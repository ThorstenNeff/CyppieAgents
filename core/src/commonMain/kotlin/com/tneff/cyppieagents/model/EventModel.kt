package com.tneff.cyppieagents.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject

/**
 * Observability Event-Log read contract (PRD 06 §4). One definition compiled into both `:server`
 * (the stamper/projector) and `:app:shared` (the Browse/Live-Tail UIs) via `:core`, so the wire
 * shape can never drift — exactly like [Message]/[AclEntry].
 *
 * This is the **read** view. The write side lives server-side as `EventDraft`: callers supply only
 * the content-free metadata, and the `append` path assigns `id`/`ts`/`seq` so a caller structurally
 * *cannot* forge the total order (same security-by-structure stance as [Agent] having no token field).
 *
 * Non-Goal enforced structurally (PRD §2/§3.5): [detail] carries **only metadata** — never
 * `TextBlock.text`, `ToolUseBlock.input`, `ToolResultBlock.content` or `Message.body`. The projector
 * (CYP-37) is what fills it field-by-field; this DTO just transports the result.
 */
@Serializable(with = EventSerializer::class)
data class Event(
    /** ULID — time-sortable, ideal for append-only + paging. */
    val id: String,
    /** epoch ms, **authoritative**, set in the append path via `TimeSource.now()`. */
    val ts: Long,
    /** monotonic (`TimeSource.nextSeq()`) → **total order**, even on equal ms / clock jumps. */
    val seq: Long,
    /** observed source time (e.g. a hook) — informational, never order-forming. */
    val sourceTs: Long? = null,
    val agentId: String,
    /** team = project token (05 §3). */
    val teamId: String,
    /** Claude-Code session — correlation across a compaction. */
    val sessionId: String? = null,
    /** per injected work-run (PO decision c): set at `turn.start`, carried to `result.final`. */
    val correlationId: String? = null,
    /** controlled vocabulary (no free text) → aggregatable, not just greppable. */
    val type: EventType,
    val severity: Severity,
    /** type-specific, **content-free** payload (PRD §3.5). */
    val detail: JsonObject = JsonObject(emptyMap()),
    /**
     * The raw wire type string, kept ONLY when [type] decoded to [EventType.UNKNOWN] (a newer server
     * or a 07 `stall.*` type this build doesn't model). Lets the UI show the raw type verbatim instead
     * of swallowing it (EVENT-LOG-UI §3) — "nothing gets eaten". Null for known types; derived at
     * decode time, round-trips via the `type` field, never a separate wire field. (CYP-37, additive.)
     */
    val rawType: String? = null,
)

/** Quick-filter axis during the load test (PRD §4). */
@Serializable
enum class Severity {
    @SerialName("debug") DEBUG,
    @SerialName("info") INFO,
    @SerialName("warn") WARN,
    @SerialName("error") ERROR,
}

/**
 * Controlled event vocabulary (PRD §4.1). Decoded **tolerantly**: an unknown wire string (a newer
 * server, or an additive type 07 introduces like `stall.suspected`) maps to [UNKNOWN] instead of
 * throwing — same resilience stance as [TolerantToolsSerializer]. The list is intentionally open.
 */
@Serializable(with = EventTypeSerializer::class)
enum class EventType(val wire: String) {
    // Agent activity (stream-json)
    TURN_START("turn.start"),
    TURN_END("turn.end"),
    TOOL_CALL("tool.call"),
    TOOL_RESULT("tool.result"),
    FILE_CHANGED("file.changed"),
    RESULT_FINAL("result.final"),

    // Token boundaries (Mediator usage)
    CONTEXT_USAGE("context.usage"),
    COMPACT_TRIGGERED("compact.triggered"),
    COMPACT_COMPLETED("compact.completed"),

    // Hooks (spool)
    HOOK_FIRED("hook.fired"),

    // Errors / interruption (Mediator)
    ERROR_MODEL("error.model"),
    ERROR_TOOL("error.tool"),
    ERROR_RATELIMIT("error.ratelimit"),
    PROCESS_EXIT("process.exit"),
    TIMEOUT("timeout"),
    WS_DISCONNECT("ws.disconnect"),

