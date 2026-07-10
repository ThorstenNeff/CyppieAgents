package com.tneff.cyppieagents.model

import kotlinx.serialization.Serializable

/**
 * CYP-356 (BE-3) — the outcome of a `--resume` / lifecycle restart, surfaced so the client renders the
 * `CONTEXT_LOST` state (see [TerminalControlState]) **authoritatively** instead of guessing from a near-zero
 * token count — which is ambiguous between an **intended** CYP-326 compaction and an **unintended**
 * memory-loss resume (the very confusion that briefly fooled the CYP-344 analysis).
 *
 * **Surfaces the EXISTING CYP-330 detection only — no new detection.** The source is `ResumingSession`
 * (`awaitStartupOutcome`): `--resume` **bound** ⇒ [RESUMED_WITH_CONTEXT]; **died unbound → healToFresh** ⇒
 * [CONTEXT_LOST]; **no durable sid** (never a `ResumingSession`) ⇒ [FRESH_NO_RESUME].
 */
@Serializable
enum class ResumeOutcome {
    /** `--resume <sid>` bound (`system/init` during the turn) → the session came back WITH its context. */
    RESUMED_WITH_CONTEXT,
    /** `--resume <sid>` died unbound → healed to a fresh session (CYP-330) → prior context was LOST. */
    CONTEXT_LOST,
    /** no durable sid (first start / intentionally cleared) → a fresh session by design (NOT a loss). */
    FRESH_NO_RESUME,
}

/**
 * CYP-356 — the content-free message announcing a resume/restart [outcome] per agent, on EVERY resume/restart
 * (hand-back AND lifecycle restart). **State / identity / time only — never any conversation content.** [sid]
 * is the durable session id involved, or null for [ResumeOutcome.FRESH_NO_RESUME]; [ts] is epoch-ms.
 */
@Serializable
data class AgentResumeOutcomeEvent(
    val agentId: String,
    val outcome: ResumeOutcome,
    val sid: String? = null,
    val ts: Long,
)
