package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.boot.storeMigrating

/**
 * CYP-220 Phase 6 S6 — the read-only-window gate for [DeliveryLog] (the S3 migration-freeze, kept universal):
 * during a `delivery` binding's MIGRATING/READ_ONLY window, [isDelivered] reads through to source A, but
 * [markDelivered] is rejected ([storeMigrating] → 409). Fail-closed is safe for a dedup log: a rejected
 * markDelivered means the deliverer does not commit the delivery either (the send is retried after the switch),
 * so a delivery is never recorded in A but lost after the rebind onto B.
 */
class MigrationGatedDeliveryLog(private val sourceA: DeliveryLog) : DeliveryLog {
    override fun isDelivered(projectId: String, agentId: String, messageId: String): Boolean = sourceA.isDelivered(projectId, agentId, messageId)
    override fun markDelivered(projectId: String, agentId: String, messageId: String): Unit = throw storeMigrating("delivery")
}
