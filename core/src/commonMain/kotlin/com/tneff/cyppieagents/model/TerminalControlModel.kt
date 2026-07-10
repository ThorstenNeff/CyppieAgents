package com.tneff.cyppieagents.model

import kotlinx.serialization.Serializable

/**
 * CYP-354 (BE-1) — the per-agent **terminal-control mode**: which I/O mode a `claude` session is in, as the
 * single source of truth the client **mirrors** (never infers). The CYP-331 hand-off state machine
 * (BE-2/CYP-355 drives the transitions; BE-3/CYP-356 supplies [CONTEXT_LOST] via the resume outcome):
 *
 * ```
 * MEDIATED ──Übernehmen(IDLE-gated)──▶ HANDING_OVER ──BE confirm──▶ INTERACTIVE
 *    ▲                                      │ reject/fail
 *    └──────────────────────────────────────┘  (stay MEDIATED)
 * INTERACTIVE ──Zurückgeben──▶ HANDING_BACK ──resume-result──▶ MEDIATED | CONTEXT_LOST
 * CONTEXT_LOST ──next real turn──▶ MEDIATED   ·   (any lifecycle restart) ──resume-result──▶ MEDIATED | CONTEXT_LOST
 * ```
 *
 * The **default is [MEDIATED]** — an agent with no event is mediated (absent == MEDIATED, exactly as an
 * absent CYP-324 busy event == idle).
 */
@Serializable
enum class TerminalControlState {
    /** stream-json mediated session (the hub is mouth+ears); the default. */
    MEDIATED,
    /** transient: attaching the interactive TUI to the session (mediated stopping). */
    HANDING_OVER,
    /** a human is at the interactive `claude --resume` TUI; the hub is blind. */
    INTERACTIVE,
    /** transient: re-attaching the mediated reader (interactive stopping). */
    HANDING_BACK,
    /** a resume/restart returned WITHOUT prior memory (BE-3 [ResumeOutcome] `CONTEXT_LOST`). */
    CONTEXT_LOST,
}

/**
 * CYP-354 — the single message pushed over `/ws/terminal-state`: the agent's current [TerminalControlState]
 * plus **who** holds an interactive session and **since when**. The twin of [AgentBusyStateEvent] /
 * [AgentTokenUsageEvent]: content-free by construction, a **momentary latest-wins value** (not an append
 * log), snapshot-then-deltas so a reconnect re-sees the current mode and neither duplicates nor loses.
 *
 * **State / identity / time ONLY — NEVER keystrokes or terminal content** (the interactive PTY stream is
 * single-viewer on `/ws/terminal` and is not fanned out here). [heldBy] = the operator id holding the
 * interactive session (set while INTERACTIVE/HANDING_*), else null; [since] = epoch-ms the current holder
 * took over, else null.
 */
@Serializable
data class AgentTerminalControlEvent(
    val agentId: String,
    val state: TerminalControlState,
    val heldBy: String? = null,
    val since: Long? = null,
)
