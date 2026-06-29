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
    fun unknownCapsArms_legacyEnabled() {
        val d = StallDetector() // no resolver → enabled (pre-CYP-121 behaviour)
        d.onEvent(throttle("backend", armTs))
        assertEquals(1, d.sweep(afterThreshold).size)
    }

    @Test
    fun stallSuppressedWhenRateLimitSignalUnavailable() {
        val d = StallDetector(capabilities = { caps(CapabilityStatus.UNAVAILABLE) })
        d.onEvent(throttle("backend", armTs))
        assertTrue(d.sweep(afterThreshold).isEmpty(), "no stall when rateLimitSignal unavailable")
    }

    @Test
    fun stallSuppressedWhenRateLimitSignalLimited() {
        // LIMITED is conservatively off in CYP-121 (text-matched degraded path is CYP-122/B work).
        val d = StallDetector(capabilities = { caps(CapabilityStatus.LIMITED) })
        d.onEvent(throttle("backend", armTs))
        assertTrue(d.sweep(afterThreshold).isEmpty())
    }
}
