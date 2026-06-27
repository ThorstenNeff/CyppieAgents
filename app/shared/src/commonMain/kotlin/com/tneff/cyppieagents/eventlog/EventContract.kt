package com.tneff.cyppieagents.eventlog

import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity

/**
 * Client-side query model for the Observability Event-Log read UIs (CYP-41/42).
 *
 * The wire DTOs [Event] / [EventType] / [Severity] now live in `:core` (CYP-35, `model/EventModel.kt`)
 * and are imported directly — the placeholder swap is done. [EventFilter] and [Page] stay client-side:
 * the live `EventsApiClient` (CYP-39) builds `GET /api/events?...` query strings from them (REST reads
 * filters from query params, not a JSON body), and the WS `SubscribeEvents(filter)` frame (CYP-40)
 * carries the filter fields. [EventPage] remains a temporary placeholder until CYP-39 lands it in `:core`.
 */

/** Client-side filter → query string for REST, and filter fields in the WS subscribe frame. Window [since, until). */
data class EventFilter(
    val agentId: String? = null,
    val type: EventType? = null,
    val severity: Severity? = null,
    val since: Long? = null,
    val until: Long? = null,
    val correlationId: String? = null,
    val sessionId: String? = null,
)

/** Paging cursor: fetch events with `seq > afterSeq`, up to [limit]. Stable over [Event.seq]. */
data class Page(val afterSeq: Long? = null, val limit: Int = 100)

/** REST page response (→ `:core` wire DTO once CYP-39 lands). */
data class EventPage(val events: List<Event>, val nextAfterSeq: Long?, val hasMore: Boolean)
