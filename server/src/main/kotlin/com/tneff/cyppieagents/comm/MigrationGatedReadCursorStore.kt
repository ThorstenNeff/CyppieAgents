package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.boot.storeMigrating

/**
 * CYP-705 — the read-only-window gate for [ReadCursorStore] (mirrors [MigrationGatedDeliveryLog]): during a
 * `read-cursor` binding's MIGRATING/READ_ONLY window, [lastReadSeq] reads through to source A, but [markRead]
 * is rejected ([storeMigrating] → 409). Fail-closed is safe for an advance-only cursor: a rejected markRead
 * means the client's `POST …/read` 409s and retries after the switch, so the cursor is never advanced in A and
 * lost after the rebind onto B (unread would only transiently over-report, never a false all-clear).
 */
class MigrationGatedReadCursorStore(private val sourceA: ReadCursorStore) : ReadCursorStore {
    override fun lastReadSeq(projectId: String, subject: String, channelId: String): Long? =
        sourceA.lastReadSeq(projectId, subject, channelId)

    override fun markRead(projectId: String, subject: String, channelId: String, upToSeq: Long): Unit =
        throw storeMigrating("read-cursor")
}
