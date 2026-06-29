package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.model.MessageKind
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.support.RecordingConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-132 R2 — pins the **production boot wiring** (the CYP-122-M4 class of bug). The per-axis
 * [MessageDelivererTest] each wire their own deliverer, so they stay green even if BootOrchestrator
 * forgets to connect `deliverer::onPosted` to the hub or the register-listener to ConnectorSessions —
 * while prod would deliver NOTHING. This test drives the REAL boot path end-to-end.
 *
 * Mutation: comment out `hub.onPosted = deliverer::onPosted` (or `sessions.addRegisterListener(...)`)
 * in BootOrchestrator → only this test reddens.
 */
class DelivererBootWiringTest {

    // Unconfined → the deliverer's drain (no real suspension point) runs inline, so a post is delivered
    // synchronously; no polling needed and no cross-dispatcher flakiness.
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    private class FakeProcess : AgentProcess {
        override val stdoutLines = kotlinx.coroutines.flow.emptyFlow<String>()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }

    private class FakeSpawner : ProcessSpawner {
        override fun spawn(command: List<String>, cwd: File, env: Map<String, String>): AgentProcess = FakeProcess()
    }

    private fun config() = PlatformConfig(
        repo = RepoConfig("git@github.com:org/repo.git", "main"),
        agents = listOf(
            AgentConfig("po", "PO", Role.PO),
            AgentConfig("backend", "BE", Role.WORKER),
        ),
    )

    private fun secrets() = Secrets(
        agentTokens = mapOf("tok-po" to "po", "tok-backend" to "backend"),
        operatorToken = "tok-op",
        apiKey = null,
    )

    @Test
    fun bootWiresDelivererSoAPostIsDeliveredInbound() {
        val root = Files.createTempDirectory("deliverer-boot").toFile()
        try {
            val rec = RecordingConnector()
            val booted = BootOrchestrator(
                config(), secrets(), WorktreeManager(FakeGit(), root), FakeSpawner(), scope,
                connectorFactory = { rec },
            ).boot()
            assertTrue("backend" in booted.bootedAgents, "worker booted a (recording) session")

            // PO delegates through the SINGLE funnel; the WIRED deliverer injects it into backend inline.
            booted.hub.postAsAgent("po", "po-backend", "delegated via boot wiring", MessageMeta(kind = MessageKind.TASK))
            assertTrue(
                rec.sessions["backend"]?.received?.any { it.contains("delegated via boot wiring") } == true,
                "the boot-wired deliverer must inject the post into the worker session",
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
