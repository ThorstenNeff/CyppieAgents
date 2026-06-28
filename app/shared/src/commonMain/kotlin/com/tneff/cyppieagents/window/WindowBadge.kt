package com.tneff.cyppieagents.window

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.Severity
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_badge_error
import kmpcyppieagents.app.shared.generated.resources.a11y_badge_severity
import kmpcyppieagents.app.shared.generated.resources.a11y_badge_unread
import kmpcyppieagents.app.shared.generated.resources.badge_count_overflow
import kmpcyppieagents.app.shared.generated.resources.event_severity_debug
import kmpcyppieagents.app.shared.generated.resources.event_severity_error
import kmpcyppieagents.app.shared.generated.resources.event_severity_info
import kmpcyppieagents.app.shared.generated.resources.event_severity_warn
import org.jetbrains.compose.resources.stringResource

/**
 * A per-window activity badge (CYP-55) — a **passive hint** ("there is activity here"), never
 * "done/delivered". Exactly one variant per window (the most relevant). **Fail-closed:** there is no
 * `WindowBadge` value for "nothing" — the absence of a badge is the honest empty state (callers pass
 * `null` and render nothing), so a badge node only ever exists when there is a real source.
 *
 * - [Count]   — Comm unread (B1): messages new since the window was last focused.
 * - [Severity]— Event-Log max open severity (C1): only on the operator-gated Event-Log window.
 * - [Attention]— Agent honest `ERROR` (A1): never a faked `WAITING_FOR_INPUT`.
 */
sealed interface WindowBadge {
    data class Count(val newCount: Int) : WindowBadge
    data class SeverityLevel(val severity: Severity) : WindowBadge
    data object Attention : WindowBadge
}

/**
 * Renders [badge] at the end of a window title bar / pager indicator. **Form carries meaning** (number
 * pill vs severity-symbol pill vs warning triangle), so the variants are distinguishable without
 * colour (WCAG 1.4.1); each carries an a11y label naming the window + meaning. [windowTitle] fills the
 * `%1$s` of the a11y strings.
 *
 * [containerTag] tags the badge container: in the canvas title bar it is `windowBadge.<id>` (the
 * default); in the phone pager it reuses the existing `phonePager.page.<id>.badge` slot (CYP-54 §6).
 * The inner variant nodes (`windowBadge.<id>.{count,severity,attention}`) stay the same in both modes —
 * the badge *form* is one vocabulary across canvas and pager.
 */
@Composable
fun WindowBadgeView(
    windowId: String,
    windowTitle: String,
    badge: WindowBadge,
    modifier: Modifier = Modifier,
    containerTag: String = WindowBadgeTags.badge(windowId),
) {
    Box(modifier = modifier.testTag(containerTag)) {
        when (badge) {
            is WindowBadge.Count -> CountBadge(windowId, windowTitle, badge.newCount)
            is WindowBadge.SeverityLevel -> SeverityBadge(windowId, windowTitle, badge.severity)
            WindowBadge.Attention -> AttentionBadge(windowId, windowTitle)
        }
    }
}

@Composable
private fun CountBadge(windowId: String, windowTitle: String, count: Int) {
    // Layout-stable: > 9 collapses to "9+" rather than growing the title bar.
    val shown = if (count > 9) stringResource(Res.string.badge_count_overflow) else count.toString()
    val description = stringResource(Res.string.a11y_badge_unread, windowTitle, count.toString())
    Pill(
        text = shown,
        container = MaterialTheme.colorScheme.primaryContainer, // neutral — NOT a severity colour
        content = MaterialTheme.colorScheme.onPrimaryContainer,
        tag = WindowBadgeTags.count(windowId),
        description = description,
    )
}

@Composable
private fun SeverityBadge(windowId: String, windowTitle: String, severity: Severity) {
    // A severity SYMBOL (not just colour) + the severity name in a11y; the node never carries event
    // content (no body/meta) — only the enum (leak-free, the C1/C2 boundary).
    val glyph = when (severity) {
        Severity.ERROR -> "✕"
        Severity.WARN -> "!"
        Severity.INFO -> "i"
        Severity.DEBUG -> "·"
    }
    val color = when (severity) {
        Severity.ERROR -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.onError
        Severity.WARN -> MaterialTheme.colorScheme.tertiary to MaterialTheme.colorScheme.onTertiary
        Severity.INFO -> MaterialTheme.colorScheme.secondary to MaterialTheme.colorScheme.onSecondary
        Severity.DEBUG -> MaterialTheme.colorScheme.outline to MaterialTheme.colorScheme.surface
    }
    val severityName = when (severity) {
        Severity.ERROR -> stringResource(Res.string.event_severity_error)
        Severity.WARN -> stringResource(Res.string.event_severity_warn)
        Severity.INFO -> stringResource(Res.string.event_severity_info)
        Severity.DEBUG -> stringResource(Res.string.event_severity_debug)
    }
    Pill(
        text = glyph,
        container = color.first,
        content = color.second,
        tag = WindowBadgeTags.severity(windowId),
        description = stringResource(Res.string.a11y_badge_severity, windowTitle, severityName),
    )
}

@Composable
private fun AttentionBadge(windowId: String, windowTitle: String) {
    // A warning triangle (distinct shape) in the error tone — honest ERROR only (A1). The glyph carries
    // the a11y meaning ("<window>: agent error"), not the raw symbol.
    val description = stringResource(Res.string.a11y_badge_error, windowTitle)
    Text(
        text = "⚠",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .testTag(WindowBadgeTags.attention(windowId))
            .clearAndSetSemantics { contentDescription = description },
    )
}

@Composable
private fun Pill(text: String, container: Color, content: Color, tag: String, description: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .testTag(tag)
            // Merge the glyph/number into this node and add the spoken label: the screen reader announces
            // the meaning (contentDescription) while the visible symbol stays addressable for tests.
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Text(text = text, color = content, style = MaterialTheme.typography.labelSmall)
    }
}
