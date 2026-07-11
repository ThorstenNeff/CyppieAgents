package com.tneff.cyppieagents.model

import kotlinx.serialization.Serializable

/**
 * CYP-355 (BE-2) — the **hand-off motor** contract: the client's request to move an agent between the two
 * stable terminal-control poles, and the motor's **non-optimistic** answer. Shares CYP-354's
 * [AgentTerminalControlEvent] verbatim in the response so the REST confirm and the `/ws/terminal-state`
 * delta are the **same shape**, compiler-guaranteed (the CYP-356 sharing pattern).
 *
 * Trigger seam: `POST /api/agents/{id}/mode` with a [ModeChangeRequest] body (operator-gated — the
 * control plane lives on REST, never on `/ws/terminal`, which stays a single-viewer PTY-I/O stream).
 * The POST is **synchronous through the whole transition** (IDLE-defer + spawn + confirm), so its
 * [ModeChangeResponse] reports the SETTLED outcome, never an optimistic flip. During the wait the
 * transient [TerminalControlState.HANDING_OVER] / [TerminalControlState.HANDING_BACK] is observable on
 * `/ws/terminal-state`.
 */

/**
 * The two **stable** poles a hand-off can target. Deliberately NOT [TerminalControlState] — the five-state
 * control enum includes the transient HANDING_OVER/HANDING_BACK/CONTEXT_LOST, which are *observed* states,
 * never a valid *request* target. A request only ever asks for one of the two resting modes.
 */
@Serializable
enum class TerminalMode {
    /** Hand the session back to the stream-json mediated reader (the hub is mouth+ears). → [TerminalControlState.MEDIATED]. */
    ORCHESTRATION,
    /** Attach the interactive `claude --resume` TUI for a human (the hub is blind). → [TerminalControlState.INTERACTIVE]. */
    TERMINAL,
}

/** The request body of `POST /api/agents/{id}/mode`. The agent id rides the path; nothing else is needed. */
@Serializable
data class ModeChangeRequest(val target: TerminalMode)

/**
 * Did the hand-off settle successfully? The **non-optimistic-flip signal** — the client flips its view ONLY
 * on [CONFIRMED]. A hand-back that returns without prior memory is still [CONFIRMED] (the mode *did* move to
 * mediated) with `control.state == CONTEXT_LOST`; the memory loss is orthogonal and already surfaced by BE-3
 * ([ResumeOutcome]). A [REJECTED] leaves the agent in its prior mode unchanged, with a [ModeChangeRejection].
 */
@Serializable
enum class ModeChangeOutcome { CONFIRMED, REJECTED }

/** Why a hand-off was rejected (the prior mode is preserved). Populated iff [ModeChangeOutcome.REJECTED]. */
@Serializable
enum class ModeChangeRejection {
    /** The agent stayed busy past the operator-configured IDLE-defer bound (no silent turn-hijack). */
    BUSY_TIMEOUT,
    /** The target session failed to come up (PTY `--resume` died at the liveness-settle, or the mediated resume never bound). */
    SPAWN_FAILED,
    /** The agent is already in the requested mode — a no-op. */
    ALREADY_IN_TARGET,
    /** Another hand-off for this agent is already in flight — the single-writer rejects the concurrent request. */
    IN_TRANSITION,
}

/**
 * The settled answer of `POST /api/agents/{id}/mode`.
 *
 * @property outcome the non-optimistic flip signal — the client acts only on [ModeChangeOutcome.CONFIRMED].
 * @property control the resulting control state — **the same [AgentTerminalControlEvent] shape pushed on
 *   `/ws/terminal-state`** (CYP-354), so `state` / `heldBy` / `since` carry their established semantics and
 *   the REST reader and the WS subscriber never disagree. On [ModeChangeOutcome.REJECTED] this reflects the
 *   UNCHANGED prior mode.
 * @property reason the rejection cause, present iff [outcome] == [ModeChangeOutcome.REJECTED].
 */
@Serializable
data class ModeChangeResponse(
    val outcome: ModeChangeOutcome,
    val control: AgentTerminalControlEvent,
    val reason: ModeChangeRejection? = null,
)
