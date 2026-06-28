package com.tneff.cyppieagents.warden

import com.tneff.cyppieagents.scanner.Signal
import com.tneff.cyppieagents.scanner.SignalSink
import com.tneff.cyppieagents.scanner.StallDetector
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The first concrete [Policy] (07 §4, CYP-63): the **touchy state** behind a stall. It carries the
 * incident lifecycle per agent — backoff timer, attempt counter — and acts only through the
 * [Actuator] (nudge / escalate) and the [SignalSink] (recovery telemetry). It is driven by a
 * [StallPolicyRunner]: [onSignal] opens incidents, [tick] drives backoff, [onActivity] detects recovery.
 *
 * Lifecycle of one incident:
 *  - `stall.suspected` → open an incident (**idempotent: one open incident per agent** — a second
 *    suspicion while one is open is ignored, so there is never a parallel nudge cycle).
 *  - each [tick]: once the current backoff has elapsed, send the next nudge ([Actuator.nudge] →
 *    `nudge.sent`). Backoff **grows** (30/60/120/240 s) — the Warden owns the curve; there is no
 *    server `retry_after` (CYP-59). Fast re-nudging would only deepen the rate-limit.
 *  - after **N = backoff.size** nudges with no recovery → [Actuator.escalateToPO] (`stall.escalated`,
 *    error) and close the incident, instead of nudging forever.
 *  - any real agent activity (turn/tool/token/result) while an incident is open → `stall.recovered`
 *    (info) and close it ([onActivity]).
 *
 * Decisions are computed under a lock; the suspending [Actuator] calls run **outside** the lock so a
 * slow `sendTurn` can't block recovery/suspicion handling (a nudge that races a just-arrived recovery
 * is at worst one extra "keep going", harmless).
 */
class StallPolicy(
    private val clock: () -> Long,
    private val actuator: Actuator,
    private val signals: SignalSink,
    private val backoffMs: List<Long> = DEFAULT_BACKOFF_MS,
    private val nudgeText: String = DEFAULT_NUDGE,
) : Policy {

    private class Incident(
        val teamId: String,
        val correlationId: String?,
        var attempts: Int,
        var lastActionMs: Long,
    )

    /** What a [tick] decided to do for one agent, performed after the lock is released. */
    private class Action(val agentId: String, val escalate: Boolean, val reason: String?)

    private val lock = Any()
    private val incidents = HashMap<String, Incident>() // agentId → incident; the map key enforces one/agent

    override fun handles(signalType: String): Boolean = signalType == StallDetector.SIGNAL_TYPE

    /** Open an incident on `stall.suspected` — idempotent: a second suspicion for an open agent is ignored. */
    override suspend fun onSignal(signal: Signal, act: Actuator) {
        synchronized(lock) {
            if (incidents.containsKey(signal.agentId)) return // one open incident per agent
            incidents[signal.agentId] = Incident(
                teamId = signal.teamId,
                correlationId = signal.correlationId,
                attempts = 0,
                // Backoff is measured from here, so the FIRST nudge waits backoffMs[0] (no instant fire).
                lastActionMs = clock(),
            )
        }
    }

    /** Recovery: real activity for an agent with an open incident closes it and emits `stall.recovered`. */
    suspend fun onActivity(agentId: String) {
        val closed = synchronized(lock) { incidents.remove(agentId) } ?: return
        signals.emit(
            Signal(
                type = RECOVERED,
                agentId = agentId,
                teamId = closed.teamId,
                correlationId = closed.correlationId,
                evidence = buildJsonObject { put("nudges", closed.attempts) },
            ),
        )
    }

    /** Drive backoff/escalation for every open incident at the current [clock] time. */
    suspend fun tick() {
        val now = clock()
        val todo = ArrayList<Action>()
        synchronized(lock) {
            val iter = incidents.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                val inc = entry.value
                val dueMs = backoffMs[minOf(inc.attempts, backoffMs.size - 1)]
                if (now - inc.lastActionMs < dueMs) continue // backoff not elapsed → wait (no Dauerfeuer)
                if (inc.attempts < backoffMs.size) {
                    todo.add(Action(entry.key, escalate = false, reason = null))
                    inc.attempts++
                    inc.lastActionMs = now
                } else {
                    todo.add(Action(entry.key, escalate = true, reason = "no recovery after ${inc.attempts} nudges"))
                    iter.remove() // escalated → incident closed
                }
            }
        }
        for (a in todo) {
            if (a.escalate) actuator.escalateToPO(a.agentId, a.reason!!, ESCALATED) // → stall.escalated (error)
            else actuator.nudge(a.agentId, nudgeText) // → nudge.sent
        }
    }

    companion object {
        const val RECOVERED = "stall.recovered"
        const val ESCALATED = "stall.escalated"

        /** Growing backoff before each nudge (07 §5/§7, spike). Its size is the attempt limit N. */
        val DEFAULT_BACKOFF_MS = listOf(30_000L, 60_000L, 120_000L, 240_000L)

        /** The defined "keep going" nudge (CYP-59: `"Mach weiter."` proven to unstick a session live). */
        const val DEFAULT_NUDGE = "Bitte mach weiter mit der laufenden Aufgabe."
    }
}
