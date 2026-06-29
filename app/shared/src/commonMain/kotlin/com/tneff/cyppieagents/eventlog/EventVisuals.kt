package com.tneff.cyppieagents.eventlog

import androidx.compose.ui.graphics.Color
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity

/**
 * Pure visual mappings for the shared Event-Log row (EVENT-LOG-UI §1–§4). Three separate axes that
 * never collide: **severity** is the one colour-bearing axis (rail hue + glyph + text label, never
 * colour alone — WCAG 1.4.1); **type** gets a group glyph + monospace text (no hue); **identity**
 * reuses `SenderPalette`/`colorSlot` (CYP-14). `commonMain`, no platform APIs.
 */

/** testTag row qualifier (event-log-tags.md §0): severity name, or `gap` for a `log.dropped` row. */
fun rowQualifier(event: Event): String =
    if (event.type == EventType.LOG_DROPPED) "gap" else event.severity.qualifier()

/**
 * Display text for the event type (EVENT-LOG-UI §3, monospace): the dotted enum wire, or — for an
 * UNKNOWN type (a newer server / additive 07 `stall.*`) — the preserved raw wire string (CYP-37
 * [Event.rawType]), never a flattened "unknown" that swallows which type it actually was.
 */
fun Event.typeText(): String =
    if (type == EventType.UNKNOWN) (rawType ?: type.wire) else type.wire

fun Severity.qualifier(): String = when (this) {
    Severity.ERROR -> "error"
    Severity.WARN -> "warn"
    Severity.INFO -> "info"
    Severity.DEBUG -> "debug"
}

/** Severity rail hue (EVENT-LOG-UI §2 — reuse of CYP-12 state hues, dark palette). */
fun Severity.railColor(): Color = when (this) {
    Severity.ERROR -> Color(0xFFFF6B6B)
    Severity.WARN -> Color(0xFFFFC857)
    Severity.INFO -> Color(0xFFA0A4AD)
    Severity.DEBUG -> Color(0xFF5A5E66)
}

/** Severity glyph — icon stand-in so colour is never the sole carrier (§2). */
fun Severity.glyph(): String = when (this) {
    Severity.ERROR -> "⚠"
    Severity.WARN -> "▲"
    Severity.INFO -> "ⓘ"
    Severity.DEBUG -> "·"
}

/** Group glyph for a type (EVENT-LOG-UI §3) — a scan/navigation aid, NOT severity. */
fun EventType.groupGlyph(): String = when (this) {
    EventType.TURN_START, EventType.TURN_END, EventType.TOOL_CALL, EventType.TOOL_RESULT,
    EventType.FILE_CHANGED, EventType.RESULT_FINAL -> "⚙"
    EventType.CONTEXT_USAGE, EventType.COMPACT_TRIGGERED, EventType.COMPACT_COMPLETED -> "▦"
    EventType.HOOK_FIRED -> "⤵"
    EventType.ERROR_MODEL, EventType.ERROR_TOOL, EventType.ERROR_RATELIMIT,
    EventType.PROCESS_EXIT, EventType.TIMEOUT, EventType.WS_DISCONNECT -> "⚠"
    EventType.AGENT_SPAWNED, EventType.AGENT_RESTARTED, EventType.AGENT_STOPPED, EventType.SESSION_RECYCLED -> "⏻"
    EventType.COMM_SENT, EventType.COMM_RECEIVED -> "⇄"
    // Mediator-Aufsicht supervision family (07/S11, CYP-64). Provisional glyph — a non-blocking
    // UIUX/Dev polish may refine it; severity (warn/info/error) still carries the alarm, not this.
    EventType.STALL_SUSPECTED, EventType.NUDGE_SENT, EventType.STALL_RECOVERED, EventType.STALL_ESCALATED -> "☂"
    // Connector fidelity degradation (Doc 10 §3, CYP-121). Provisional glyph; severity (warn) carries
    // the signal, this is the scan aid. CYP-119/123 UIUX may refine it (per-agent degradation surface).
    EventType.CAPABILITY_DEGRADED -> "▽"
    EventType.LOG_DROPPED -> "⚠"
    EventType.UNKNOWN -> "ⓘ"
}

/**
 * UTC wall-clock `HH:MM:SS.mmm` from epoch ms — millisecond-precise (§4 needs resolution for the load
 * test). No timezone lib in `commonMain`; display only — ordering is always by `seq`, never this (§5).
 */
fun formatTs(ts: Long): String {
    val dayMs = ((ts % 86_400_000L) + 86_400_000L) % 86_400_000L
    val h = dayMs / 3_600_000L
    val m = (dayMs / 60_000L) % 60L
    val s = (dayMs / 1_000L) % 60L
    val millis = dayMs % 1_000L
    fun p2(v: Long) = v.toString().padStart(2, '0')
    return "${p2(h)}:${p2(m)}:${p2(s)}.${millis.toString().padStart(3, '0')}"
}
