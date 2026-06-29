package com.tneff.cyppieagents.scanner

import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-121: the Warden stall detector is gated by `rateLimitSignal` (Doc 10 §3). When a connector lacks a
 * trusted structured throttle marker, an `error.ratelimit` event must NOT arm a stall — stall detection
 * is honestly off, not driven off an untrusted signal. Mutation: drop the `rateLimitSignalEnabled` guard
 * in [StallDetector.onEvent] → the throttle arms under reduced caps → `stallSuppressed…` goes red.
 */
class StallDetectorCapabilityGateTest {

    private fun caps(rateLimit: CapabilityStatus) = Capabilities(
        structuredUsage = CapabilityStatus.AVAILABLE,
        toolGranularity = CapabilityStatus.AVAILABLE,
        reliableResult = CapabilityStatus.AVAILABLE,
        rateLimitSignal = rateLimit,
        coordination = CapabilityStatus.AVAILABLE,
        kind = ConnectorKind.MCP,
    )

    private fun throttle(agentId: String, ts: Long) = Event(
        id = "rl-$ts", ts = ts, seq = ts, agentId = agentId, projectId = "default",
        type = EventType.ERROR_RATELIMIT, severity = Severity.WARN,
        detail = buildJsonObject { put("status", "blocked") },
    )

    private val armTs = 1_000L
    private val afterThreshold = armTs + StallDetector.DEFAULT_THRESHOLD_MS + 1

    @Test
    fun availableRateLimitArmsAndSuspects_control() {
        val d = StallDetector(capabilities = { caps(CapabilityStatus.AVAILABLE) })
        d.onEvent(throttle("backend", armTs))
        val signals = d.sweep(afterThreshold)
        assertEquals(1, signals.size)
        assertEquals(StallDetector.SIGNAL_TYPE, signals.single().type)
    }

    @Test
    fun noResolverArms_legacy() {
        val d = StallDetector() // no capability system configured → no gating (pre-CYP-121 behaviour)
        d.onEvent(throttle("backend", armTs))
        assertEquals(1, d.sweep(afterThreshold).size)
    }

    @Test
    fun registryMissFailsClosed_doesNotArm() {
        // F2: capability system configured (non-null resolver) but the agent is unknown (returns null) →
        // fail-closed, not assumed AVAILABLE. The throttle must NOT arm a stall.
        val d = StallDetector(capabilities = { null })
        d.onEvent(throttle("backend", armTs))
        assertTrue(d.sweep(afterThreshold).isEmpty(), "unknown caps → no arm (fail-closed)")
    }

    @Test
    fun stallSuppressedWhenRateLimitSignalUnavailable() {
        val d = StallDetector(capabilities = { caps(CapabilityStatus.UNAVAILABLE) })
        d.onEvent(throttle("backend", armTs))
        assertTrue(d.sweep(afterThreshold).isEmpty(), "no stall when rateLimitSignal unavailable")
    }

    @Test
    fun limitedArmsButMarksEvidenceDegraded() {
        // CYP-122: rateLimitSignal LIMITED → DEGRADED → the detector ARMS (text-matched throttle is still a
        // signal) but marks the suspicion `degraded:true` (less robust than a structured throttle).
        val d = StallDetector(capabilities = { caps(CapabilityStatus.LIMITED) })
        d.onEvent(throttle("backend", armTs))
        val signal = d.sweep(afterThreshold).single()
        assertEquals(StallDetector.SIGNAL_TYPE, signal.type)
        assertEquals(
            true,
            (signal.evidence["degraded"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBoolean(),
            "a DEGRADED-armed stall is marked degraded:true",
        )
    }

    @Test
    fun availableArmIsNotMarkedDegraded() {
        // Control: a structured (AVAILABLE) throttle arms WITHOUT the degraded mark.
        val d = StallDetector(capabilities = { caps(CapabilityStatus.AVAILABLE) })
        d.onEvent(throttle("backend", armTs))
        assertEquals(null, d.sweep(afterThreshold).single().evidence["degraded"])
    }
}
