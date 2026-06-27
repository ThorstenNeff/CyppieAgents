package com.tneff.cyppieagents.eventlog

import com.tneff.cyppieagents.model.Event
import kotlinx.coroutines.flow.Flow

/**
 * UI-shaped live event from the event-log socket — the renderer's view, NOT the wire format. The live
 * `/ws/events` adapter (`EventsWsClient`, CYP-40) maps the `:core` `EventsWsServerEvent` frames
 * (`EventPushed`/`CaughtUp`) into these; until then the tail runs against [StubEventsSource]. Keeping
 * this seam means the `/ws/events` swap is the only follow-up commit (mirrors `CommLiveSource`).
 */
sealed interface EventLiveEvent {
    /** The socket is open — the tail may honestly show "live". */
    data object Connected : EventLiveEvent

    /** The socket dropped — the tail must stop claiming "live" (comm CYP-17 §5 honesty). */
    data object Disconnected : EventLiveEvent

    /** A new event pushed by the log; deduped by [Event.id] downstream. */
    data class Received(val event: Event) : EventLiveEvent
}

/** Source of the live event stream, narrowed by [filter] (server-side, operator-only). */
interface EventLiveSource {
    fun events(filter: EventFilter): Flow<EventLiveEvent>
}
