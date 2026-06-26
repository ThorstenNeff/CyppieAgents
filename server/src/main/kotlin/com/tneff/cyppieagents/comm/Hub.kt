package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.routing.ForbiddenException

/**
 * The comm hub: the single place that enforces ACL on write and read, using the pure
 * [com.tneff.cyppieagents.model.AclMatrix] decision from [HubState] (no duplicated logic).
 *
 * Security gates (Reviewer):
 *  - #2 `canWrite` is checked **before** the store write, fail-closed, with an audit record.
 *  - #1 the channel and sender are passed in by the caller from the *authenticated identity* /
 *    URL path — never parsed from message content. [postAsAgent] never inspects the body for routing.
 *  - #3 the body is masked on the way in, so no secret is ever persisted or served.
 */
class Hub(
    val state: HubState,
    private val store: MessageStore,
    private val audit: Audit = Audit(),
    private val clock: Clock = Clock.SYSTEM,
    private val ids: IdGenerator = IdGenerator.UUIDS,
) {
    /** Post [body] from [senderId] into [channelId]. Throws 403 if the sender may not write. */
    fun postAsAgent(senderId: String, channelId: String, body: String, meta: MessageMeta? = null): Message {
        // Gate #2: fail-closed BEFORE any write.
        if (!state.acl.canWrite(channelId, senderId)) {
            audit.denied(senderId, channelId, "canWrite=false")
            throw ForbiddenException("agent '$senderId' has no write access to channel '$channelId'")
        }
        val maskedBody = SecretMasker.mask(body) // Gate #3 egress
        val message = Message(
            id = ids.newId(),
            channelId = channelId,
            from = senderId,
            body = maskedBody,
            ts = clock.now(),
            meta = meta,
        )
        store.append(message)
        audit.posted(message)
        return message
    }

    /** Messages of one channel for [readerId]; throws 403 if the reader may not read it. */
    fun channelMessages(readerId: String, channelId: String, since: Long? = null): List<Message> {
        if (!state.acl.canRead(channelId, readerId)) {
            throw ForbiddenException("agent '$readerId' has no read access to channel '$channelId'")
        }
        return store.byChannel(channelId, since)
    }

    /** Aggregated inbox across all channels [readerId] may read (Spec 02 §6.3, ACL-filtered). */
    fun inbox(readerId: String, since: Long? = null): List<Message> {
        val readable = state.acl.readableChannels(readerId).map { it.id }
        val candidate = store.acrossChannels(readable, since)
        // Defense in depth: re-filter through the same decision even though channels are pre-filtered.
        return state.acl.visibleMessages(readerId, candidate)
    }

    /** Channels [readerId] may read (for GET /api/channels). */
    fun readableChannels(readerId: String): List<Channel> = state.acl.readableChannels(readerId)
}
