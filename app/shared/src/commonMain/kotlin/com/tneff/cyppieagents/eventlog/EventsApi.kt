package com.tneff.cyppieagents.eventlog

import com.tneff.cyppieagents.model.EventPage

/**
 * Read port for the historical event log (Browse, CYP-41). The live `EventsApiClient` (CYP-39) builds
 * `GET /api/events?...` query strings from [EventFilter] + [Page] (operator-token bearer, like
 * `CommRepository`); tests/dev use [StubEventsApi]. Paging is stable over [Event.seq].
 */
interface EventsApi {
    suspend fun query(filter: EventFilter, page: Page): EventPage
}
