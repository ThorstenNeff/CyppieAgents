package com.tneff.cyppieagents.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Frame contract for the Event-Log live-tail socket `/ws/events` (PRD §6, ST6/CYP-40), shared via
 * `:core` like [CommWsServerEvent]. Decoded through [com.tneff.cyppieagents.CommJson]
 * (`classDiscriminator = "type"`). Operator-only, fail-closed — the server pushes the team-wide log
 * only to an authorized operator. Idempotency is by [Event.id]/[Event.seq] (client de-dups on reconnect).
 */
@Serializable
sealed interface EventsWsServerEvent

@Serializable
@SerialName("event")
data class EventPushed(val event: Event) : EventsWsServerEvent

/** Optional marker the server may send once the live stream is established (PRD §6 "caughtup"). */
@Serializable
@SerialName("caughtup")
data object CaughtUp : EventsWsServerEvent

/** Client → server frames on `/ws/events`. */
@Serializable
sealed interface EventsWsClientEvent

/**
 * Narrow the live stream to a subset (still operator-only). All fields optional; absent = no narrowing.
 * NOTE: the event-type filter is named [eventType] (not `type`) so it does not collide with the
 * `classDiscriminator = "type"` of the sealed hierarchy.
 */
@Serializable
@SerialName("subscribe")
data class SubscribeEvents(
    val agentId: String? = null,
    val eventType: EventType? = null,
    val severity: Severity? = null,
    val correlationId: String? = null,
    val sessionId: String? = null,
    val since: Long? = null,
    val until: Long? = null,
) : EventsWsClientEvent
