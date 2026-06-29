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
import com.tneff.cyppieagents.events.EventFilter
import com.tneff.cyppieagents.events.Page
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.support.FakeConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-121 e2e: a `FakeConnector(reduced caps)` booted through the CYP-120 `connectorFactory` seam emits a
 * content-free `capability.degraded` event per non-AVAILABLE dimension (incl. the log-only
 * reliableResult/coordination), and the Connector-A production path (no factory) emits none — proving the
 * live path is byte-unchanged. The degradation events also prove boot populated the CapabilityRegistry.
 */
class CapabilityDegradationBootTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    private class NoopSpawner : ProcessSpawner {
        override fun spawn(command: List<String>, cwd: File, env: Map<String, String>): AgentProcess =
            error("FakeConnector is injected → no spawn expected")
    }

    /** Returns a fake process so the real Connector A can "spawn" without a live claude. */
    private class FakeProcessSpawner : ProcessSpawner {
        override fun spawn(command: List<String>, cwd: File, env: Map<String, String>) = object : AgentProcess {
            override val stdoutLines = kotlinx.coroutines.flow.emptyFlow<String>()
            override suspend fun writeLine(line: String) {}
            override fun destroy() {}
        }
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

    private suspend fun degradedEvents(sink: com.tneff.cyppieagents.events.EventSink, expectAtLeast: Int): List<Event> =
        withTimeout(5_000) {
            var out: List<Event> = emptyList()
            while (out.size < expectAtLeast) {
                out = sink.query(EventFilter(type = EventType.CAPABILITY_DEGRADED), Page()).events
                if (out.size < expectAtLeast) delay(20)
            }
            out
        }

    @Test
    fun reducedCapsEmitDegradedEventPerNonAvailableDimension() = runBlocking {
        // structuredUsage LIMITED · toolGranularity UNAVAILABLE · reliableResult UNAVAILABLE ·
        // rateLimitSignal LIMITED · coordination LIMITED → all five are non-AVAILABLE → 5 per agent.
        val reduced = Capabilities(
            structuredUsage = CapabilityStatus.LIMITED,
            toolGranularity = CapabilityStatus.UNAVAILABLE,
            reliableResult = CapabilityStatus.UNAVAILABLE,
            rateLimitSignal = CapabilityStatus.LIMITED,
            coordination = CapabilityStatus.LIMITED,
            kind = ConnectorKind.MCP,
        )
        val fake = FakeConnector(reduced)
        val booted = BootOrchestrator(
            config(), secrets(), WorktreeManager(FakeGit(), Files.createTempDirectory("cap-deg").toFile()),
            NoopSpawner(), scope, connectorFactory = { _ -> fake },
        ).boot()

        val events = degradedEvents(booted.eventSink, expectAtLeast = 3 * 5)
        assertEquals(setOf("po", "frontend", "backend"), events.map { it.agentId }.toSet())
        for (agent in listOf("po", "frontend", "backend")) {
            val dims = events.filter { it.agentId == agent }.map { (it.detail["dimension"] as JsonPrimitive).content }.toSet()
            assertEquals(
                setOf("structuredUsage", "toolGranularity", "reliableResult", "rateLimitSignal", "coordination"),
                dims,
                "every non-AVAILABLE dimension logged for $agent (incl. log-only reliableResult/coordination)",
            )
        }
        // First-class warn + content-free {dimension, status} only (needle-absence).
        val sample = events.first()
        assertEquals(Severity.WARN, sample.severity)
        assertEquals(setOf("dimension", "status"), sample.detail.keys)
    }

    @Test
    fun connectorAProductionPathEmitsNoDegradation() = runBlocking {
        // No factory → real Connector A (all-AVAILABLE). Boot spawns via the real connector → use a
        // FakeSpawner so the stream-json connector's open() gets a fake process, not a live claude.
        val booted = BootOrchestrator(
            config(), secrets(), WorktreeManager(FakeGit(), Files.createTempDirectory("cap-none").toFile()),
            FakeProcessSpawner(), scope,
        ).boot()

        // Let the async recorder drain, then assert ZERO capability.degraded events on the prod path.
        val spawned = withTimeout(5_000) {
            var n = 0
            while (n < 3) { n = booted.eventSink.query(EventFilter(type = EventType.AGENT_SPAWNED), Page()).events.size; if (n < 3) delay(20) }
            n
        }
        assertEquals(3, spawned)
        assertTrue(
            booted.eventSink.query(EventFilter(type = EventType.CAPABILITY_DEGRADED), Page()).events.isEmpty(),
            "Connector A is all-AVAILABLE → no degradation events, live path byte-unchanged",
        )
    }

    @Test
    fun multiplexingBoot_onlyTheMcpAgentIsDegraded_pinsPerAgentCapabilitiesFor() = runBlocking {
        // Reviewer merge-gate (M4): the prior boot tests use a UNIFORM connector, where
        // capabilities == capabilitiesFor(*), so the `capabilitiesFor(agent.id) → capabilities` mutation
        // (router default = A's all-AVAILABLE → a B agent over-trusted as full fidelity) stays GREEN. This
        // boots a REAL multiplexing setup via the production ConnectorRouter: backend = Connector B (MCP),
        // po/frontend = Connector A. ONLY backend gets its column-B `capability.degraded` events; the A
        // agents get none. Under the M4 mutation every agent would resolve A's all-AVAILABLE caps → backend
        // emits nothing → this reddens.
        val cfg = PlatformConfig(
            repo = RepoConfig("git@github.com:org/repo.git", "main"),
            hub = HubConfig(),
            agents = listOf(
                AgentConfig("po", "PO", Role.PO),
                AgentConfig("frontend", "FE", Role.WORKER),
                AgentConfig("backend", "BE", Role.WORKER, connectorKind = ConnectorKind.MCP),
            ),
        )
        // No connectorFactory → the real ConnectorRouter selects A vs B per agent.connectorKind. The A
        // agents spawn via the fake process; the B agent's McpConnector does not spawn.
        val booted = BootOrchestrator(
            cfg, secrets(), WorktreeManager(FakeGit(), Files.createTempDirectory("cap-mux").toFile()),
            FakeProcessSpawner(), scope,
        ).boot()

        // Connector B (col B) has 4 non-AVAILABLE dims (coordination is AVAILABLE) → 4 events, all backend.
        val events = degradedEvents(booted.eventSink, expectAtLeast = 4)
        assertEquals(setOf("backend"), events.map { it.agentId }.toSet(), "only the MCP agent is degraded")
        val dims = events.filter { it.agentId == "backend" }.map { (it.detail["dimension"] as JsonPrimitive).content }.toSet()
        assertEquals(
            setOf("structuredUsage", "toolGranularity", "reliableResult", "rateLimitSignal"),
            dims,
            "backend gets exactly Connector B's non-AVAILABLE dimensions (coordination is AVAILABLE)",
        )
    }
}
