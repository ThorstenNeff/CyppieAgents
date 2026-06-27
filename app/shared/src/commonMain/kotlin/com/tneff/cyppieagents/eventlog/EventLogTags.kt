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

    const val TABLE = "eventBrowse.table"

    /** n-th row (0-based, additive-stable render order). */
    fun row(index: Int) = "eventBrowse.row.$index"

    /** n-th row + qualifier (severity `error`/`warn`/`info`/`debug` or `gap`). */
    fun row(index: Int, qualifier: String) = "eventBrowse.row.$index.$qualifier"

    /** id-stable row selector (ULID is punctuation-free → safe). */
    fun rowById(eventId: String) = "eventBrowse.rowById.$eventId"

    const val LOAD_MORE = "eventBrowse.loadMore"
    const val EMPTY = "eventBrowse.empty"

    const val DETAIL = "eventBrowse.detail"
    const val DETAIL_JSON = "eventBrowse.detail.json"
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

    const val EMPTY = "eventTail.empty"
    const val ACCESS_REVOKED = "eventTail.accessRevoked"
}
