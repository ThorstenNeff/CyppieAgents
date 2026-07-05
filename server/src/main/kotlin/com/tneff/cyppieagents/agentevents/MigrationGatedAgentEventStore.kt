package com.tneff.cyppieagents.agentevents

import com.tneff.cyppieagents.boot.storeMigrating
import com.tneff.cyppieagents.model.StoredAgentEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
import kotlinx.coroutines.flow.Flow

/**
 * CYP-220 Phase 6 S5 — the read-only-window gate for [AgentEventStore] (the S3 migration-freeze, kept
 * universal): during a `agent_events` binding's MIGRATING/READ_ONLY window, replay/backfill
 * ([query]/[subscribe]) pass through to source A, but every write ([append]/[deleteByProject]) is rejected
 * ([storeMigrating] → 409), so a transcript event appended (or a project-cascade delete) mid-migration can
 * never be lost in A or duplicated into B.
 */
class MigrationGatedAgentEventStore(private val sourceA: AgentEventStore) : AgentEventStore {
    override suspend fun append(agentId: String, projectId: String, tsMs: Long, event: StreamJsonEvent): StoredAgentEvent =
        throw storeMigrating("agent_events")
    override fun subscribe(agentId: String, sinceSeq: Long?): Flow<StoredAgentEvent> = sourceA.subscribe(agentId, sinceSeq)
    override suspend fun query(agentId: String, sinceSeq: Long?, limit: Int): List<StoredAgentEvent> = sourceA.query(agentId, sinceSeq, limit)
    override suspend fun deleteByProject(projectId: String): Int = throw storeMigrating("agent_events")
}
