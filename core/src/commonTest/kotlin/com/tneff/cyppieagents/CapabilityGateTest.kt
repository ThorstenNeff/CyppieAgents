package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityGate
import com.tneff.cyppieagents.model.CapabilityGate.CapabilityMode
import com.tneff.cyppieagents.model.CapabilityGate.EnforcedCapability
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** CYP-121: the explicit (dimension × status) gate table + fail-closed-on-unknown + the wire pin. */
class CapabilityGateTest {

    private val A = CapabilityStatus.AVAILABLE

    /** All-AVAILABLE caps with exactly [capability]'s dimension overridden to [status]. */
    private fun capsWith(capability: EnforcedCapability, status: CapabilityStatus) = Capabilities(
        structuredUsage = if (capability == EnforcedCapability.STRUCTURED_USAGE) status else A,
        toolGranularity = if (capability == EnforcedCapability.TOOL_GRANULARITY) status else A,
        reliableResult = A,
        rateLimitSignal = if (capability == EnforcedCapability.RATE_LIMIT_SIGNAL) status else A,
        coordination = A,
        kind = ConnectorKind.MCP,
    )

    @Test
    fun explicitTable_eachEnforcedDimension_pinsAllThreeStatusesSeparately() {
        // F1: every (dimension, status) is pinned on its OWN line — LIMITED is NOT collapsed into
        // UNAVAILABLE. CYP-121: LIMITED → OFF (conservative). When CYP-122 flips LIMITED to DEGRADED,
        // exactly these LIMITED lines change — a deliberate, visible edit, never a silent drift.
        for (cap in EnforcedCapability.entries) {
            assertEquals(CapabilityMode.ENABLED, CapabilityGate.mode(cap, capsWith(cap, CapabilityStatus.AVAILABLE)), "$cap AVAILABLE")
            assertEquals(CapabilityMode.OFF, CapabilityGate.mode(cap, capsWith(cap, CapabilityStatus.LIMITED)), "$cap LIMITED (CYP-121 conservative)")
            assertEquals(CapabilityMode.OFF, CapabilityGate.mode(cap, capsWith(cap, CapabilityStatus.UNAVAILABLE)), "$cap UNAVAILABLE")
        }
    }

    @Test
    fun unknownCapsFailClosed_neverAssumedAvailable() {
        // F2: null caps (unresolved agent / registry miss) → OFF for every enforced dimension.
        for (cap in EnforcedCapability.entries) {
            assertEquals(CapabilityMode.OFF, CapabilityGate.mode(cap, null), "$cap unknown → fail-closed")
            assertEquals(false, CapabilityGate.isEnabled(cap, null), "$cap unknown not enabled")
        }
    }

    @Test
    fun isEnabledOnlyWhenAvailable() {
        val cap = EnforcedCapability.STRUCTURED_USAGE
        assertTrue(CapabilityGate.isEnabled(cap, capsWith(cap, CapabilityStatus.AVAILABLE)))
        assertEquals(false, CapabilityGate.isEnabled(cap, capsWith(cap, CapabilityStatus.LIMITED)))
        assertEquals(false, CapabilityGate.isEnabled(cap, capsWith(cap, CapabilityStatus.UNAVAILABLE)))
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
        assertEquals(
            mapOf(
                "toolGranularity" to CapabilityStatus.LIMITED,
                "reliableResult" to CapabilityStatus.UNAVAILABLE,
                "coordination" to CapabilityStatus.LIMITED,
            ),
            CapabilityGate.degraded(caps).associate { it.dimension to it.status },
        )
    }

    @Test
    fun allAvailableHasNoDegradation() {
        val caps = Capabilities(A, A, A, A, A, ConnectorKind.STREAM_JSON)
        assertTrue(CapabilityGate.degraded(caps).isEmpty())
    }

    @Test
    fun capabilityDegradedEventTypeRoundTripsOnWire() {
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
