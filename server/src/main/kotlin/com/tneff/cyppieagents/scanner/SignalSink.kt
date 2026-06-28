package com.tneff.cyppieagents.scanner

import com.tneff.cyppieagents.events.EventDraft
import com.tneff.cyppieagents.events.EventRecorder
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity

/**
 * The Scanner's **only** write port. It emits [Signal]s — nothing else. There is deliberately no
 * method here (and no field anywhere in the scanner) that touches an agent session, so "der Scanner
 * ist nur-lesen + Signal-emittieren — kein Schreibzugriff Richtung Agent" (07 §2) holds by
 * construction, not by review discipline.
 */
fun interface SignalSink {
    suspend fun emit(signal: Signal)
}

/**
 * Emits each [Signal] as an Event into the **same** Event-Log bus (07 §2). It goes through the shared
 * [EventRecorder] tap → [com.tneff.cyppieagents.events.EventSink] write contract, so the signal-event
 * is stamped/ordered by the one ordering authority and a Detector structurally cannot forge `seq`
 * (same security-by-structure stance as every other writer).
 *
 * The wire `type` is the [Signal.type] string. Until CYP-64 enumerates the 07 types, an unknown type
 * is preserved via `EventDraft.rawType` (→ `Event.rawType`), so `/api/events` shows
 * `"stall.suspected"` rather than `"unknown"`. Severity is derived from the type ([severityOf]); the
 * default map pre-stages the 07 §4 vocabulary and is overridable so CYP-63/64 can refine it without
 * touching this class.
 */
class EventLogSignalSink(
    private val recorder: EventRecorder,
    private val severityOf: (String) -> Severity = ::defaultSeverity,
) : SignalSink {
    override suspend fun emit(signal: Signal) {
        val resolved = EventType.fromWire(signal.type)
        recorder.record(
            EventDraft(
                agentId = signal.agentId,
                projectId = signal.projectId,
                type = resolved,
                rawType = if (resolved == EventType.UNKNOWN) signal.type else null,
                severity = severityOf(signal.type),
                correlationId = signal.correlationId,
                detail = signal.evidence,
            ),
        )
    }

    companion object {
        /**
         * Default severity per 07 §4 signal vocabulary. A suspicion is `warn`, an exhausted-retries
         * escalation is `error`, everything else (`nudge.sent`, `*.recovered`) is `info`. Unknown →
         * `info` (a new signal type shouldn't read as an alarm until it's classified).
         */
        fun defaultSeverity(type: String): Severity = when {
            type.endsWith(".escalated") -> Severity.ERROR
            type.endsWith(".suspected") -> Severity.WARN
            else -> Severity.INFO
        }
    }
}
