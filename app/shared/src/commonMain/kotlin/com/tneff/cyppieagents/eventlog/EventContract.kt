package com.tneff.cyppieagents.eventlog

import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity

/**
 * Client-side query model for the Observability Event-Log read UIs (CYP-41/42).
 *
 * The wire DTOs `Event` / `EventType` / `Severity` (CYP-35) and `EventPage` (CYP-39) live in `:core`
 * and are imported directly — the placeholder swap is complete. Only [EventFilter] and [Page] stay
 * client-side: the live `EventsApiClient` builds `GET /api/events?...` query strings from them (REST
 * reads filters from query params, not a JSON body), and the WS `SubscribeEvents(filter)` frame
 * (CYP-40) carries the filter fields.
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

/** Paging cursor: fetch events with `seq > afterSeq`, up to [limit]. Stable over `Event.seq`. */
data class Page(val afterSeq: Long? = null, val limit: Int = 100)
