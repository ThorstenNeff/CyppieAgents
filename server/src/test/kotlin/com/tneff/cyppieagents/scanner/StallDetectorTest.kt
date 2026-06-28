package com.tneff.cyppieagents.scanner

import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The CYP-61 stall detector, proven on every axis the CYP-59 spike + PO mandated. All deterministic:
 * [StallDetector.onEvent] senses, [StallDetector.sweep] decides at an explicit `nowMs` — no wall clock,
 * no injected-event races. The throttle is delivered as an injected `error.ratelimit` event (the spike
 * proved the wire shape; we don't burn real quota to reproduce a 429).
 *
 * Trigger contract (spike, binary-verified): arm ONLY on the **primary** `rate_limit_info.status ∈
 * {blocked, rejected}`; never on `overageStatus`/`allowed*`. Fire only on throttle **∧** silence > T.
 */
class StallDetectorTest {

    private val T = 60_000L
    private fun det() = StallDetector(thresholdMs = T)

    private var seq = 0L
    private fun event(
        type: EventType,
        agent: String = "backend",
        tsMs: Long = 0L,
        detail: JsonObject = JsonObject(emptyMap()),
    ): Event = Event(
        id = "e${seq++}",
        ts = tsMs,
        seq = seq,
        agentId = agent,
        teamId = "default",
        type = type,
        severity = Severity.INFO,
        detail = detail,
    )

    private fun rateLimit(status: String, agent: String = "backend", tsMs: Long = 0L, extra: Map<String, String> = emptyMap()) =
        event(EventType.ERROR_RATELIMIT, agent, tsMs, buildJsonObject {
            put("status", status)
            for ((k, v) in extra) put(k, v)
        })

    // ---- Axis (a): routine status → no signal -------------------------------------------------
    @Test
    fun routineAllowedStatus_neverSignals() {
        for (status in listOf("allowed", "allowed_warning")) {
            val d = det()
            d.onEvent(rateLimit(status, tsMs = 0))
            assertTrue(d.sweep(nowMs = T + 5_000).isEmpty(), "$status must not arm a stall")
        }
    }

    // ---- Axis (b): the overageStatus trap → no signal (the spike's headline finding) ----------
    @Test
    fun healthyAgentWithOverageStatusRejected_neverSignals() {
        val d = det()
        // Exactly the live healthy shape: primary status allowed, sibling overageStatus rejected.
        d.onEvent(rateLimit("allowed", extra = mapOf("overageStatus" to "rejected")))
        assertTrue(
            d.sweep(nowMs = T + 60_000).isEmpty(),
            "overageStatus must never be read as a throttle — only the primary status arms a stall",
        )
    }

    // ---- Axis (c): pure silence, no throttle → no signal ---------------------------------------
    @Test
    fun pureSilenceWithoutThrottle_neverSignals() {
        val d = det()
        // No throttle ever seen; long quiet, even after some earlier activity.
        d.onEvent(event(EventType.TOOL_CALL, tsMs = 0))
        assertTrue(d.sweep(nowMs = 10 * T).isEmpty(), "silence alone (no rate-limit) must not signal")
    }

    // ---- Axis (d): throttle THEN silence > T → exactly one stall.suspected ---------------------
    @Test
    fun throttleThenSilence_signalsOnceWithEvidence() {
        val d = det()
        d.onEvent(rateLimit("blocked", tsMs = 1_000))
        // Before T: nothing.
        assertTrue(d.sweep(nowMs = 1_000 + T - 1).isEmpty(), "must not fire before T elapsed")
        // At/after T: one signal.
        val fired = d.sweep(nowMs = 1_000 + T)
        assertEquals(1, fired.size)
        val s = fired.single()
        assertEquals("stall.suspected", s.type)
        assertEquals("backend", s.agentId)
        assertEquals("blocked", s.evidence["rateLimitStatus"]?.jsonPrimitive?.content)
        assertEquals("1000", s.evidence["rateLimitAtMs"]?.jsonPrimitive?.content)
        // Idempotent: a later sweep on the same armed episode does NOT re-emit.
        assertTrue(d.sweep(nowMs = 1_000 + 5 * T).isEmpty(), "one open suspicion per armed episode")
    }

    @Test
    fun rejectedStatusAlsoArms() {
        val d = det()
        d.onEvent(rateLimit("rejected", tsMs = 0))
        assertEquals(1, d.sweep(nowMs = T).size)
    }

    // ---- Axis (e): throttle THEN activity → no signal (activity disarms) -----------------------
    @Test
    fun throttleThenActivity_neverSignals() {
        for (activity in StallDetector.ACTIVITY_TYPES) {
            val d = det()
            d.onEvent(rateLimit("blocked", tsMs = 0))
            d.onEvent(event(activity, tsMs = 1_000)) // agent resumed work → disarm
            assertTrue(d.sweep(nowMs = 10 * T).isEmpty(), "$activity after throttle must disarm the stall")
        }
    }

    // ---- Repeated throttle beats are not "activity": the silence baseline holds ---------------
    @Test
    fun repeatedThrottleBeats_doNotResetSilenceTimer() {
        val d = det()
        d.onEvent(rateLimit("blocked", tsMs = 0))
        d.onEvent(rateLimit("blocked", tsMs = T - 1)) // a second beat just before T — must NOT reset
        // Still measured from the first marker (t=0), so by t=T it fires.
        assertEquals(1, d.sweep(nowMs = T).size, "a rate-limit beat is not activity; baseline stays at the first throttle")
    }

    // ---- Per-agent isolation: one agent's throttle never signals for another -------------------
    @Test
    fun stallIsPerAgent() {
        val d = det()
        d.onEvent(rateLimit("blocked", agent = "backend", tsMs = 0))
        d.onEvent(event(EventType.TOOL_CALL, agent = "frontend", tsMs = 0)) // frontend is busy, never throttled
        val fired = d.sweep(nowMs = T)
        assertEquals(listOf("backend"), fired.map { it.agentId })
    }

    // ---- metadata-only Needle-Absence on Signal.evidence (Reviewer, like CYP-34 §4) -----------
    @Test
    fun evidenceIsMetadataOnly_noContentNeedle() {
        val d = det()
        val needle = "NEEDLE rm -rf / sk-ant-SECRET tool-input"
        // The throttle event carries content-shaped fields (input/content/result/text) AND the
        // overageStatus trap. The detector must lift NONE of them into evidence — only its whitelist.
        d.onEvent(
            rateLimit(
                "blocked",
                tsMs = 0,
                extra = mapOf(
                    "input" to needle,
                    "content" to needle,
                    "result" to needle,
                    "text" to needle,
                    "thinking" to needle,
                    "overageStatus" to "rejected",
                ),
            ),
        )
        val evidence = d.sweep(nowMs = T).single().evidence
        // (1) exact whitelist — no surprise keys can sneak content in later.
        assertEquals(
            setOf("rateLimitStatus", "rateLimitAtMs", "silenceMs", "thresholdMs"),
            evidence.keys,
        )
        // (2) the needle (and any content field) is absent from the serialized evidence.
        val json = evidence.toString()
        for (n in listOf("NEEDLE", "rm -rf", "sk-ant", "tool-input", "thinking")) {
            assertFalse(json.contains(n), "evidence must not carry content needle '$n'")
        }
        // (3) it carries the real metadata.
        assertEquals("blocked", evidence["rateLimitStatus"]?.jsonPrimitive?.content)
    }
}
