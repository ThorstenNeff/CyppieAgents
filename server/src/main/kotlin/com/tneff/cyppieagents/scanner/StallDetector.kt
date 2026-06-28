package com.tneff.cyppieagents.scanner

import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The first concrete [Detector] (07 §4, CYP-61): `stall.suspected` = **a rate-limit throttle seen
 * *and then* silence** for a threshold T. The coupling to the throttle is what distinguishes
 * "gedrosselt-hängend" from "arbeitet/denkt gerade": a long legitimate tool-run produces no throttle
 * marker, so it can never trip this detector.
 *
 * **Trigger field (de-risked in the CYP-59 spike, from the shipped CLI 2.1.193, not memory):** only
 * the **primary** `rate_limit_info.status ∈ {blocked, rejected}` arms a stall. `allowed`/
 * `allowed_warning` are routine spend beats. The sibling **`overageStatus` is NOT a throttle** — it is
 * live `"rejected"` even on a perfectly healthy agent (overage-billing availability) — so it is never
 * read here. Keying on it would false-positive 100% of the time.
 *
 * **Why a sweep, not a synchronous return:** a stall is the *absence* of events over time, which the
 * event-driven [Detector.onEvent] seam cannot express. So [onEvent] only maintains per-agent state
 * (arm on a throttle, disarm on activity) and always returns `null`; the timed decision is [sweep],
 * driven periodically by a [StallSweeper] (prod) or called directly with a controlled clock (tests).
 * Both are deterministic — no wall-clock in the unit tests.
 *
 * Idempotency at the detection level: once a stall is suspected for an armed episode it is not
 * re-emitted every sweep; only fresh activity (which disarms) and a new throttle re-arm it. The full
 * incident lifecycle (nudge/backoff/recovery/escalation) is the Warden's (CYP-62/63), not here.
 */
class StallDetector(
    private val thresholdMs: Long = DEFAULT_THRESHOLD_MS,
) : Detector {

    private class Armed(
        val sinceMs: Long,
        val status: String,
        val projectId: String,
        val correlationId: String?,
        var suspected: Boolean,
    )

    private val lock = Any()
    private val byAgent = HashMap<String, Armed>()

    /**
     * Sense only — never emits synchronously. A throttle marker arms the agent (silence is measured
     * from the marker); any real activity disarms it (throttle *then work* = not stalled). All other
     * events (lifecycle, `log.dropped`, the `stall.*` signals themselves) are ignored, so the detector
     * never reacts to its own output (07 §3 loop-avoidance).
     */
    override fun onEvent(e: Event): Signal? {
        synchronized(lock) {
            when {
                isThrottle(e) -> {
                    // Arm once; a repeated throttle beat is NOT activity and must not reset the silence
                    // baseline (07 §4: silence = no turn.*/tool.*/tokens — a rate_limit beat is none).
                    if (byAgent[e.agentId] == null) {
                        byAgent[e.agentId] = Armed(
                            sinceMs = e.ts,
                            status = throttleStatus(e)!!,
                            projectId = e.projectId,
                            correlationId = e.correlationId,
                            suspected = false,
                        )
                    }
                }
                isActivity(e) -> byAgent.remove(e.agentId) // throttle followed by work → disarm
            }
        }
        return null
    }

    /**
     * The timed decision: emit `stall.suspected` for every agent that has been armed and silent for at
     * least [thresholdMs] and not already suspected. Returns the signals to emit (the caller forwards
     * them to the bus). Pure given [nowMs] + current state.
     */
    fun sweep(nowMs: Long): List<Signal> = synchronized(lock) {
        val out = ArrayList<Signal>()
        for ((agentId, a) in byAgent) {
            if (a.suspected) continue
            val silenceMs = nowMs - a.sinceMs
            if (silenceMs < thresholdMs) continue
            a.suspected = true
            out += Signal(
                type = SIGNAL_TYPE,
                agentId = agentId,
                projectId = a.projectId,
                correlationId = a.correlationId,
                evidence = buildJsonObject {
                    put("rateLimitStatus", a.status)   // blocked | rejected
                    put("rateLimitAtMs", a.sinceMs)    // when the throttle armed the stall
                    put("silenceMs", silenceMs)
                    put("thresholdMs", thresholdMs)
                },
            )
        }
        out
    }

    private fun isThrottle(e: Event): Boolean =
        e.type == EventType.ERROR_RATELIMIT && throttleStatus(e) != null

    /** The PRIMARY status, only when it is a throttle value. Never reads `overageStatus`. */
    private fun throttleStatus(e: Event): String? {
        val status = (e.detail["status"] as? JsonPrimitive)?.content ?: return null
        return status.takeIf { it in THROTTLE_STATUSES }
    }

    private fun isActivity(e: Event): Boolean = e.type in ACTIVITY_TYPES

    companion object {
        const val SIGNAL_TYPE = "stall.suspected"

        /** Spike default (07 §5/§7): coupled to the throttle marker, so 60s is responsive yet calm. */
        const val DEFAULT_THRESHOLD_MS = 60_000L

        /**
         * Primary `rate_limit_info.status` values that mean throttle-hang (CYP-59, binary-verified).
         * The full status enum is `{allowed, allowed_warning, blocked, rejected}` — **identical in CLI
         * 2.1.193 (connector pin) and 2.1.195 (installed), confirmed by binary diff**, so the
         * discriminator is stable across the pin drift (Reviewer pre-merge check). Never reads the
         * sibling `overageStatus`, which is live `"rejected"` even on a healthy agent.
         */
        val THROTTLE_STATUSES = setOf("blocked", "rejected")

        /** Real agent progress (07 §4: turn / tool / token events) — any of these disarms a pending stall. */
        val ACTIVITY_TYPES = setOf(
            EventType.TURN_START,
            EventType.TURN_END,
            EventType.TOOL_CALL,
            EventType.TOOL_RESULT,
            EventType.RESULT_FINAL,
            EventType.CONTEXT_USAGE,
        )
    }
}
