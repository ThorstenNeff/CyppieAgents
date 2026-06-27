package com.tneff.cyppieagents.model

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

@Serializable
@SerialName("message")
data class MessageEvent(val message: Message) : CommWsServerEvent

@Serializable
@SerialName("acl")
data class AclEvent(val entry: AclEntry) : CommWsServerEvent

@Serializable
@SerialName("channels")
data class ChannelsEvent(val channels: List<Channel>) : CommWsServerEvent

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
