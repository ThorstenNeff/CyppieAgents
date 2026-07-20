package com.tneff.cyppieagents.model

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Frame contract for the comm live-push socket `/ws/comm` (Spec 02 §8), shared via `:core`.
 * Decoded through [com.tneff.cyppieagents.CommJson] (classDiscriminator = "type").
 *
 * Server → client: new messages, ACL changes, and channel-list updates — already ACL-filtered for
 * the connected participant (the server pushes only what they may read). Idempotency is by
 * [Message.id]: a client de-duplicates re-delivered messages across reconnects.
 */
@Serializable
sealed interface CommWsServerEvent

/**
 * CYP-744 — the /ws/comm live message push now carries the FRONTEND [DeliveredMessage] wrapper: `delivered.message`
 * (the whole stored message, seq intact) + `delivered.mentions` (server-resolved spans). The `/ws/hub` BYOA frame
 * (`WireMessage`) still carries the bare [Message] — the spans never reach the agent wire (§9-frame-guard).
 */
@Serializable
@SerialName("message")
data class MessageEvent(val delivered: DeliveredMessage) : CommWsServerEvent

@Serializable
@SerialName("acl")
data class AclEvent(val entry: AclEntry) : CommWsServerEvent

@Serializable
@SerialName("channels")
data class ChannelsEvent(val channels: List<Channel>) : CommWsServerEvent

/**
 * CYP-705 — a per-`(viewer, channel)` read-state delta for the CONNECTED principal. **Self-only**: the server
 * routes it ONLY to the viewer's own connections (no cross-viewer leak; the subject never reaches the wire, so
 * this event stays content-free — no `identityId`). The client renders [unreadCount] as-is (server-computed,
 * never client arithmetic). Standalone — deliberately NOT folded into [MessageEvent] (CYP-704 is decoupled).
 */
@Serializable
@SerialName("readState")
data class ReadStateEvent(
    val channelId: String,
    val lastReadSeq: Long,
    val unreadCount: Int,
    /** CYP-745 — server-computed "an unread message mentions YOU" for this viewer+channel. Same single filter
     *  pass as [unreadCount] (see [ChannelReadState.hasUnreadMention]), so the two can never disagree:
     *  `true` here implies `unreadCount >= 1`. `@Required` for the same reason as on [ChannelReadState] — an
     *  omissible field would let a PRESENT channel arrive without it, and `undefined → false` is a fabricated
     *  all-clear. Default stays → no call-site changes. Locked by `Cyp745HasUnreadMentionRequiredTest`. */
    @Required val hasUnreadMention: Boolean = false,
) : CommWsServerEvent

/** Client → server frames on `/ws/comm`. */
@Serializable
sealed interface CommWsClientEvent

/**
 * Optionally narrow the live stream to a subset of channels (still ACL-filtered server-side).
 * Default (no Subscribe) = all channels the participant may read.
 */
@Serializable
@SerialName("subscribe")
data class Subscribe(val channelIds: List<String>) : CommWsClientEvent
