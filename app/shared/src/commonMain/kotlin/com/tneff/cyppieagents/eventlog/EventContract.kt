package com.tneff.cyppieagents.eventlog

/**
 * TEMPORARY placeholder contract for the Observability Event-Log (Epic CYP-34), so the read-only data
 * layer (reducer, view-models, stubs, tests) can be built **stub-first** before the canonical types land.
 *
 * Ownership / swap plan (PO-reconciled with backend, 2026-06-27):
 *  - [Event] / [Severity] / [EventType]  → move to `:core` as `@Serializable` wire DTOs (CYP-35, ST1);
 *    `EventType` carries `@SerialName` wire strings, decodes unknown → [EventType.UNKNOWN] tolerantly,
 *    and includes `log.dropped` as a real member.
 *  - [EventPage]                          → `:core` wire DTO (CYP-39, ST5).
 *  - [EventFilter] / [Page]               → client-side query model (NOT a REST JSON body): the live
 *    `EventsApiClient` builds `GET /api/events?...` query strings from these (CYP-39); the WS
 *    `SubscribeEvents(filter)` frame carries the filter fields (CYP-40, ST6).
 *
 * When CYP-35/39/40 land, delete this file and swap the imports — the reducer/VMs/tests are unchanged,
 * because they touch only spine fields (id/seq/type/...), never serialization (same seam trick as
 * `StubCommLiveSource` → `CommWsClient`). `detail` is `Map<String,String>` here; `:core` uses a JSON
 * object — the data layer never inspects its contents, so that swap is type-local to the detail view.
 */

/** §4.1 controlled vocabulary. [wire] = the dotted `@SerialName` the `:core` enum will carry. */
enum class EventType(val wire: String) {
    TURN_START("turn.start"),
    TURN_END("turn.end"),
    TOOL_CALL("tool.call"),
    TOOL_RESULT("tool.result"),
    FILE_CHANGED("file.changed"),
    RESULT_FINAL("result.final"),
    CONTEXT_USAGE("context.usage"),
    COMPACT_TRIGGERED("compact.triggered"),
    COMPACT_COMPLETED("compact.completed"),
    HOOK_FIRED("hook.fired"),
    ERROR_MODEL("error.model"),
    ERROR_TOOL("error.tool"),
    ERROR_RATELIMIT("error.ratelimit"),
    PROCESS_EXIT("process.exit"),
    TIMEOUT("timeout"),
    WS_DISCONNECT("ws.disconnect"),
    AGENT_SPAWNED("agent.spawned"),
    AGENT_RESTARTED("agent.restarted"),
    AGENT_STOPPED("agent.stopped"),
    SESSION_RECYCLED("session.recycled"),
    COMM_SENT("comm.sent"),
    COMM_RECEIVED("comm.received"),
    LOG_DROPPED("log.dropped"),

    /** Tolerant sentinel for an unrecognized wire type (e.g. future 07 `stall.*`) — never throws. */
    UNKNOWN("unknown");

    companion object {
        private val byWire = entries.associateBy { it.wire }

        /** Tolerant decode (mirrors the `:core` serializer contract): unknown wire → [UNKNOWN]. */
        fun fromWire(wire: String): EventType = byWire[wire] ?: UNKNOWN
    }
}

enum class Severity { DEBUG, INFO, WARN, ERROR }

/**
 * §4 spine — read-only event metadata (never hub content). Ordering authority is [seq] (monotonic,
 * total); identity/dedupe is [id] (ULID). [detail] is typed, content-free metadata.
 */
data class Event(
    val id: String,
    val ts: Long,
    val seq: Long,
    val agentId: String,
    val teamId: String,
    val type: EventType,
    val severity: Severity,
    val sourceTs: Long? = null,
    val sessionId: String? = null,
    val correlationId: String? = null,
    val detail: Map<String, String> = emptyMap(),
)

/**
 * Client-side filter → query string for REST, and filter fields in the WS subscribe frame.
 * Time window is half-open `[since, until)` (the CYP-39 semantic).
 */
data class EventFilter(
    val agentId: String? = null,
    val type: EventType? = null,
    val severity: Severity? = null,
    val since: Long? = null,
    val until: Long? = null,
    val correlationId: String? = null,
    val sessionId: String? = null,
)

/** Paging cursor: fetch events with `seq > afterSeq`, up to [limit]. Stable over [Event.seq]. */
data class Page(val afterSeq: Long? = null, val limit: Int = 100)

/** REST page response (→ `:core` wire DTO, CYP-39). */
data class EventPage(val events: List<Event>, val nextAfterSeq: Long?, val hasMore: Boolean)
