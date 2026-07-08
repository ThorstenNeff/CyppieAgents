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
 *  - **(a) write-enabled:** a connector WITH the grant spawns the worker WITH the `bypassPermissions` MODE.
 *  - **(b) distinct mechanism (CYP-321 re-point):** the same connector WITHOUT the grant (the production
 *    default) now bypasses too — but via the FLAG `--dangerously-skip-permissions` (MVP-wide, Auftraggeber-
 *    authorized), NOT the grant-gated `bypassPermissions` MODE. The two mechanisms stay disjoint.
 *
 * **Mutation:** route the grant path through `streamJsonArgs` (the default) instead of the separate
 * `sandboxBypassStreamJsonArgs` override → the grant spawn loses the MODE mechanism → (a) reddens. The MODE
 * vector guard (`require`) is still covered in [ConnectorDefaultsTest].
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
    fun connectorWithoutGrant_mvpSpawnsWithSkipFlag_notTheGrantMode() {
        // CYP-321 re-point: the production default (no grant) now spawns WITH --dangerously-skip-permissions
        // (MVP-wide, Auftraggeber-authorized) — via the FLAG, NOT the grant-gated `bypassPermissions` MODE.
        val spawner = CapturingSpawner()
        connector(spawner, grant = null).open("backend")
        assertTrue(ConnectorDefaults.bypassesPermissions(spawner.command), "the MVP prod spawn bypasses via the flag (CYP-321)")
        assertTrue(spawner.command.contains(ConnectorDefaults.DANGEROUS_FLAG), "the spawn carries --dangerously-skip-permissions")
        assertFalse(spawner.command.contains("bypassPermissions"), "via the FLAG, not the grant-only MODE string")
    }
}
