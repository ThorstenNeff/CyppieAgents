package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.boot.CommandResult
import com.tneff.cyppieagents.boot.CommandRunner
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ProcessSpawner
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
import kotlin.test.assertTrue

/**
 * CYP-150 ⭐ — the **hermetic, 0-quota quota-guard**: a booted Connector-A PO (the RB1 harness path) MUST
 * be offered the hub via `--mcp-config` + the pre-approved `mcp__hub__hub_send` tool (CYP-146). RB1 #4 ran
 * the PO **solo** because the harness booted `BootOrchestrator` without `mcpConfigDir` — this test catches
 * exactly that **without spending a real-run quota**, so we never burn a run re-discovering it.
 *
 * No `claude`, no real git: a [CapturingSpawner] records the spawn command, a fake [CommandRunner] no-ops git.
 *
 * **Mutation:** drop the `mcpConfigDir = …` arg in [Rb1RealAgentHarness.bootRealAgentPlatform] → no
 * `--mcp-config`, no hub tool → this test reddens (the exact RB1 #4 regression).
 */
class Rb1HubSendOfferedTest {

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

    @Test
    fun bootedConnectorAPoIsOfferedHubSend_mcpConfigPresent() {
        val gitRoot = Files.createTempDirectory("rb1-hermetic-guard").toFile()
        val spawner = CapturingSpawner()
        // Boots the SAME harness path RB1 uses — but hermetic (fake spawner + fake git, 0 quota).
        Rb1RealAgentHarness.bootRealAgentPlatform(
            gitRoot = gitRoot,
            repoUrl = "file://" + gitRoot.absolutePath,
            scope = scope,
            spawner = spawner,
            commandRunner = FakeRunner(),
        )

        val poCmd = spawner.byAgent.getValue("po")
        assertTrue("--mcp-config" in poCmd, "the booted Connector-A PO must be offered the hub via --mcp-config")
        assertTrue("mcp__hub__hub_send" in poCmd, "the hub_send tool must be pre-approved (mcp__hub__hub_send)")
        // …and the referenced mcp-config is actually written, out-of-repo, carrying the PO's token.
        val cfg = File(poCmd[poCmd.indexOf("--mcp-config") + 1])
        assertTrue(cfg.exists(), "the mcp-config file is written")
        assertTrue(cfg.readText().contains("tok-po"), "carries the PO's bearer token (the auth the agent uses for /mcp/hub)")
    }
}
