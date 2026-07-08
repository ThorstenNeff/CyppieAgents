package com.tneff.cyppieagents.agentview

/**
 * Agent-level overall status (CYP-12 "Ebene B"), derived from the transcript. Honest by design:
 * each value maps to a fact we can actually observe in the event stream.
 *
 * - [WAITING_FOR_INPUT] is intentionally **never derived here** — stream-json has no "waiting for
 *   input" signal, so guessing it after a turn would imply a question that may not exist (a
 *   disclosure violation, CYP-12 §3.2). It is set only by an explicit future block signal
 *   (the escalation seam, 05 §5 — not MVP).
 * - [OFFLINE] is a session/connection fact (WS closed / session stopped), not a transcript fact,
 *   so it is applied by the session layer, not by [deriveStatus].
 */
enum class AgentStatus { RUNNING, IDLE, WAITING_FOR_INPUT, ERROR, OFFLINE }

/**
 * Pure derivation of the agent's overall status from its rendered transcript (CYP-12 §3.3).
 * Keys off the latest event; defaults to [AgentStatus.IDLE] when nothing is actively running
 * (never claims RUNNING without an open stream/tool, never guesses WAITING/OFFLINE).
 */
fun deriveStatus(transcript: List<AgentEvent>): AgentStatus =
    when (val last = transcript.lastOrNull()) {
        null -> AgentStatus.IDLE
        is AgentEvent.AssistantText -> if (!last.complete) AgentStatus.RUNNING else AgentStatus.IDLE
        is AgentEvent.ToolCall -> when (last.status) {
            ToolStatus.RUNNING -> AgentStatus.RUNNING
            ToolStatus.ERROR -> AgentStatus.ERROR
            ToolStatus.OK -> AgentStatus.IDLE
        }
        is AgentEvent.Result -> if (last.isError) AgentStatus.ERROR else AgentStatus.IDLE
        is AgentEvent.Notice -> AgentStatus.IDLE
        // CYP-323: a human turn is not agent activity — status stays IDLE until the agent's first
        // AssistantText/ToolCall flips it to RUNNING (honest: we've observed no agent work yet).
        is AgentEvent.UserTurn -> AgentStatus.IDLE
    }
