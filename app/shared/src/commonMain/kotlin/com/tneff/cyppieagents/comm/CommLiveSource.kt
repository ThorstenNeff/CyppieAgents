package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.Message
import kotlinx.coroutines.flow.Flow

/**
 * UI-shaped live event from the comm hub. This is the renderer's view, NOT the wire format — the
 * real `/ws/comm` source (CYP-18) maps the `:core` WS frame DTOs (`MessageEvent`/`AclEvent`/
 * `ChannelsEvent`, Spec 02 §8 — not in `:core` yet) into these. Until then the panel runs against
 * [StubCommLiveSource]. Keeping this seam means the `/ws/comm` swap is the only follow-up commit.
 */
sealed interface CommLiveEvent {
    /** The socket is open — the timeline may honestly show "live". */
    data object Connected : CommLiveEvent

    /** The socket dropped — the timeline must stop claiming "live" (CYP-17 §5 honesty). */
    data object Disconnected : CommLiveEvent

    /** A new message pushed by the hub; deduped by [Message.id] downstream. */
    data class MessageReceived(val message: Message) : CommLiveEvent

    /** The set of channels the viewer may read changed (ACL/config). */
    data class ChannelsChanged(val channels: List<Channel>) : CommLiveEvent
}

/** Source of the live comm stream. The Ktor `/ws/comm` adapter (CYP-18) replaces the stub here. */
interface CommLiveSource {
    fun events(): Flow<CommLiveEvent>
}
