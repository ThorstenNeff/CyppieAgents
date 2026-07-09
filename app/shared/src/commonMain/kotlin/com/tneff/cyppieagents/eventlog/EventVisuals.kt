package com.tneff.cyppieagents.eventlog

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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

/**
 * Severity rail hue (EVENT-LOG-UI §2), **scheme-adaptive** (CYP-274). [dark] keeps the CYP-12 dark palette; the
 * maritime LIGHT scheme uses darker/saturated tones so ERROR/WARN/INFO clear the ≥3:1 non-text target
 * (WCAG 1.4.11) on the white surface — semantics preserved, none confusable with the brand `primary`. DEBUG is
 * **deliberately dim in BOTH schemes**: the quietest severity earns no ≥3:1 rail; its meaning rides the `·` glyph
 * + text label, never colour alone (§2 / WCAG 1.4.1). Hand-picked per UIUX (severity-rail-per-scheme-forward.md).
 */
fun Severity.railColor(dark: Boolean): Color = if (dark) {
    when (this) {
        Severity.ERROR -> Color(0xFFFF6B6B)
        Severity.WARN -> Color(0xFFFFC857)
        Severity.INFO -> Color(0xFFA0A4AD)
        Severity.DEBUG -> Color(0xFF5A5E66)
    }
} else {
    when (this) {
        Severity.ERROR -> Color(0xFFB3261E) // = colorScheme.error, 6.5:1 on white
        Severity.WARN -> Color(0xFF9A6400) // 5.0:1
        Severity.INFO -> Color(0xFF567083) // 5.2:1
        Severity.DEBUG -> Color(0xFFB8BCC4) // ~2.0:1 — deliberately dim (see KDoc)
    }
}

/**
 * CYP-300 (a0) — the ONE shared severity-colour source. WARN was rendered via the brand `tertiary` role in 3–4
 * duplicated `when(severity)` blocks (WindowBadge / ProductLeadPanel / AclPanel offline banner); E1 turns
 * `tertiary` GREEN at night, so a warning/offline would read as "ok/connected" (inverted meaning). a0 hangs WARN
 * on **amber** — the SAME CYP-274 [railColor] tone (foreground) plus an amber container variant — so every
 * severity rendering draws its WARN from here and `tertiary` becomes a pure brand accent (§9-Inv.1). All other
 * severities keep their established roles (error / secondary / outline) — a0 only de-overloads `tertiary`.
 *
 * The pure `*For(sev, scheme, dark)` cores are unit-testable (no composition); the `@Composable` wrappers read
 * the active scheme + scheme-brightness. [dark] only selects the WARN amber tone (foreground/container).
 */
fun severityColorFor(severity: Severity, scheme: ColorScheme, dark: Boolean): Color = when (severity) {
    Severity.ERROR -> scheme.error
    Severity.WARN -> severity.railColor(dark) // amber (CYP-274) — de-overloaded from `tertiary`
    Severity.INFO -> scheme.secondary
    Severity.DEBUG -> scheme.outline
}

/** (container, onColor) severity pair for pills/banners. WARN = amber container; others keep their roles. */
fun severityContainerFor(severity: Severity, scheme: ColorScheme, dark: Boolean): Pair<Color, Color> = when (severity) {
    Severity.ERROR -> scheme.error to scheme.onError
    Severity.WARN -> warnContainer(dark) // amber container — a0
    Severity.INFO -> scheme.secondary to scheme.onSecondary
    Severity.DEBUG -> scheme.outline to scheme.surface
}

/** Foreground severity colour at a render site (WARN = amber, never `tertiary`). */
@Composable
fun severityColor(severity: Severity): Color =
    severityColorFor(severity, MaterialTheme.colorScheme, MaterialTheme.colorScheme.surface.luminance() < 0.5f)

/** (container, onColor) severity pair at a render site (WARN = amber container). */
@Composable
fun severityContainer(severity: Severity): Pair<Color, Color> =
    severityContainerFor(severity, MaterialTheme.colorScheme, MaterialTheme.colorScheme.surface.luminance() < 0.5f)

/**
 * The WARN amber CONTAINER (surface + AA onColor), scheme-adaptive. **a0: DERIVED** from the CYP-274 WARN tone
 * (light `#9A6400` / dark `#FFC857`) — UIUX ratifies the exact hex at the §9 UX-QA; this helper is the SOLE seam,
 * so a change is two values with **no call-site churn**. onColor ≥ AA (≥4.5:1) on the container in both schemes.
 */
private fun warnContainer(dark: Boolean): Pair<Color, Color> = if (dark) {
    Color(0xFF4A3A10) to Color(0xFFFFC857) // dark amber-brown surface + bright-amber onColor
} else {
    Color(0xFFFFE7B0) to Color(0xFF5A3D00) // pale gold surface + dark-brown onColor
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
    EventType.CONTEXT_USAGE, EventType.COMPACT_TRIGGERED, EventType.COMPACT_COMPLETED,
    // CYP-326 compact-orchestration family (same compaction group; Dev/UIUX may refine the glyph).
    EventType.COMPACT_PREPARE_SENT, EventType.COMPACT_REQUEST_SENT, EventType.COMPACT_ORCHESTRATION_DONE -> "▦"
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
    // Connector opt-in (Doc 10 §5, CYP-122) — an operator chose a connector (e.g. Connector B). Provisional.
    EventType.CONNECTOR_OPTIN -> "⇆"
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
