package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.boot.storeMigrating

/**
 * CYP-220 Phase 6 S6 — the read-only-window gate for [SessionStore] (the S3 migration-freeze, kept universal):
 * during a `session` binding's MIGRATING/READ_ONLY window, [find] passes through to source A, but every write
 * ([upsert]/[clear]) is rejected ([storeMigrating] → 409), so a resume-binding write mid-migration can never be
 * lost in A or duplicated into B.
 */
class MigrationGatedSessionStore(private val sourceA: SessionStore) : SessionStore {
    override fun find(projectId: String, agentId: String): SessionEntry? = sourceA.find(projectId, agentId)
    override fun upsert(projectId: String, agentId: String, sessionId: String, now: Long): Unit = throw storeMigrating("session")
    override fun clear(projectId: String, agentId: String): Unit = throw storeMigrating("session")
}
