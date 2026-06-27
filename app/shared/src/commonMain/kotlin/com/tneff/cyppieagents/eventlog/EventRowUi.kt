package com.tneff.cyppieagents.eventlog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import com.tneff.cyppieagents.comm.SenderPalette
import com.tneff.cyppieagents.comm.initialsOf
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_event_gap
import kmpcyppieagents.app.shared.generated.resources.a11y_event_row
import kmpcyppieagents.app.shared.generated.resources.event_gap_dropped
import kmpcyppieagents.app.shared.generated.resources.event_severity_debug
import kmpcyppieagents.app.shared.generated.resources.event_severity_error
import kmpcyppieagents.app.shared.generated.resources.event_severity_info
import kmpcyppieagents.app.shared.generated.resources.event_severity_warn
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.stringResource

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
) {
    if (event.type == EventType.LOG_DROPPED) {
        GapRow(event, rowTag, qualifierTag, byIdTag)
        return
    }
    val sevLabel = severityLabel(event.severity)
    val desc = stringResource(Res.string.a11y_event_row, sevLabel, event.typeText(), event.agentId, formatTs(event.ts))
    val identity = SenderPalette.forSender(event.agentId)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(rowTag)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = desc }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Severity rail (the qualifier-tagged node) + glyph — colour never the sole carrier (§2).
        Box(
            modifier = Modifier.width(4.dp).height(22.dp).clip(RoundedCornerShape(2.dp))
                .background(event.severity.railColor()).testTag(qualifierTag),
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
        // Identity: avatar initials + name (CYP-14 hue), never status (§1).
        Box(
            modifier = Modifier.size(22.dp).clip(CircleShape).background(identity.avatarFill),
            contentAlignment = Alignment.Center,
        ) { Text(initialsOf(event.agentId), color = identity.onAvatar, style = MaterialTheme.typography.labelSmall) }
        Text(event.agentId, color = identity.nameAccent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
        // Correlation chip — truncated correlationId; absent → "—", never guessed (§4).
        Text(
            text = event.correlationId?.let { "· ${it.take(8)}" } ?: "· —",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
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
        Box(modifier = Modifier.width(4.dp).height(22.dp).background(Severity.ERROR.railColor()).testTag(qualifierTag))
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
