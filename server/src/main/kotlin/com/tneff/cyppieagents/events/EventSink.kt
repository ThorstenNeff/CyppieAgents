package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventPage
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * The write contract for the Event-Log. Callers (the Mediator/projector, lifecycle hooks, the
 * drop-reporter) supply **only** content-free metadata; `id`/`ts`/`seq` are assigned in the
 * `append` path by the [TimeSource]. There is structurally no way for a caller to forge the total
 * order or backdate an event — the same security-by-structure stance as `SendMessageRequest`
 * carrying no `from` (CYP-9, Gate #1).
 *
 * Metadata-only is the caller's contract here; the projector (CYP-37) is responsible for never
 * lifting `TextBlock.text`/`tool.input`/`tool_result.content`/`Message.body` into [detail] (PRD §3.5).
 */
data class EventDraft(
    val agentId: String,
    /** team = project token (05 §3); MVP is single-team. */
    val teamId: String,
    val type: EventType,
    val severity: Severity,
    val sessionId: String? = null,
    val correlationId: String? = null,
    /** observed source time (hooks) — preserved as `Event.sourceTs`, never order-forming. */
    val sourceTs: Long? = null,
    /** content-free payload; defaults to empty. */
    val detail: JsonObject = JsonObject(emptyMap()),
)

/**
 * Filter over the event spine fields (PRD §6 Browse axes). [matches] is the single in-memory
 * predicate used by the in-memory double and by `subscribe`; the SQLite sink mirrors the same
 * semantics in SQL. Time window is half-open `[since, until)`.
 */
data class EventFilter(
    val agentId: String? = null,
    val type: EventType? = null,
    val severity: Severity? = null,
    val since: Long? = null,
    val until: Long? = null,
    val correlationId: String? = null,
    val sessionId: String? = null,
) {
    fun matches(e: Event): Boolean =
        (agentId == null || e.agentId == agentId) &&
            (type == null || e.type == type) &&
            (severity == null || e.severity == severity) &&
            (since == null || e.ts >= since) &&
            (until == null || e.ts < until) &&
            (correlationId == null || e.correlationId == correlationId) &&
            (sessionId == null || e.sessionId == sessionId)

    companion object {
        val ALL = EventFilter()
    }
}

/** Cursor-based page request. Paging is stable over `seq` (PRD §6): return events with `seq > afterSeq`. */
data class Page(val afterSeq: Long? = null, val limit: Int = 100)

// EventPage is the REST wire DTO (CYP-39) — it lives in :core (com.tneff.cyppieagents.model.EventPage,
// imported above) so the Browse UI consumes the exact same shape.

/**
 * The single ordering + persistence seam (PRD §3.2), swappable behind this narrow interface exactly
 * like `MessageStore` (02 §15) — callers never see SQL. **Stamping happens inside [appendBatch]** so
 * the total order is owned in one place. Implementations must be safe for concurrent callers and
 * must keep `seq` gapless under no-drop conditions.
 *
 * MVP impl: SQLite (WAL, batch). In-memory double for tests. Swappable for Redis Streams / Postgres
 * later without touching callers.
 */
interface EventSink {
    /** Stamp + persist one event durably, returning the stamped [Event]. */
    suspend fun append(draft: EventDraft): Event = appendBatch(listOf(draft)).first()

    /** Stamp + persist a batch in one unit (the writer-coroutine's hot path). Returns stamped events in input order. */
    suspend fun appendBatch(drafts: List<EventDraft>): List<Event>

    /** Browse, paged and ordered by `seq` ascending (PRD §6). */
    suspend fun query(filter: EventFilter, page: Page): EventPage

    /** Live-tail: a hot stream of newly-appended events matching [filter] (PRD §6 / §9 reacting consumers). */
    fun subscribe(filter: EventFilter): Flow<Event>
}
