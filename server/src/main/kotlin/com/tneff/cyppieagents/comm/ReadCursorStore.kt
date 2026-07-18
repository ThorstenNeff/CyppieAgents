package com.tneff.cyppieagents.comm

import java.util.concurrent.ConcurrentHashMap

/**
 * CYP-705 — durable per-`(principal, channel)` read cursor: the `lastReadSeq` behind unread-per-channel.
 *
 * [subject] is the **ACL read-subject** (agentId / [HubState.OPERATOR_ID] / a human identityId — the same
 * viewer-identity axis as the ACL and CYP-704). Advance-only (monotonic `max`): a lower/late `upToSeq` is a
 * no-op, so an out-of-order mark-read can never un-read. **Absence of a row ⇒ UNKNOWN** (no cursor yet) — the
 * caller distinguishes that from a `0`-unread confirmed-read (UIUX2 §8 ①: absence = unknown, never a false 0).
 * Keyed `(projectId, subject, channelId)`. Mirrors [DeliveryLog]'s form (set-idempotent, dual Sqlite/Pg).
 */
interface ReadCursorStore {
    /** The principal's cursor for the channel, or `null` if never set (UNKNOWN). */
    fun lastReadSeq(projectId: String, subject: String, channelId: String): Long?

    /** Advance the cursor to `max(existing, upToSeq)`. Idempotent; a lower [upToSeq] is a no-op (monotonic). */
    fun markRead(projectId: String, subject: String, channelId: String, upToSeq: Long)
}

/** Stable composite key. subject/channelId/projectId carry no space → unambiguous (mirrors DeliveryLog). */
private fun cursorKey(projectId: String, subject: String, channelId: String): String =
    "$projectId $subject $channelId"

/** In-memory [ReadCursorStore] (tests + the default boot). */
open class InMemoryReadCursorStore : ReadCursorStore {
    protected val cursors: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    override fun lastReadSeq(projectId: String, subject: String, channelId: String): Long? =
        cursors[cursorKey(projectId, subject, channelId)]

    override fun markRead(projectId: String, subject: String, channelId: String, upToSeq: Long) {
        // Advance-only: keep the larger of the existing cursor and the new one (race-safe merge).
        cursors.merge(cursorKey(projectId, subject, channelId), upToSeq, ::maxOf)
    }
}
