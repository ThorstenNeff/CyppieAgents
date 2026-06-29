package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.HubConfig
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.Connector
import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * CYP-120: the connector-injection seam in [BootOrchestrator]. Boot must use the injected [Connector]
 * (so CYP-121 can boot a reduced-caps `FakeConnector` to prove Mediator gating) instead of hard-newing
 * the stream-json connector. Mutation proof: revert the seam to use `defaultConnector` directly →
 * the fake is never opened, so [FakeConnector.opened] is empty and these assertions go red.
 */
class BootConnectorInjectionTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest fun tearDown() = scope.cancel()

    /** A connector test double with caller-chosen tri-state caps and no real process (no spawn). */
    private class FakeConnector(override val capabilities: Capabilities) : Connector {
        val opened = mutableListOf<String>()
        override fun open(agentId: String): ConnectorSession {
            opened += agentId
            return FakeSession(agentId)
        }
    }

    private class FakeSession(override val agentId: String) : ConnectorSession {
        override val events: Flow<StreamJsonEvent> = emptyFlow()
        override suspend fun sendTurn(turn: UserTurn) {}
        override fun close() {}
    }

    /** Never invoked when the injected [FakeConnector] is used (it does not spawn a process). */
    private class FakeSpawnerNoop : ProcessSpawner {
        override fun spawn(command: List<String>, cwd: java.io.File, env: Map<String, String>): AgentProcess =
            error("spawner must not be called when a FakeConnector is injected")
    }

    private fun config() = PlatformConfig(
        repo = RepoConfig("git@github.com:org/repo.git", "main"),
        hub = HubConfig(),
        agents = listOf(
            AgentConfig("po", "PO", Role.PO),
            AgentConfig("frontend", "FE", Role.WORKER),
            AgentConfig("backend", "BE", Role.WORKER),
        ),
    )

    private fun secrets() = Secrets(
        agentTokens = mapOf("tok-po" to "po", "tok-frontend" to "frontend", "tok-backend" to "backend"),
        operatorToken = "tok-op",
        apiKey = null,
    )

    @Test
    fun bootUsesTheInjectedConnector() {
        val limitedCaps = Capabilities(
            structuredUsage = CapabilityStatus.LIMITED,
            toolGranularity = CapabilityStatus.UNAVAILABLE,
            reliableResult = CapabilityStatus.LIMITED,
            rateLimitSignal = CapabilityStatus.LIMITED,
            coordination = CapabilityStatus.AVAILABLE,
            kind = ConnectorKind.MCP,
        )
        val fake = FakeConnector(limitedCaps)
        val gitRoot = Files.createTempDirectory("boot-inject").toFile()
        val booted = BootOrchestrator(
            config(), secrets(), WorktreeManager(FakeGit(), gitRoot), FakeSpawnerNoop(), scope,
            connectorFactory = { _ -> fake },
        ).boot()

        // The injected connector — not a hard-newed ClaudeCodeConnector — opened every agent's session.
        assertEquals(setOf("po", "frontend", "backend"), fake.opened.toSet())
        assertEquals(setOf("po", "frontend", "backend"), booted.bootedAgents.toSet())
        assertNotNull(booted.connectorSessions.session("backend"))
    }

    @Test
    fun factoryReceivesTheRealDefaultConnectorAndCanDecorate() {
        // The seam hands the default (Connector A) to the factory, so a decorator can wrap it; here we
        // assert the default really is stream-json (all-AVAILABLE) before choosing to replace it.
        val gitRoot = Files.createTempDirectory("boot-inject2").toFile()
        var seenDefaultKind: ConnectorKind? = null
        val fake = FakeConnector(
            Capabilities(
                CapabilityStatus.LIMITED, CapabilityStatus.LIMITED, CapabilityStatus.LIMITED,
                CapabilityStatus.LIMITED, CapabilityStatus.AVAILABLE, ConnectorKind.MCP,
            ),
        )
        BootOrchestrator(
            config(), secrets(), WorktreeManager(FakeGit(), gitRoot), FakeSpawnerNoop(), scope,
            connectorFactory = { default ->
                seenDefaultKind = default.capabilities.kind
                fake
            },
        ).boot()
        assertEquals(ConnectorKind.STREAM_JSON, seenDefaultKind)
    }
}
