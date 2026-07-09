package com.tneff.cyppieagents.compact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.eventlog.formatTs
import com.tneff.cyppieagents.eventlog.severityColor
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import com.tneff.cyppieagents.window.formatCompactTokens
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_compact_allow
import kmpcyppieagents.app.shared.generated.resources.compact_allow_hint
import kmpcyppieagents.app.shared.generated.resources.compact_allow_label
import kmpcyppieagents.app.shared.generated.resources.compact_last_run_ok
import kmpcyppieagents.app.shared.generated.resources.compact_last_run_timeout
import kmpcyppieagents.app.shared.generated.resources.compact_status_idle
import kmpcyppieagents.app.shared.generated.resources.compact_status_label
import kmpcyppieagents.app.shared.generated.resources.compact_status_off
import kmpcyppieagents.app.shared.generated.resources.compact_status_running
import kmpcyppieagents.app.shared.generated.resources.compact_threshold_label
import kmpcyppieagents.app.shared.generated.resources.workspace_operator_only
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-326 — the compact-orchestration window body (UIUX spec §1.3). One column, top-down:
 * the global "compact allowed" control (operator checkbox / member read-only chip), the mandatory INFO
 * disclosure hint, the operator gate hint (members), then the **server-mirror** facts (threshold, status,
 * last run X/N).
 *
 * Honesty (§3): default OFF + operator-gated + never optimistic; `compact.completed`/a full run are
 * **neutral, never green**; a **timeout is WARN amber**, never faked as done; the server state is a mirror —
 * when UNKNOWN the status/threshold rows are **absent**, never a defaulted "idle"/"off".
 */
@Composable
fun CompactPanel(viewModel: CompactViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val status = state.status
    val allowed = status?.allowed ?: false // fail-closed default when the server state is unknown

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag(CompactTags.PANEL),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // §1.2 Control: operator → live checkbox; non-operator → read-only chip (ACL honesty: a form marker,
        // never a fake/disabled switch that implies editability).
        val allowLabel = stringResource(Res.string.compact_allow_label)
        if (state.editable) {
            val allowA11y = stringResource(Res.string.a11y_compact_allow)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Checkbox(
                    checked = allowed,
                    onCheckedChange = { viewModel.setAllowed(it) },
                    modifier = Modifier
                        .testTag(CompactTags.ALLOW_TOGGLE)
                        .semantics { contentDescription = allowA11y },
                )
                Text(allowLabel, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            // Form marker (✓ on / – off) carries the state without colour (WCAG 1.4.1); read-only, no switch.
            Row(
                modifier = Modifier.testTag(CompactTags.ALLOW_CHIP),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(if (allowed) "✓" else "–", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(allowLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // §3-1 mandatory disclosure — ALWAYS visible (not only when on): the honest, irreversible consequence.
        TonedHint(stringResource(Res.string.compact_allow_hint), HintTone.INFO, CompactTags.ALLOW_HINT)

        // Non-operator: honest "only the operator can change this" (reuse workspace_operator_only, GATED tone).
        if (!state.editable) {
            TonedHint(stringResource(Res.string.workspace_operator_only), HintTone.GATED, CompactTags.GATE_HINT)
        }

        HorizontalDivider()

        // §3-3 server-mirror facts — rendered ONLY when the server state resolved (status != null). UNKNOWN →
        // these rows are absent (never a defaulted idle/off).
        status?.let { s ->
            LabelValueRow(
                label = stringResource(Res.string.compact_threshold_label),
                value = formatCompactTokens(s.thresholdTokens),
                tag = CompactTags.THRESHOLD,
            )

            val statusText = when {
                !s.allowed -> stringResource(Res.string.compact_status_off)
                s.running -> stringResource(Res.string.compact_status_running, s.lastRun?.startedTs?.let { formatTs(it) } ?: "—")
                else -> stringResource(Res.string.compact_status_idle)
            }
            LabelValueRow(
                label = stringResource(Res.string.compact_status_label),
                value = statusText,
                tag = CompactTags.STATUS,
                // Announce idle→running→done transitions even when focus is elsewhere.
                rowModifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )

            // §1.3 last run — only a FINISHED run (finishedTs != null); an in-flight run shows via the status row.
            s.lastRun?.takeIf { it.finishedTs != null }?.let { run ->
                val timedOut = run.pendingAgentIds.isNotEmpty()
                val text =
                    if (timedOut) {
                        stringResource(Res.string.compact_last_run_timeout, run.completed, run.total, run.pendingAgentIds.size)
                    } else {
                        stringResource(Res.string.compact_last_run_ok, run.completed, run.total)
                    }
                // §3-4 timeout ≠ success: WARN amber (shared severity role, no hardcode); a full run stays neutral.
                val color = if (timedOut) severityColor(Severity.WARN) else MaterialTheme.colorScheme.onSurfaceVariant
                Text(
                    text = text,
                    color = color,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().testTag(CompactTags.LAST_RUN),
                )
            }
        }
    }
}

/** A read-only fact row: `onSurfaceVariant` label + `onSurface` value. [rowModifier] carries per-row semantics. */
@Composable
private fun LabelValueRow(label: String, value: String, tag: String, rowModifier: Modifier = Modifier) {
    Row(
        modifier = rowModifier.fillMaxWidth().testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}
