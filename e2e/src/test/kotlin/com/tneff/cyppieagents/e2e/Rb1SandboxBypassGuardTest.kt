package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.CommandResult
import com.tneff.cyppieagents.boot.CommandRunner
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ClaudeCodeConnector
import com.tneff.cyppieagents.connector.ConnectorDefaults
import com.tneff.cyppieagents.connector.ProcessSpawner
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
 * CYP-163 ⭐ — the hermetic, 0-quota **Tester guard** for the sandbox-only `bypassPermissions` override, at
 * the **harness/boot level** (Backend's [com.tneff.cyppieagents.SandboxBypassConnectorTest] pins it at the bare
 * connector level). No `claude`, no real git — a [CapturingSpawner] records the spawn command, a fake
 * [CommandRunner] no-ops git. Both guard axes, each with a positive control + a reddening mutation:
 *
 *  - **(a) write-enabled:** the RB1 harness ([Rb1RealAgentHarness.bootRealAgentPlatform]) spawns the WORKER
 *    WITH `bypassPermissions` (RB1 Run #5's blocker: headless writes need a permission mode). Mutation: drop
 *    `sandboxBypassGrant = SandboxBypassGrant.rb1Sandbox()` in the harness → worker back to sharp → reddens.
 *  - **(b) non-leak (Gate #4) — closes the Reviewer-flagged gap:** Backend's connector test passes the grant
 *    arg EXPLICITLY (`grant = null`), so it pins neither the ctor DEFAULT nor the BOOT path. This guard pins
 *    BOTH, so the two leak mutations the Reviewer named redden:
 *      - **(b-i) ctor default:** a [ClaudeCodeConnector] built with the `sandboxBypassGrant` arg **OMITTED**
 *        (the production default) spawns sharp. Mutation `null → SandboxBypassGrant.rb1Sandbox()` (MUT2) reddens.
 *      - **(b-ii) boot default:** a [BootOrchestrator] built with NO grant (the production boot) spawns sharp.
 *        Mutation "boot reaches the grant through to the prod connector" reddens.
 *
 * SCOPE NOTE (honest): the grant is connector-level, so on the RB1 sandbox BOTH agents (po+backend) spawn with
 * bypass — within the disposable-sandbox envelope (no product/net). The invariant that matters, and that (b)
 * pins, is that PROD/DEFAULT never bypasses. Axis (a) asserts the WORKER (the one that writes/commits/pushes).
 */
class Rb1SandboxBypassGuardTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    private class FakeProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }

    /** Records the spawn command per agent (keyed by the injected HUB_AGENT_ID env) — no real process. */
    private class CapturingSpawner : ProcessSpawner {
        val byAgent = mutableMapOf<String, List<String>>()
        override fun spawn(command: List<String>, cwd: File, env: Map<String, String>): AgentProcess {
            byAgent[env["HUB_AGENT_ID"] ?: "?"] = command
            return FakeProcess()
        }
    }

    /** No-op git: clone/worktree "succeed" (exit 0) without touching the disk. */
    private class FakeRunner : CommandRunner {
        override fun run(command: List<String>, cwd: File): CommandResult = CommandResult(0, "")
    }

    // ---- Axis (a): the RB1 harness grants the worker the write-enabling bypass ----
    @Test
    fun harnessSpawnsWorkerWithBypass() {
        val gitRoot = Files.createTempDirectory("rb1-bypass-guard-a").toFile()
        val spawner = CapturingSpawner()
        Rb1RealAgentHarness.bootRealAgentPlatform(
            gitRoot = gitRoot,
            repoUrl = "file://" + gitRoot.absolutePath,
            scope = scope,
            spawner = spawner,
            commandRunner = FakeRunner(),
        )
        val workerCmd = spawner.byAgent.getValue("backend")
        assertTrue(ConnectorDefaults.bypassesPermissions(workerCmd),
            "(a) the RB1 sandbox WORKER spawn must carry the bypass mode so it can write/commit/push (Run #5 fix)")
        assertTrue(workerCmd.contains("bypassPermissions"), "the bypass flag is inspectable in the captured worker command")
    }

    // ---- Axis (b-i): the ClaudeCodeConnector ctor DEFAULT (no grant arg) is sharp — pins MUT2 ----
    @Test
    fun connectorDefault_noGrantArg_spawnsSharp() {
        val spawner = CapturingSpawner()
        val hub = Hub(
            HubState.hubAndSpoke(listOf(Agent("po", "PO", Role.PO, "po"), Agent("backend", "BE", Role.WORKER, "backend"))),
            InMemoryMessageStore(),
        )
        val registry = SessionRegistry()
        // sandboxBypassGrant arg is DELIBERATELY OMITTED → relies on the ctor DEFAULT. This is the pin the
        // existing SandboxBypassConnectorTest misses (it always passes grant=null explicitly). Flipping the
        // ClaudeCodeConnector ctor default null → SandboxBypassGrant.rb1Sandbox() (MUT2) reddens THIS test.
        ClaudeCodeConnector(
            spawner = spawner,
            worktreesRoot = Files.createTempDirectory("wt").toFile(),
            resolveApiKey = { null },
            registry = registry,
            router = MediationRouter(registry, hub),
            turnQueue = SessionTurnQueue(),
            scope = scope,
        ).open("backend")
        val cmd = spawner.byAgent.getValue("backend")
        assertFalse(ConnectorDefaults.bypassesPermissions(cmd),
            "(b-i) the connector ctor DEFAULT (no grant) must spawn sharp — bypass must never be the default (Gate #4)")
        assertFalse(cmd.contains("bypassPermissions"))
    }

    // ---- Axis (b-ii): a BootOrchestrator with NO grant (production boot) is sharp — pins the boot leak ----
    @Test
    fun bootDefault_noGrant_spawnsSharp() {
        val gitRoot = Files.createTempDirectory("rb1-bypass-guard-b").toFile()
        val spawner = CapturingSpawner()
        val config = Rb1RealAgentHarness.sandboxConfig("file://" + gitRoot.absolutePath)
        val secrets = Secrets(
            agentTokens = config.agents.associate { "tok-${it.id}" to it.id },
            operatorToken = "tok-operator",
            apiKey = null,
        )
        // sandboxBypassGrant is DELIBERATELY OMITTED on this BootOrchestrator → the production boot default.
        // Mutation "boot reaches the grant through to the prod connector" (set the grant here, or flip the
        // BootOrchestrator ctor default) reddens THIS test.
        BootOrchestrator(
            config = config,
            secrets = secrets,
            worktrees = WorktreeManager(FakeRunner(), gitRoot, config.projectId),
            spawner = spawner,
            scope = scope,
        ).boot()
        for (agent in listOf("po", "backend")) {
            val cmd = spawner.byAgent.getValue(agent)
            assertFalse(ConnectorDefaults.bypassesPermissions(cmd),
                "(b-ii) the prod BootOrchestrator (no grant) must spawn '$agent' sharp — the sandbox grant must not leak into boot")
            assertFalse(cmd.contains("bypassPermissions"))
        }
    }
}
