package com.tneff.cyppieagents.eventlog

/**
 * `testTag` contracts for the Event-Log read UIs — the **Dev/QA API** from `docs/design/event-log-tags.md`
 * (Test-Contract v0.5 §2, prefixless `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`).
 * Single source of truth shared with the tester (CYP-7) — do not silently rename. Two single-instance
 * areas: `eventBrowse` (CYP-41) and `eventTail` (CYP-42). Vorbild: `comm/CommTags`, `agentview/AgentViewTags`.
 */
object EventBrowseTags {
    const val FILTER_BAR = "eventBrowse.filterBar"
    const val FILTER_AGENT = "eventBrowse.filter.agent"
    const val FILTER_TYPE = "eventBrowse.filter.type"
    const val FILTER_SEVERITY = "eventBrowse.filter.severity"
    const val FILTER_TIME_WINDOW = "eventBrowse.filter.timeWindow"
    const val FILTER_CORRELATION = "eventBrowse.filter.correlation"
    const val FILTER_ACTIVE = "eventBrowse.filterActive"
    // CYP-94 cross-project additions.
    const val FILTER_PROJECT = "eventBrowse.filter.project"
    const val CROSS_PROJECT_VIEW = "eventBrowse.crossProjectView"

    const val TABLE = "eventBrowse.table"

    /** CYP-288 — load-error surface (shown INSTEAD of EMPTY when a first-page load failed) + its retry button. */
    const val ERROR = "eventBrowse.error"
    const val ERROR_RETRY = "eventBrowse.error.retry"

    /** n-th row (0-based, additive-stable render order). */
    fun row(index: Int) = "eventBrowse.row.$index"

    /** n-th row + qualifier (severity `error`/`warn`/`info`/`debug` or `gap`). */
    fun row(index: Int, qualifier: String) = "eventBrowse.row.$index.$qualifier"

    /** n-th row's project identity (CYP-94) — rendered ONLY in the cross-project view. */
    fun rowProject(index: Int) = "eventBrowse.row.$index.project"

    /** id-stable row selector (ULID is punctuation-free → safe). */
    fun rowById(eventId: String) = "eventBrowse.rowById.$eventId"

    const val LOAD_MORE = "eventBrowse.loadMore"
    const val EMPTY = "eventBrowse.empty"

    const val DETAIL = "eventBrowse.detail"
    const val DETAIL_JSON = "eventBrowse.detail.json"
    /** CYP-326 §2.5: the localized X/N summary for a `compact.orchestration.done` event (above the raw JSON). */
    const val DETAIL_COMPACT_SUMMARY = "eventBrowse.detail.compactSummary"
    /** CYP-381 (CYP-356): the 3-stage ResumeOutcome summary for a `resume.outcome` event (CONTEXT_LOST = WARN). */
    const val DETAIL_RESUME_OUTCOME = "eventBrowse.detail.resumeOutcome"
    const val DETAIL_SOURCE_TS = "eventBrowse.detail.sourceTs"
    const val DETAIL_SHOW_RUN = "eventBrowse.detail.showRun"
    const val DETAIL_SHOW_SESSION = "eventBrowse.detail.showSession"
    const val BACK = "eventBrowse.back"

    const val DRILLDOWN = "eventBrowse.drilldown"
    const val DRILLDOWN_HEADER = "eventBrowse.drilldown.header"
    fun drilldownRow(index: Int) = "eventBrowse.drilldown.row.$index"

    const val ACCESS_REVOKED = "eventBrowse.accessRevoked"
}

object EventTailTags {
    const val STREAM = "eventTail.stream"

    fun row(index: Int) = "eventTail.row.$index"
    fun row(index: Int, qualifier: String) = "eventTail.row.$index.$qualifier"
    fun rowById(eventId: String) = "eventTail.rowById.$eventId"

    const val PAUSE_TOGGLE = "eventTail.pauseToggle"
    const val LIVE_INDICATOR = "eventTail.liveIndicator"
    const val PAUSED_INDICATOR = "eventTail.pausedIndicator"
    const val BUFFERED_COUNT = "eventTail.bufferedCount"
    const val BUFFER_OVERFLOW = "eventTail.bufferOverflow"
    const val TRIMMED = "eventTail.trimmed"
    const val CONNECTION = "eventTail.connection"

    const val FILTER_AGENT = "eventTail.filter.agent"
    const val FILTER_TYPE = "eventTail.filter.type"
    const val FILTER_SEVERITY = "eventTail.filter.severity"
    // CYP-94 cross-project additions.
    const val FILTER_PROJECT = "eventTail.filter.project"
    const val CROSS_PROJECT_VIEW = "eventTail.crossProjectView"

    /** n-th row's project identity (CYP-94) — rendered ONLY in the cross-project view. */
    fun rowProject(index: Int) = "eventTail.row.$index.project"

    const val EMPTY = "eventTail.empty"
    const val ACCESS_REVOKED = "eventTail.accessRevoked"
}
