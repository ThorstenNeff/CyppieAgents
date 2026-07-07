package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.Message

/** A timeline entry: a hub [message] plus its local delivery state. */
data class MessageItem(
    val message: Message,
    /** True while an optimistic send is unconfirmed (no server-assigned id yet) — shown as "sending". */
    val pending: Boolean = false,
)

/** Honest connection state driving the timeline's live/disconnected banner (CYP-17 §5). */
enum class ConnectionStatus { CONNECTING, LIVE, DISCONNECTED }

/**
 * Pure timeline reducers. Identity is [Message.id] only — never `(from, ts, body)` (CYP-17 §6);
 * ordering is `ts` then `id` as a stable tiebreaker. Reconnect replays merge idempotently, so a
 * history catch-up after a drop produces no duplicates and no gap.
 */
object CommReducer {

    private val ORDER: Comparator<MessageItem> =
        compareBy<MessageItem> { it.message.ts }.thenBy { it.message.id }

    /** Inserts or updates [message] by id, keeping the list ordered. Idempotent. */
    fun merge(items: List<MessageItem>, message: Message, pending: Boolean = false): List<MessageItem> {
        val withoutDup = items.filterNot { it.message.id == message.id }
        return (withoutDup + MessageItem(message, pending)).sortedWith(ORDER)
    }

    /** Merges a batch (REST history load or reconnect catch-up). */
    fun mergeAll(items: List<MessageItem>, messages: List<Message>): List<MessageItem> =
        messages.fold(items) { acc, m -> merge(acc, m) }

    /** Adds an optimistic, unconfirmed message (temporary client id) marked as pending. */
    fun addOptimistic(items: List<MessageItem>, optimistic: Message): List<MessageItem> =
        merge(items, optimistic, pending = true)

    /** Confirms an optimistic send: drops the temp [tempId] entry and inserts the server [confirmed]. */
    fun confirm(items: List<MessageItem>, tempId: String, confirmed: Message): List<MessageItem> =
        merge(items.filterNot { it.message.id == tempId }, confirmed, pending = false)

    /** Maps a live event to the connection status it implies, or null if it carries no status. */
    fun statusOf(event: CommLiveEvent): ConnectionStatus? = when (event) {
        is CommLiveEvent.Connected -> ConnectionStatus.LIVE
        is CommLiveEvent.Disconnected -> ConnectionStatus.DISCONNECTED
        is CommLiveEvent.AccessRevoked -> ConnectionStatus.DISCONNECTED // CYP-291: honest terminal (never "live")
        else -> null
    }
}
