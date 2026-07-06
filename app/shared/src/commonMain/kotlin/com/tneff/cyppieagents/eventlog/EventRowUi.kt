package com.tneff.cyppieagents.eventlog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.ui.AgentAvatarView
import com.tneff.cyppieagents.ui.SenderPalette
import com.tneff.cyppieagents.ui.readableNameAccent
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_event_gap
import kmpcyppieagents.app.shared.generated.resources.a11y_event_row
import kmpcyppieagents.app.shared.generated.resources.event_gap_dropped
import kmpcyppieagents.app.shared.generated.resources.event_row_project
import kmpcyppieagents.app.shared.generated.resources.event_severity_debug
import kmpcyppieagents.app.shared.generated.resources.event_severity_error
import kmpcyppieagents.app.shared.generated.resources.event_severity_info
import kmpcyppieagents.app.shared.generated.resources.event_severity_warn
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.stringResource

// CYP-158 §2.2: below this ROW inner width the 7–8 fixed columns stop fitting → the row reflows to a
// deterministic 2-line grouping (triage line + identity line) so no column clips or char-stacks
// vertically. Row-density-specific and DELIBERATELY DISTINCT from the pane breakpoint
// (PANE_COLLAPSE_WIDTH=600dp): this is measured at the row's own inner width, not the pane. Dev-calibrated
// 560dp (CYP-26-analogue) keeps the single-pane phone (~411dp) on 2 lines and a wide master pane (>560) on 1.
private val EVENT_ROW_REFLOW_WIDTH = 560.dp

/** Localized severity label (event-log-keys §1) — severity is never colour alone (§2). */
@Composable
fun severityLabel(severity: Severity): String = stringResource(
    when (severity) {
        Severity.ERROR -> Res.string.event_severity_error
        Severity.WARN -> Res.string.event_severity_warn
        Severity.INFO -> Res.string.event_severity_info
        Severity.DEBUG -> Res.string.event_severity_debug
    },
)

/**
 * The shared, scannable Event-Log row (EVENT-LOG-UI §4) used by both surfaces. Three non-colliding
 * axes: severity rail+glyph (the one colour axis), type group-glyph+monospace text, identity
 * avatar+name (`SenderPalette`/CYP-14). Carries a text `contentDescription` (a11y) and the three
 * row tags ([rowTag] index, [qualifierTag] severity/gap, [byIdTag] id-stable). A `log.dropped` event
 * renders as a highlighted **gap** row — never a silent skip (§5.3).
 */
@Composable
fun EventRow(
    event: Event,
    rowTag: String,
    qualifierTag: String,
    byIdTag: String,
    onClick: (() -> Unit)? = null,
    /** CYP-94: render the per-row project identity (Text, never colour alone) — ONLY in the cross-project view. */
    showProject: Boolean = false,
    projectTag: String? = null,
) {
    if (event.type == EventType.LOG_DROPPED) {
        GapRow(event, rowTag, qualifierTag, byIdTag)
        return
    }
    val sevLabel = severityLabel(event.severity)
    val desc = stringResource(Res.string.a11y_event_row, sevLabel, event.typeText(), event.agentId, formatTs(event.ts))
    // rowTag + contentDescription + click live on the OUTER container (Box) so they are unchanged whether
    // the row renders as 1 visual line (≥ EVENT_ROW_REFLOW_WIDTH) or 2 (CYP-158 §2.2). Same child nodes
    // and tags either way — only their grouping changes.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(rowTag)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = desc }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        if (maxWidth < EVENT_ROW_REFLOW_WIDTH) {
            // Deterministic 2-line grouping: line 1 = triage (severity, time, type), line 2 = identity/meta.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TriageCells(event, qualifierTag, byIdTag)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IdentityCells(event, showProject, projectTag)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TriageCells(event, qualifierTag, byIdTag)
                IdentityCells(event, showProject, projectTag)
            }
        }
    }
}

/** Triage cells (severity rail+glyph, timestamp, type) — line 1 narrow, leading cells wide. */
@Composable
private fun TriageCells(event: Event, qualifierTag: String, byIdTag: String) {
    // Severity rail (the qualifier-tagged node) + glyph — colour never the sole carrier (§2).
    // CYP-274: pick the rail palette by the ACTIVE scheme (follows the R3 toggle, not just the OS) — a dark
    // surface luminance means the dark scheme is live.
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Box(
        modifier = Modifier.width(4.dp).height(22.dp).clip(RoundedCornerShape(2.dp))
            .background(event.severity.railColor(dark)).testTag(qualifierTag),
    )
    Text(event.severity.glyph(), style = MaterialTheme.typography.labelSmall)
    Text(formatTs(event.ts), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
    // Type: group glyph + monospace exact enum wire (aggregatable). Carries the id-stable tag.
    Text(
        text = "${event.type.groupGlyph()} ${event.typeText()}",
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.testTag(byIdTag),
    )
}

/** Identity/meta cells (avatar+name, correlation, optional project) — line 2 narrow, trailing cells wide. */
@Composable
private fun IdentityCells(event: Event, showProject: Boolean, projectTag: String?) {
    val identity = SenderPalette.forSender(event.agentId)
    // Identity: the shared AgentAvatar (CYP-216) — initials + CYP-14 hue + CYP-209 ring, never status (§1). The
    // event stream carries only the agentId (no Agent/custom colour) → slot default; honouring a custom colour here
    // is a follow-up (needs the agent colour map threaded into the log). Name accent keeps the CYP-14 slot HUE but
    // is luminance-adapted per surface (CYP-275) so the agent-id TEXT stays AA-readable on light AND dark schemes.
    AgentAvatarView(id = event.agentId, size = 22.dp)
    Text(event.agentId, color = readableNameAccent(identity.nameAccent), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    // Correlation chip — truncated correlationId; absent → "—", never guessed (§4).
    Text(
        text = event.correlationId?.let { "· ${it.take(8)}" } ?: "· —",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )
    // CYP-94: project identity — only in the cross-project view, as TEXT (identity ≠ severity ≠ right).
    if (showProject && projectTag != null) {
        Text(
            text = stringResource(Res.string.event_row_project, event.projectId),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.testTag(projectTag),
        )
    }
}

@Composable
private fun GapRow(event: Event, rowTag: String, qualifierTag: String, byIdTag: String) {
    val count = droppedCount(event)
    val desc = stringResource(Res.string.a11y_event_gap, count)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(rowTag)
            .background(MaterialTheme.colorScheme.errorContainer)
            .semantics { contentDescription = desc }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f // CYP-274: rail palette by active scheme
        Box(modifier = Modifier.width(4.dp).height(22.dp).background(Severity.ERROR.railColor(dark)).testTag(qualifierTag))
        Text("⚠", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        Text(
            text = stringResource(Res.string.event_gap_dropped, count),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag(byIdTag),
        )
    }
}

/** Best-effort cumulative drop count from the `log.dropped` event's content-free [Event.detail]. */
private fun droppedCount(event: Event): String =
    (event.detail["count"] ?: event.detail["dropped"])?.jsonPrimitive?.contentOrNull ?: "?"
