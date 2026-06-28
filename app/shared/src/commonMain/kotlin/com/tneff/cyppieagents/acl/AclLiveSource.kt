package com.tneff.cyppieagents.acl

import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.Channel
import kotlinx.coroutines.flow.Flow

/**
 * UI-shaped live event from the comm hub's ACL stream (CYP-48). Mirrors the comm seam: the real
 * `/ws/comm` source decodes the shared `:core` [com.tneff.cyppieagents.model.AclEvent] (and the
 * membership-affecting `ChannelsEvent`) frames into these. Until wired, the panel runs against
 * [StubAclLiveSource]. Keeping this seam means the live swap is the only follow-up.
 */
sealed interface AclLiveEvent {
    /** The socket is open — the matrix may honestly show "live". */
    data object Connected : AclLiveEvent

    /** The socket dropped — the matrix must stop claiming "live". */
    data object Disconnected : AclLiveEvent

    /** A single (channel, agent) ACL entry changed at the hub — the matrix reconciles by key. */
    data class EntryChanged(val entry: AclEntry) : AclLiveEvent

    /** The channel set / membership changed (e.g. a new spoke) — the matrix axes refresh. */
    data class ChannelsChanged(val channels: List<Channel>) : AclLiveEvent
}

/** Source of the live ACL stream. The Ktor `/ws/comm` adapter ([AclWsClient]) replaces the stub. */
interface AclLiveSource {
    fun events(): Flow<AclLiveEvent>
}
