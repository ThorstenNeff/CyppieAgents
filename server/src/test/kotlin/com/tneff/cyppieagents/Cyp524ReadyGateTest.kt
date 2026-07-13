package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.HubConfig
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.controlplane.HubAdmissionResult
import com.tneff.cyppieagents.crypto.HubIdentity
import com.tneff.cyppieagents.crypto.SecretStore
import com.tneff.cyppieagents.model.Role
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-524 — the STRUCTURAL half of the boot-ordering-race fix: the self-admit launch AWAITS the [readyGate] before it
 * dials this process' own edge. `Application.installPlatform` fires `ApplicationStarted` (server listening) into that
 * gate. Without it, the admit coroutine — launched during module load, before Netty binds — races the socket and hits
 * a not-yet-up edge (502 → non-JSON → SourceByteReadChannel). Verifies the launch does not self-admit until ready.
 */
class Cyp524ReadyGateTest {

    private class FakeProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }

    private class FakeSpawner : ProcessSpawner {
        override fun spawn(command: List<String>, cwd: File, env: Map<String, String>): AgentProcess = FakeProcess()
    }

    private fun config() = PlatformConfig(
        repo = RepoConfig("git@github.com:org/repo.git", "main"),
        hub = HubConfig(),
        agents = listOf(AgentConfig("po", "PO", Role.PO)),
    )

    private fun secrets() = Secrets(agentTokens = mapOf("tok-po" to "po"), operatorToken = "tok-op", apiKey = null)

    private fun gitRoot() = Files.createTempDirectory("cyp524-readygate").toFile()

    @Test
    fun selfAdmit_awaitsReadyGate_doesNotFireBeforeServerReady() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val gate = CompletableDeferred<Unit>()          // stands in for `ApplicationStarted`
            val admitCalled = CompletableDeferred<Unit>()
            val factory: (HubIdentity?, SecretStore?) -> (suspend () -> HubAdmissionResult)? = { _, _ ->
                { admitCalled.complete(Unit); HubAdmissionResult(admitted = true) }
            }
            BootOrchestrator(
                config(), secrets(), WorktreeManager(FakeGit(), gitRoot()), FakeSpawner(), scope,
                hubAdmissionFactory = factory,
                readyGate = { gate.await() },
            ).boot()

            // Let the launched coroutine run up to the gate barrier. With the gate still open, the self-admit must NOT
            // have fired — this is exactly the boot-ordering race the gate closes. (Revert the gate → admit fires now.)
            delay(300)
            assertFalse(admitCalled.isCompleted, "the hub must NOT self-admit before the server is ready (gate still open)")

            gate.complete(Unit) // server is now listening
            withTimeout(2_000) { admitCalled.await() }
            assertTrue(admitCalled.isCompleted, "once ready, the self-admit fires")
        } finally {
            scope.cancel()
        }
    }
}
