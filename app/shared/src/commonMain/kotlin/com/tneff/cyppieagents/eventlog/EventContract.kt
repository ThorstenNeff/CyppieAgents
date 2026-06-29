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
    /**
     * CYP-94 cross-project read lens (operator-only). `null` = no param → the server forces the active
     * project (CYP-102, unchanged); a concrete project id = that other project; [PROJECT_ALL] = all of the
     * operator's authorized projects. Sent as the `projectId` query param **only when non-null** (the
     * operator-gated path). This is the CLIENT [EventFilter] (app:shared) — distinct from the server's
     * forced-active `EventFilter.projectId`; the operator-override is validated server-side (Backend seam).
     */
    val projectId: String? = null,
) {
    companion object {
        /** Sentinel `projectId` value = cross-project (all of the operator's own authorized projects). */
        const val PROJECT_ALL = "all"
    }
}

/** Paging cursor: fetch events with `seq > afterSeq`, up to [limit]. Stable over `Event.seq`. */
data class Page(val afterSeq: Long? = null, val limit: Int = 100)