    // Lifecycle (Mediator/Boot)
    AGENT_SPAWNED("agent.spawned"),
    AGENT_RESTARTED("agent.restarted"),
    AGENT_STOPPED("agent.stopped"),
    SESSION_RECYCLED("session.recycled"),

    // Communication metadata (Mediator/Router) — from/to/channel/kind, NO content
    COMM_SENT("comm.sent"),
    COMM_RECEIVED("comm.received"),

    // Mediator-Aufsicht: Scanner & Warden (07/S11) — the Sense→Decide→Act loop's own events. Now
    // first-class in the controlled vocabulary (CYP-64); before CYP-64 they rode in via `rawType`.
    STALL_SUSPECTED("stall.suspected"),
    NUDGE_SENT("nudge.sent"),
    STALL_RECOVERED("stall.recovered"),
    STALL_ESCALATED("stall.escalated"),

    /**
     * Telemetry self-report: the bounded queue dropped events under back-pressure (PRD §3.3, PO
     * decision CYP-44). Emitted by the **writer** with a cumulative count so a gap in the log is
     * visible in the same telemetry the operator watches — never silently swallowed.
     */
    LOG_DROPPED("log.dropped"),

    /** Fallback for any wire string not modelled here (tolerant decode). */
    UNKNOWN("unknown");

    companion object {
        private val byWire: Map<String, EventType> = entries.associateBy { it.wire }

        /** Tolerant lookup: unknown wire string → [UNKNOWN], never throws. */
        fun fromWire(wire: String): EventType = byWire[wire] ?: UNKNOWN
    }
}

/**
 * Serializes [EventType] as its [EventType.wire] string and decodes tolerantly via
 * [EventType.fromWire] (unknown → [EventType.UNKNOWN]). Keeps older clients from crashing on a
 * type a newer server emits (PRD §4.1).
 */
object EventTypeSerializer : KSerializer<EventType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.tneff.cyppieagents.model.EventType", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: EventType) = encoder.encodeString(value.wire)

    override fun deserialize(decoder: Decoder): EventType = EventType.fromWire(decoder.decodeString())
}

/** Wire surrogate for [Event] — carries `type` as the raw string so the decoder can preserve it. */
@Serializable
private class EventSurrogate(
    val id: String,
    val ts: Long,
    val seq: Long,
    val sourceTs: Long? = null,
    val agentId: String,
    val teamId: String,
    val sessionId: String? = null,
    val correlationId: String? = null,
    val type: String,
    val severity: Severity,
    val detail: JsonObject = JsonObject(emptyMap()),
)

/**
 * Serializes [Event] while preserving an unknown wire `type` into [Event.rawType]. On decode, the raw
 * `type` string is mapped via [EventType.fromWire]; if it's [EventType.UNKNOWN], the original string is
 * kept in `rawType`. On encode, `rawType` (if present) is written back as `type`, so an unknown type
 * round-trips losslessly without a separate wire field.
 */
object EventSerializer : KSerializer<Event> {
    override val descriptor: SerialDescriptor = EventSurrogate.serializer().descriptor

    override fun deserialize(decoder: Decoder): Event {
        val s = decoder.decodeSerializableValue(EventSurrogate.serializer())
        val resolved = EventType.fromWire(s.type)
        return Event(
            id = s.id, ts = s.ts, seq = s.seq, sourceTs = s.sourceTs, agentId = s.agentId,
            teamId = s.teamId, sessionId = s.sessionId, correlationId = s.correlationId,
            type = resolved, severity = s.severity, detail = s.detail,
            rawType = if (resolved == EventType.UNKNOWN) s.type else null,
        )
    }

    override fun serialize(encoder: Encoder, value: Event) {
        encoder.encodeSerializableValue(
            EventSurrogate.serializer(),
            EventSurrogate(
                id = value.id, ts = value.ts, seq = value.seq, sourceTs = value.sourceTs,
                agentId = value.agentId, teamId = value.teamId, sessionId = value.sessionId,
                correlationId = value.correlationId,
                type = value.rawType ?: value.type.wire, // unknown raw string round-trips via `type`
                severity = value.severity, detail = value.detail,
            ),
        )
    }
}
