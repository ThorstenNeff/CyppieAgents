package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ClaudeCodeConnector
import com.tneff.cyppieagents.connector.HubMcpConfigWriter
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
import kotlin.test.assertTrue

/**
 * CYP-146 — the Connector-A spawn exposes `hub_send`: the command carries `--mcp-config <abspath>` and
 * pre-approves ONLY `mcp__hub__hub_send` (F3 tight allowlist), and the mcp-config is written out-of-repo
 * with the agent token (F1). This is the regression guard for the exact RB1 gap (no hub tool was exposed).
 *
 * **Mutation:** drop `mcpConfigWriter` (→ no `--mcp-config`, no hub tool) → these assertions redden.
 */
class HubMcpSpawnWiringTest {

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

    private fun connector(spawner: CapturingSpawner, mcpDir: File): ClaudeCodeConnector {
        val hub = Hub(HubState.hubAndSpoke(listOf(Agent("po", "PO", Role.PO, "po"), Agent("backend", "BE", Role.WORKER, "backend"))), InMemoryMessageStore())
        val registry = SessionRegistry()
        return ClaudeCodeConnector(
            spawner = spawner,
            worktreesRoot = Files.createTempDirectory("wt").toFile().let { d -> { d } },
            resolveApiKey = { null },
            registry = registry,
            router = MediationRouter(registry, hub),
            turnQueue = SessionTurnQueue(),
            scope = scope,
            mcpConfigWriter = HubMcpConfigWriter(mcpDir, "http://127.0.0.1:8787/mcp/hub"),
            tokenFor = { "tok-$it" },
        )
    }

    @Test
    fun spawnCarriesMcpConfigAndPreApprovesHubSend() {
        val spawner = CapturingSpawner()
        val mcpDir = Files.createTempDirectory("mcp").toFile()
        connector(spawner, mcpDir).open("backend")

        val cmd = spawner.command
        // --mcp-config <abspath> present, and the file actually exists out-of-repo with the agent token.
        assertTrue("--mcp-config" in cmd, "the spawn must expose the hub via --mcp-config")
        val cfgPath = cmd[cmd.indexOf("--mcp-config") + 1]
        val cfg = File(cfgPath)
        assertTrue(cfg.isAbsolute && cfg.exists(), "mcp-config written at an absolute path")
        assertTrue(cfg.toPath().startsWith(mcpDir.toPath()), "written under the out-of-repo dir (F1)")
        assertTrue(cfg.readText().contains("tok-backend"), "carries the agent's bearer token")
        // Tight allowlist: the hub tool is pre-approved, by its prefixed MCP name.
        assertTrue("mcp__hub__hub_send" in cmd, "pre-approves only mcp__hub__hub_send (F3)")
        // F3: never the dangerous mode.
        assertTrue(cmd.none { it == "bypassPermissions" || it == "--dangerously-skip-permissions" }, "never bypassPermissions")
    }
}
