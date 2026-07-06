package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ClaudeCodeConnector
import com.tneff.cyppieagents.connector.ConnectorDefaults
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.connector.SandboxBypassGrant
import com.tneff.cyppieagents.mediation.MediationRouter
import com.tneff.cyppieagents.mediation.SessionRegistry
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-163 — the sandbox-only `bypassPermissions` grant, threaded through [ClaudeCodeConnector] to the
 * spawned command. The grant is **human (Auftraggeber) + reviewer signed**, RB1 throwaway-sandbox ONLY.
 * Pins the two guard axes at the connector level (the Tester re-pins them at the harness level):
 *  - **(a) write-enabled:** a connector WITH the grant spawns the worker WITH `bypassPermissions`.
 *  - **(b) non-leak:** the same connector WITHOUT the grant (the production default) spawns sharp — never bypass.
 *
 * **Mutation:** route the grant path through `streamJsonArgs` (the default) instead of the separate
 * `sandboxBypassStreamJsonArgs` override → the grant spawn would throw (Gate #4) / lose bypass → (a) reddens;
 * or drop the prod `require` → (b)'s non-leak guarantee evaporates (covered in [ConnectorDefaultsTest]).
 */
class SandboxBypassConnectorTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    private class FakeProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }

    private class CapturingSpawner : ProcessSpawner {
        lateinit var command: List<String>
        override fun spawn(command: List<String>, cwd: File, env: Map<String, String>): AgentProcess {
            this.command = command
            return FakeProcess()
        }
    }

    private fun connector(spawner: CapturingSpawner, grant: SandboxBypassGrant?): ClaudeCodeConnector {
        val hub = Hub(
            HubState.hubAndSpoke(listOf(Agent("po", "PO", Role.PO, "po"), Agent("backend", "BE", Role.WORKER, "backend"))),
            InMemoryMessageStore(),
        )
        val registry = SessionRegistry()
        return ClaudeCodeConnector(
            spawner = spawner,
            worktreesRoot = Files.createTempDirectory("wt").toFile().let { d -> { d } },
            resolveApiKey = { null },
            registry = registry,
            router = MediationRouter(registry, hub),
            turnQueue = SessionTurnQueue(),
            scope = scope,
            sandboxBypassGrant = grant,
        )
    }

    @Test
    fun connectorWithGrant_spawnsWorkerWithBypass() {
        val spawner = CapturingSpawner()
        connector(spawner, grant = SandboxBypassGrant.rb1Sandbox()).open("backend")
        assertTrue(ConnectorDefaults.bypassesPermissions(spawner.command), "the sandbox worker spawn carries bypassPermissions")
        assertTrue(spawner.command.contains("bypassPermissions"), "the bypass flag is inspectable in the captured command")
    }

    @Test
    fun connectorWithoutGrant_spawnsSharp_noLeak() {
        val spawner = CapturingSpawner()
        connector(spawner, grant = null).open("backend") // the production default
        assertFalse(ConnectorDefaults.bypassesPermissions(spawner.command), "the prod default never bypasses (Gate #4)")
        assertFalse(spawner.command.contains("bypassPermissions"))
    }
}
