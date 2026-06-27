package com.tneff.cyppieagents.eventlog

import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * In-memory [EventsApi] for ungated development + tests (no `/api/events` yet, CYP-39). Holds a fixed
 * event set, applies the [EventFilter] (time window half-open `[since, until)` — the CYP-39 semantic)
 * and the seq-cursor [Page], returning an [EventPage]. Replaced by the Ktor `EventsApiClient` later.
 */
class StubEventsApi(events: List<Event> = sampleEvents()) : EventsApi {

    private val all: List<Event> = events.sortedBy { it.seq }

    override suspend fun query(filter: EventFilter, page: Page): EventPage {
        val matched = all
            .filter { matches(it, filter) }
            .filter { page.afterSeq == null || it.seq > page.afterSeq }
        val window = matched.take(page.limit.coerceAtLeast(1))
        return EventPage(
            events = window,
            nextAfterSeq = window.lastOrNull()?.seq,
            hasMore = matched.size > window.size,
        )
    }

    private fun matches(e: Event, f: EventFilter): Boolean =
        (f.agentId == null || e.agentId == f.agentId) &&
            (f.type == null || e.type == f.type) &&
            (f.severity == null || e.severity == f.severity) &&
            (f.since == null || e.ts >= f.since) &&
            (f.until == null || e.ts < f.until) && // half-open [since, until)
            (f.correlationId == null || e.correlationId == f.correlationId) &&
            (f.sessionId == null || e.sessionId == f.sessionId)

    companion object {
        /** A small, deterministic fixture: one correlated work-run (`run-1`) plus unrelated noise. */
        fun sampleEvents(): List<Event> {
            fun ev(
                seq: Long, type: EventType, agent: String = "backend",
                cid: String? = "run-1", sid: String? = "sess-1",
                sev: Severity = Severity.INFO, detail: JsonObject = JsonObject(emptyMap()),
            ) = Event(
                id = "e$seq", ts = 1_000 + seq, seq = seq, agentId = agent, teamId = "team-1",
                type = type, severity = sev, correlationId = cid, sessionId = sid, detail = detail,
            )
            return listOf(
                ev(1, EventType.TURN_START),
                ev(2, EventType.TOOL_CALL, detail = buildJsonObject { put("toolName", "read_file"); put("toolUseId", "t1") }),
                ev(3, EventType.TOOL_RESULT, detail = buildJsonObject { put("toolUseId", "t1"); put("isError", "false") }),
                ev(4, EventType.CONTEXT_USAGE, detail = buildJsonObject { put("bandPct", "20") }),
                ev(5, EventType.RESULT_FINAL, detail = buildJsonObject { put("subtype", "success"); put("numTurns", "1") }),
                // unrelated noise (different run/agent) — must NOT appear in a run-1 drilldown
                ev(6, EventType.TURN_START, agent = "frontend", cid = "run-2", sid = "sess-2"),
                ev(7, EventType.ERROR_RATELIMIT, agent = "frontend", cid = "run-2", sid = "sess-2", sev = Severity.WARN),
            )
        }
    }
}
