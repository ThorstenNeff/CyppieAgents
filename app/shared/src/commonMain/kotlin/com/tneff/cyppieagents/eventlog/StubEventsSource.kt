package com.tneff.cyppieagents.eventlog

import com.tneff.cyppieagents.model.Event
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Stand-in [EventLiveSource] for ungated development (no `/ws/events` yet, CYP-40). Emits Connected then
 * a few scripted events (honoring a basic [EventFilter]) so the live-tail path is exercised end-to-end.
 * Replaced by the Ktor `EventsWsClient` adapter — the only follow-up commit; this seam is why.
 */
class StubEventsSource(
    private val scripted: List<Event> = StubEventsApi.sampleEvents(),
    private val stepMillis: Long = 300L,
) : EventLiveSource {

    override fun events(filter: EventFilter): Flow<EventLiveEvent> = flow {
        emit(EventLiveEvent.Connected)
        for (e in scripted) {
            if (matches(e, filter)) {
                delay(stepMillis)
                emit(EventLiveEvent.Received(e))
            }
        }
    }

    private fun matches(e: Event, f: EventFilter): Boolean =
        (f.agentId == null || e.agentId == f.agentId) &&
            (f.type == null || e.type == f.type) &&
            (f.severity == null || e.severity == f.severity) &&
            (f.correlationId == null || e.correlationId == f.correlationId) &&
            (f.sessionId == null || e.sessionId == f.sessionId)
}
