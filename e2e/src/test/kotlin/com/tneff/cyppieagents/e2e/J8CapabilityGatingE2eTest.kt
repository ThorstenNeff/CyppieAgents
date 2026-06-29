package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.connector.Connector
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventPage
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Role
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E2E Journey J8 (CYP-124, axis C2 of `test/CONNECTOR-TEST-PLAN.md`) — Connector capability **gating &
 * degradation visibility over the REAL Event-Log egress**. This is the e2e complement to CYP-121's unit/
 * sink-level tests (CapabilityGateTest, EventProjector/StallDetectorCapabilityGateTest,
 * CapabilityDegradationBootTest): those prove the decision table + suppression + sink emission; THIS proves
 * the `capability.degraded` events actually surface over `/api/events` (HTTP bytes), follow the real
 * declared capability (mutation per dimension), and leak no secret/foreign-project at that live egress.
 *
 * Method (Doc-10 line): a [FakeConnector] with chosen capabilities is booted through the CYP-120
 * `connectorFactory` seam (threaded into [e2ePlatform], CYP-124) → BootOrchestrator emits one content-free
 * `capability.degraded` per non-AVAILABLE dimension per active agent → a journey reads the result.
 *
 * Surface note: `capability.degraded` is emitted **once at boot** (idempotent, see BootOrchestrator KDoc),
 * so it is a historical record → **REST `/api/events`** is its visibility surface. The events-WS
 * (`/ws/events`) is live-only (`sink.subscribe`, no backlog replay); its live-egress needle-absence is
 * already covered by J7 — not re-asserted here to avoid duplication.
 *
 * Active "alpha" = po + frontend (booted via the factory → both declare the FakeConnector's caps).
 * "beta" carries the SECRET as its api-key for the needle axis (added post-boot → no capability events).
 */
class J8CapabilityGatingE2eTest {

    private val allDims = setOf("structuredUsage", "toolGranularity", "reliableResult", "rateLimitSignal", "coordination")

    private fun platform(connectorFactory: ((Connector) -> Connector)?) = e2ePlatform(
        listOf(
            SeedProject("alpha", "A", listOf(SeedAgent("po", Role.PO), SeedAgent("frontend"))),
            SeedProject("beta", "B", listOf(SeedAgent("backend")), apiKey = Needles.SECRET),
        ),
        connectorFactory = connectorFactory,
    )

    /** All `capability.degraded` events on the active-scoped REST Event-Log (high limit → no paging). */
    private suspend fun E2ePlatform.degradedEvents(): List<Event> =
        asOperator().use { c ->
            c.get("$baseUrl/api/events?limit=500").body<EventPage>().events
                .filter { it.type == EventType.CAPABILITY_DEGRADED }
        }

    private fun Event.dimension(): String = detail["dimension"]!!.jsonPrimitive.content
    private fun Event.statusName(): String = detail["status"]!!.jsonPrimitive.content

    @Test
    fun unavailableConnector_everyDimensionDegrades_perActiveAgent_overRest() = runBlocking {
        platform { FakeConnector.uniform(CapabilityStatus.UNAVAILABLE) }.use { p ->
            val byAgent = p.degradedEvents().groupBy { it.agentId }
            // EXACT: only the two ACTIVE (alpha) agents declared via the factory emit; beta's backend was
            // added post-boot, so it has no capability events (boot-only emit).
            assertEquals(setOf("po", "frontend"), byAgent.keys, "only active-project agents declare at boot")
            byAgent.forEach { (agent, events) ->
                assertEquals(allDims, events.map { it.dimension() }.toSet(), "$agent: all five dimensions degraded")
                assertTrue(events.all { it.statusName() == "UNAVAILABLE" }, "$agent: each marked UNAVAILABLE, not pretended")
            }
        }
    }

    @Test
    fun degradedEvent_detailIsContentFree_dimensionAndStatusKeysOnly() = runBlocking {
        platform { FakeConnector.uniform(CapabilityStatus.UNAVAILABLE) }.use { p ->
            val events = p.degradedEvents()
            assertTrue(events.isNotEmpty(), "non-vacuous: there are degraded events to inspect")
            // metadata-only at the LIVE egress: the projector never widens the detail beyond {dimension,status}.
            events.forEach { e ->
                assertEquals(setOf("dimension", "status"), e.detail.keys, "detail carries no content, only the two metadata keys")
            }
        }
    }

    @Test
    fun mutation_onlyTheReducedDimensionDegrades_overRest() = runBlocking {
        // Single dimension reduced; the other four AVAILABLE. Proves the event follows the REAL declared
        // capability, not a constant — flip it and the degraded set flips with it (non-vacuity per axis).
        val onlyRateLimitOff = Capabilities(
            structuredUsage = CapabilityStatus.AVAILABLE,
            toolGranularity = CapabilityStatus.AVAILABLE,
            reliableResult = CapabilityStatus.AVAILABLE,
            rateLimitSignal = CapabilityStatus.UNAVAILABLE,
            coordination = CapabilityStatus.AVAILABLE,
            kind = ConnectorKind.MCP,
        )
        platform { FakeConnector(onlyRateLimitOff) }.use { p ->
            val byAgent = p.degradedEvents().groupBy { it.agentId }
            assertEquals(setOf("po", "frontend"), byAgent.keys)
            byAgent.forEach { (agent, events) ->
                assertEquals(setOf("rateLimitSignal"), events.map { it.dimension() }.toSet(), "$agent: ONLY the reduced dim degrades")
            }
        }
    }

    @Test
    fun connectorA_allAvailable_emitsNoDegradation_overRest() = runBlocking {
        // No factory → the production default connector (all-AVAILABLE, STREAM_JSON). The live egress shows
        // ZERO capability.degraded — prod path byte-unchanged. Non-vacuous against the tests above that DO see them.
        platform(connectorFactory = null).use { p ->
            assertTrue(p.degradedEvents().isEmpty(), "Connector A degrades nothing at the live egress")
        }
    }

    @Test
    fun degradedEvents_metadataOnly_noSecretNoForeignLeak_overRest() = runBlocking {
        // SECRET is seeded as beta's api-key; reduced caps on alpha guarantee real degraded events exist
        // (non-vacuous). The active-scoped (alpha) Event-Log must carry neither the secret nor the foreign
        // 'beta' project id at this capability egress — the CYP-44 needle line applied to capability events.
        platform { FakeConnector.uniform(CapabilityStatus.UNAVAILABLE) }.use { p ->
            val raw = p.asOperator().use { it.get("${p.baseUrl}/api/events?limit=500").bodyAsText() }
            assertTrue(raw.contains("capability.degraded"), "non-vacuous: capability events are present in the body being grepped")
            assertNoNeedles("capability events REST (active=alpha)", raw, foreignProjectIds = setOf("beta"))
        }
    }
}
