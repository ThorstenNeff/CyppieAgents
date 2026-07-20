package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.db.BindingReason

import com.tneff.cyppieagents.boot.storeMigrating
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventPage
import kotlinx.coroutines.flow.Flow

/**
 * CYP-220 Phase 6 S5 — the read-only-window gate for [EventSink] (the S3 migration-freeze, kept universal):
 * during a `event_log` binding's MIGRATING/READ_ONLY window, browse/tail ([query]/[subscribe]) pass through to
 * source A, but every write ([append]/[appendBatch]/[deleteByProject]) is rejected ([storeMigrating] → 409), so
 * an event appended (or a project-cascade delete) mid-migration can never be lost in A or duplicated into B.
 */
class MigrationGatedEventSink(private val sourceA: EventSink, private val reason: BindingReason) : EventSink {
    override suspend fun append(draft: EventDraft): Event = throw storeMigrating("event_log", reason)
    override suspend fun appendBatch(drafts: List<EventDraft>): List<Event> = throw storeMigrating("event_log", reason)
    override suspend fun query(filter: EventFilter, page: Page): EventPage = sourceA.query(filter, page)
    override fun subscribe(filter: EventFilter): Flow<Event> = sourceA.subscribe(filter)
    override suspend fun deleteByProject(projectId: String): Int = throw storeMigrating("event_log", reason)
}
