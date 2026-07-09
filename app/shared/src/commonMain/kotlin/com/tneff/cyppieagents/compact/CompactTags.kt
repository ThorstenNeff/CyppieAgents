package com.tneff.cyppieagents.compact

/**
 * CYP-326 — testTag contract for the compact-orchestration window (UIUX spec §1.6). Prefixless, camelCase-
 * after-dot (convention `compact.<element>`, consistent with `settings.*`/`acl.*`/`eventTail.*`). Shared with
 * the tester — an API between Dev and QA, not renamed silently. The window frame tag `window.compact` derives
 * automatically from [com.tneff.cyppieagents.window.WindowTestTags].
 */
object CompactTags {
    const val PANEL = "compact.panel"

    /** The operator's "compact allowed" checkbox (present only for an operator). */
    const val ALLOW_TOGGLE = "compact.allowToggle"

    /** The read-only "compact allowed" status chip shown to a non-operator (honesty: no fake switch). */
    const val ALLOW_CHIP = "compact.allowChip"

    /** The mandatory INFO disclosure hint (always visible — the honest consequence, §3-1). */
    const val ALLOW_HINT = "compact.allowHint"

    /** The GATED "only the operator can change this" hint (non-operator only). */
    const val GATE_HINT = "compact.gateHint"

    /** The read-only threshold fact row. */
    const val THRESHOLD = "compact.threshold"

    /** CYP-327 Feature A: the operator's editable threshold input (present only for an operator; the read-only
     *  [THRESHOLD] row is shown to a non-operator). */
    const val THRESHOLD_INPUT = "compact.thresholdInput"

    /** CYP-327 Feature A: the operator's "set threshold" action (enabled only for a valid, changed value). */
    const val THRESHOLD_SET = "compact.thresholdSet"

    /** CYP-327 Feature A: the inline range-validation error (1..1,000,000) — shown while the draft is invalid. */
    const val THRESHOLD_ERROR = "compact.thresholdError"

    /** CYP-327 Feature A: the transient INFO confirmation after a server-confirmed set ("threshold set to X"). */
    const val THRESHOLD_CONFIRM = "compact.thresholdConfirm"

    // --- CYP-329: operator-tunable timings — Stagger + Round gap editors (minutes UI, ms wire). Each mirrors the
    //     CYP-327 threshold editor: read-only row for a member, input/set/error/confirm for an operator. ---

    /** Read-only stagger row (non-operator). */
    const val STAGGER = "compact.stagger"
    /** Operator stagger input (minutes). */
    const val STAGGER_INPUT = "compact.staggerInput"
    /** Operator "set stagger" action (enabled only for a valid, changed value). */
    const val STAGGER_SET = "compact.staggerSet"
    /** Inline stagger range error (single-sourced `CompactConfig.STAGGER_MIN_MS..STAGGER_MAX_MS`). */
    const val STAGGER_ERROR = "compact.staggerError"
    /** Transient INFO confirmation after a server-confirmed stagger set. */
    const val STAGGER_CONFIRM = "compact.staggerConfirm"

    /** Read-only round-gap row (non-operator). */
    const val ROUND_GAP = "compact.roundGap"
    /** Operator round-gap input (minutes). */
    const val ROUND_GAP_INPUT = "compact.roundGapInput"
    /** Operator "set round gap" action (enabled only for a valid, changed value). */
    const val ROUND_GAP_SET = "compact.roundGapSet"
    /** Inline round-gap range error (single-sourced `CompactConfig.ROUND_GAP_MIN_MS..ROUND_GAP_MAX_MS`). */
    const val ROUND_GAP_ERROR = "compact.roundGapError"
    /** Transient INFO confirmation after a server-confirmed round-gap set. */
    const val ROUND_GAP_CONFIRM = "compact.roundGapConfirm"

    /** The server-mirror status row (liveRegion). Absent when the server state is unknown (§3-3). */
    const val STATUS = "compact.status"

    /** The last-run X/N row. Absent before the first run; WARN-toned on a timeout. */
    const val LAST_RUN = "compact.lastRun"

    // --- CYP-327 Feature B: the per-sequence compact-event list (operator-only; the feed is gated) ---

    /** The dynamic run header ("current run" while running, else "last run"). */
    const val RUN_HEADER = "compact.runHeader"

    /** The event-list container for the current/last run. */
    const val EVENTS = "compact.events"

    /** The honest empty state when the operator has no compact run yet. */
    const val EVENTS_EMPTY = "compact.events.empty"

    /** The N-th compact event row (rendered via the shared `EventRow`). */
    fun eventRow(index: Int): String = "compact.event.$index"
}
