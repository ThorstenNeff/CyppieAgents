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

    /** The socket dropped — the matrix must stop claiming "live". Transient → the VM reconnects (CYP-289). */
    data object Disconnected : AclLiveEvent

    /** CYP-289: the server closed `/ws/comm` with 1008 (VIOLATED_POLICY) — a revoked/invalid operator token.
     *  TERMINAL: the matrix must stop claiming "live" AND must NOT reconnect (a revoked token won't return
     *  without re-auth); re-subscribing would re-open `/ws/comm` with the revoked token every backoff period. */
    data object AccessRevoked : AclLiveEvent

    /** A single (channel, agent) ACL entry changed at the hub — the matrix reconciles by key. */
    data class EntryChanged(val entry: AclEntry) : AclLiveEvent

    /** The channel set / membership changed (e.g. a new spoke) — the matrix axes refresh. */
    data class ChannelsChanged(val channels: List<Channel>) : AclLiveEvent
}

/** Source of the live ACL stream. The Ktor `/ws/comm` adapter ([AclWsClient]) replaces the stub. */
interface AclLiveSource {
    fun events(): Flow<AclLiveEvent>
}
