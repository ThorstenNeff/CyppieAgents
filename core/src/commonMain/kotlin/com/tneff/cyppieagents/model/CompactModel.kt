package com.tneff.cyppieagents.model

import kotlinx.serialization.Serializable

/**
 * CYP-326 — compact-orchestration configuration (operator-set, persisted). The two knobs the UI exposes:
 * the global **"compact allowed"** checkbox and the configurable PO-context **threshold** (default 500K).
 * The staggered timing (+10 min between rounds, 1 min per agent) and the 10-min per-round timeout are fixed
 * platform behaviour, not config.
 *
 * Also the PUT body for `POST /api/compact/config` (operator-gated).
 */
@Serializable
data class CompactConfig(
    /** The global "compact allowed" gate — no orchestration runs while false (default OFF, fail-closed). */
    val allowed: Boolean = false,
    /** PO-context token threshold that arms a run; configurable, default 500_000 (the Auftraggeber's 500K). */
    val thresholdTokens: Int = 500_000,
)

/**
 * CYP-326 — the live orchestration status (`GET /api/compact/status`, read-tier). Content-free: only the
 * config + run bookkeeping (agentIds / counts / timestamps), never any agent output.
 */
@Serializable
data class CompactStatus(
    val allowed: Boolean,
    val thresholdTokens: Int,
    /** Threshold-watch is armed (PO context below threshold → a future up-crossing will fire). */
    val armed: Boolean,
    /** An orchestration run is currently in progress. */
    val running: Boolean,
    /** The most recent run's honest X/N outcome (null before the first run). */
    val lastRun: CompactRunSummary? = null,
)

/**
 * CYP-326 — one orchestration run's outcome. **Honest X-of-N**: [completed] agents saw a `compact_result:
 * success` within their round's 10-min window; [pendingAgentIds] did NOT (timeout → WARN, never faked as done).
 */
@Serializable
data class CompactRunSummary(
    /** X — agents that completed a compaction. */
    val completed: Int,
    /** N — agents the run targeted. */
    val total: Int,
    /** Agents that did NOT complete within their window (the WARN set); empty on a full N/N run. */
    val pendingAgentIds: List<String> = emptyList(),
    val startedTs: Long,
    /** null while the run is still in progress. */
    val finishedTs: Long? = null,
    /**
     * CYP-327 — the run's `correlationId`: the AUTHORITATIVE join key for the per-sequence compact-event list.
     * The orchestration stamps this same value onto every event of the run (`compact.prepare.sent` /
     * `compact.request.sent` / `compact.completed` / `compact.orchestration.done`), so the UI filters the event
     * list on `status.lastRun.correlationId` instead of a client heuristic (which could show the wrong run).
     * Nullable/additive: null on a legacy/unstamped summary.
     */
    val correlationId: String? = null,
    /**
     * CYP-326 kill-switch: true when the run was ABORTED mid-flight (operator turned "compact allowed" off).
     * [completed]/[pendingAgentIds] are the honest state at the abort — never re-labelled "all done". Already-sent
     * `/compact` commands are not retractable, so a compacted agent still counts in [completed].
     */
    val aborted: Boolean = false,
)
