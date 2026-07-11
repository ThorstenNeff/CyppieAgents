package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.model.ResumeOutcome
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * CYP-355 — a per-runtime, awaitable **resume-outcome signal**. The connector's resume path
 * (`ClaudeCodeConnector.recordResumeOutcome`) pokes it right after it records the CYP-356 `resume.outcome`
 * event; the hand-off motor awaits it so a →ORCHESTRATION hand-back can report `CONTEXT_LOST` vs `MEDIATED`
 * **synchronously** in its `ModeChangeResponse`, instead of only as a later async state delta.
 *
 * **Momentary, no replay** (twin of `CompactCompletionSignal`): the motor arms its await BEFORE it respawns the
 * mediated session (via `scope.async`), so the proactive CYP-330 probe's outcome — which fires shortly after the
 * spawn, without a turn — is never missed. A late await for a past outcome simply times out to null, which the
 * motor reads as "no loss observed in the window → MEDIATED". Since an agent's hand-offs are serialised by the
 * transition lock, there is never a concurrent await for the same agent to confuse.
 */
class ResumeOutcomeSignal {
    // A single shared instance serves every runtime (the connector is shared); the key is (projectId, agentId)
    // so a same-id agent in two projects can never cross wires.
    private val flow = MutableSharedFlow<Pair<String, ResumeOutcome>>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST, // never block the connector's resume path on a slow awaiter
    )

    private fun key(projectId: String, agentId: String) = "$projectId\u0000$agentId"

    /** Non-blocking poke from the connector's resume path (CYP-356), for every outcome (incl. RESUMED_WITH_CONTEXT). */
    fun signal(projectId: String, agentId: String, outcome: ResumeOutcome) {
        flow.tryEmit(key(projectId, agentId) to outcome)
    }

    /** Await the next outcome for the agent up to [boundMs]; null on timeout (no outcome observed in the window). */
    suspend fun await(projectId: String, agentId: String, boundMs: Long): ResumeOutcome? {
        val k = key(projectId, agentId)
        return withTimeoutOrNull(boundMs) { flow.first { it.first == k }.second }
    }
}
