package com.tneff.cyppieagents.model

import kotlinx.serialization.Serializable

/**
 * CYP-326/329 — compact-orchestration configuration (operator-set, persisted). Operator knobs:
 * the global **"compact allowed"** checkbox, the configurable PO-context **threshold** (default 500K), and
 * (CYP-329) the **tunable timings** [staggerMs]/[roundGapMs]/[roundWindowMs] so the sequence can be sped up
 * without a redeploy. The per-round idle-poll granularity stays fixed platform behaviour (not config).
 *
 * **Timing semantics (CYP-329, decision (c)):** [staggerMs] is the gap between two consecutive messages
 * *within* a round (PO first, then each worker +[staggerMs]); [roundGapMs] is a **fixed pause after the last
 * Round-1 prepare before the first Round-2 `/compact`** (NOT a start-to-start budget — that old formula
 * collapsed to 0 at fast values); [roundWindowMs] is the per-round completion-detection window. With the 2-min
 * defaults the timeline is uniform 2 min between every message (prepare PO=0/…/A_k=2k min → +2 min → compact).
 *
 * Also the PUT body for `POST /api/compact/config` (operator-gated). New timing fields are additive with
 * defaults → no wire break; a legacy persisted config decodes to the 2-min defaults.
 */
@Serializable
data class CompactConfig(
    /** The global "compact allowed" gate — no orchestration runs while false (default OFF, fail-closed). */
    val allowed: Boolean = false,
    /** PO-context token threshold that arms a run; configurable, default 500_000 (the Auftraggeber's 500K). */
    val thresholdTokens: Int = 500_000,
    /** CYP-329 — ms between consecutive messages within a round (default 2 min). Bounded [STAGGER_MIN_MS]..[STAGGER_MAX_MS]. */
    val staggerMs: Long = 120_000,
    /** CYP-329 — fixed pause (ms) after the last Round-1 prepare before Round-2 (default 2 min). Bounded [ROUND_GAP_MIN_MS]..[ROUND_GAP_MAX_MS]. */
    val roundGapMs: Long = 120_000,
    /** CYP-329 — per-round completion-detection window (ms, default 10 min). Bounded [ROUND_WINDOW_MIN_MS]..[ROUND_WINDOW_MAX_MS]. */
    val roundWindowMs: Long = 600_000,
) {
    /**
     * CYP-329 — the SINGLE SOURCE for the timing range bounds (server rejects out-of-range in
     * `POST /api/compact/config`; the client mirrors these exact consts, so validation can't drift —
     * CLAUDE.md "single-source derived values"). Values are the PO-ratified bounds.
     */
    companion object {
        const val STAGGER_MIN_MS = 30_000L        // 30 s
        const val STAGGER_MAX_MS = 600_000L       // 10 min
        const val ROUND_GAP_MIN_MS = 30_000L      // 30 s
        const val ROUND_GAP_MAX_MS = 1_800_000L   // 30 min
        const val ROUND_WINDOW_MIN_MS = 60_000L   // 60 s (floor so completion can be detected)
        const val ROUND_WINDOW_MAX_MS = 1_800_000L // 30 min
    }

    /**
     * CYP-329 — null iff every timing is within its bound; otherwise a human-readable reason. The server
     * rejects (400, fail-closed, no partial apply) and the client disables Save on a non-null result. Only the
     * timings are range-checked here (allowed/thresholdTokens keep their existing semantics).
     */
    fun timingBoundsError(): String? = when {
        staggerMs !in STAGGER_MIN_MS..STAGGER_MAX_MS ->
            "staggerMs $staggerMs out of range $STAGGER_MIN_MS..$STAGGER_MAX_MS"
        roundGapMs !in ROUND_GAP_MIN_MS..ROUND_GAP_MAX_MS ->
            "roundGapMs $roundGapMs out of range $ROUND_GAP_MIN_MS..$ROUND_GAP_MAX_MS"
        roundWindowMs !in ROUND_WINDOW_MIN_MS..ROUND_WINDOW_MAX_MS ->
            "roundWindowMs $roundWindowMs out of range $ROUND_WINDOW_MIN_MS..$ROUND_WINDOW_MAX_MS"
        else -> null
    }
}

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
    /** CYP-329 — the live tunable timings (ms), so the UI shows/edits the current values. Additive w/ defaults. */
    val staggerMs: Long = 120_000,
    val roundGapMs: Long = 120_000,
    val roundWindowMs: Long = 600_000,
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
