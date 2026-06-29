package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityGate
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CYP-121: the pure capability-gating decision + the `capability.degraded` EventType wire pin. */
class CapabilityGateTest {

    @Test
    fun onlyAvailableEnablesAFunction() {
        assertTrue(CapabilityGate.enabled(CapabilityStatus.AVAILABLE))
        assertFalse(CapabilityGate.enabled(CapabilityStatus.LIMITED))
        assertFalse(CapabilityGate.enabled(CapabilityStatus.UNAVAILABLE))
    }

    @Test
    fun degradedListsEveryNonAvailableDimensionWithItsName() {
        val caps = Capabilities(
            structuredUsage = CapabilityStatus.AVAILABLE,
            toolGranularity = CapabilityStatus.LIMITED,
            reliableResult = CapabilityStatus.UNAVAILABLE,
            rateLimitSignal = CapabilityStatus.AVAILABLE,
            coordination = CapabilityStatus.LIMITED,
            kind = ConnectorKind.MCP,
        )
        val degraded = CapabilityGate.degraded(caps).associate { it.dimension to it.status }
        assertEquals(
            mapOf(
                "toolGranularity" to CapabilityStatus.LIMITED,
                "reliableResult" to CapabilityStatus.UNAVAILABLE,
                "coordination" to CapabilityStatus.LIMITED,
            ),
            degraded,
        )
    }

    @Test
    fun allAvailableHasNoDegradation() {
        val a = CapabilityStatus.AVAILABLE
        val caps = Capabilities(a, a, a, a, a, ConnectorKind.STREAM_JSON)
        assertTrue(CapabilityGate.degraded(caps).isEmpty())
    }

    @Test
    fun capabilityDegradedEventTypeRoundTripsOnWire() {
        // First-class warn type (CYP-64 tolerant-enum pattern): a `capability.degraded` event must
        // round-trip as that type, not flatten to UNKNOWN.
        assertEquals(EventType.CAPABILITY_DEGRADED, EventType.fromWire("capability.degraded"))
        val e = Event(
            id = "01J", projectId = "default", agentId = "backend",
            type = EventType.CAPABILITY_DEGRADED, severity = Severity.WARN, ts = 1, seq = 1,
        )
        val wire = CommJson.encodeToString(e)
        assertTrue(wire.contains("\"capability.degraded\""), wire)
        assertEquals(EventType.CAPABILITY_DEGRADED, CommJson.decodeFromString<Event>(wire).type)
    }
}
