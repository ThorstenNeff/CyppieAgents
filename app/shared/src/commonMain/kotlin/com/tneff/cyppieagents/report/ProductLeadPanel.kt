package com.tneff.cyppieagents.report
import com.tneff.cyppieagents.model.ReportType
import com.tneff.cyppieagents.model.ReportItem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.eventlog.formatTs
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.window.PANE_COLLAPSE_WIDTH
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.comm_back
import kmpcyppieagents.app.shared.generated.resources.event_severity_debug
import kmpcyppieagents.app.shared.generated.resources.event_severity_error
import kmpcyppieagents.app.shared.generated.resources.event_severity_info
import kmpcyppieagents.app.shared.generated.resources.event_severity_warn
import kmpcyppieagents.app.shared.generated.resources.report_access_denied
import kmpcyppieagents.app.shared.generated.resources.report_advisory
import kmpcyppieagents.app.shared.generated.resources.a11y_report_snapshot_select
import kmpcyppieagents.app.shared.generated.resources.report_as_of
import kmpcyppieagents.app.shared.generated.resources.report_empty
import kmpcyppieagents.app.shared.generated.resources.report_error
import kmpcyppieagents.app.shared.generated.resources.report_generate
import kmpcyppieagents.app.shared.generated.resources.report_generating
import kmpcyppieagents.app.shared.generated.resources.report_provenance
import kmpcyppieagents.app.shared.generated.resources.report_snapshot_hint
import kmpcyppieagents.app.shared.generated.resources.report_type_defects
import kmpcyppieagents.app.shared.generated.resources.report_type_status
import kmpcyppieagents.app.shared.generated.resources.report_type_usage
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Product-Lead window body (S16, CYP-90): an on-demand trigger bar + a newest-first snapshot list
 * with a read-only detail pane (reuse of the CommPanel list+detail shape). **Operator-gated/fail-closed**
 * — without a token the panel is just the gate hint (no trigger, no list).
 *
 * Snapshot ≠ live: every snapshot shows a prominent "As of: <ts>" + provenance, so a report is never
 * shown as a standing truth. The defect register is advisory ("observed, not exhaustive/confirmed") with
 * a reused severity rail (colour **+ symbol + label**, never colour alone). Items are content-free.
 */
@Composable
fun ProductLeadPanel(viewModel: ProductLeadViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()

    if (!state.accessible) {
        // Fail-closed: no report, no trigger — only the honest gate hint.
        Column(modifier = modifier.fillMaxSize().padding(16.dp).testTag(ProductLeadTags.PANEL)) {
            HintLine(stringResource(Res.string.report_access_denied), MaterialTheme.colorScheme.tertiary, ProductLeadTags.GATE_HINT)
        }
        return
    }

    Column(
        modifier = modifier.fillMaxSize().padding(12.dp).testTag(ProductLeadTags.PANEL),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TriggerBar(state, viewModel)
        if (state.generating) {
            HintLine(stringResource(Res.string.report_generating), MaterialTheme.colorScheme.tertiary, ProductLeadTags.GENERATING)
        }
        state.error?.let { HintLine(stringResource(errorRes(it)), MaterialTheme.colorScheme.error, ProductLeadTags.ERROR) }

        // CYP-156: the TriggerBar above stays put (it is not a pane); only the master/detail collapses.
        // Below PANE_COLLAPSE_WIDTH (panel inner width) → single-pane: snapshot list OR detail (+back).
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth < PANE_COLLAPSE_WIDTH) {
                if (state.selectedId == null) {
                    SnapshotList(state, viewModel, modifier = Modifier.fillMaxWidth())
                } else {
                    DetailPane(state, onBack = viewModel::clearSelection, modifier = Modifier.fillMaxWidth())
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SnapshotList(state, viewModel, modifier = Modifier.weight(0.4f))
                    DetailPane(state, onBack = null, modifier = Modifier.weight(0.6f))
                }
            }
        }
    }
}

// CYP-156 §3.1: Row → FlowRow so the label + 3 trigger buttons WRAP instead of squashing the narrowest
// button to a 2-line text break on a phone (411dp; Tester-measured). Same node + tags (productLead.trigger
// + .usage/.status/.defects). FlowRow is Foundation-only — no shared code with CYP-158.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TriggerBar(state: ProductLeadUiState, viewModel: ProductLeadViewModel) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().testTag(ProductLeadTags.TRIGGER),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.report_generate),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        TriggerButton(Res.string.report_type_usage, ProductLeadTags.TRIGGER_USAGE, state.generating) { viewModel.generate(ReportType.USAGE) }
        TriggerButton(Res.string.report_type_status, ProductLeadTags.TRIGGER_STATUS, state.generating) { viewModel.generate(ReportType.STATUS) }
        TriggerButton(Res.string.report_type_defects, ProductLeadTags.TRIGGER_DEFECTS, state.generating) { viewModel.generate(ReportType.DEFECTS) }
    }
}

@Composable
private fun TriggerButton(label: StringResource, tag: String, generating: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !generating, modifier = Modifier.testTag(tag)) {
        // maxLines = 1: never break a label like "Status"/"report" across two lines inside the button.
        Text(stringResource(label), maxLines = 1)
    }
}

@Composable
private fun SnapshotList(state: ProductLeadUiState, viewModel: ProductLeadViewModel, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().testTag(ProductLeadTags.LIST),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state.metas.isEmpty()) {
            HintLine(stringResource(Res.string.report_empty), MaterialTheme.colorScheme.onSurfaceVariant, ProductLeadTags.EMPTY)
            return
        }
        state.metas.forEach { meta ->
            val selected = meta.id == state.selectedId
            // CYP-277: name the selectable snapshot (type + as-of) so a screen reader announces the clickable
            // row's purpose, not just its two child texts.
            val snapshotA11y = stringResource(
                Res.string.a11y_report_snapshot_select,
                stringResource(reportTypeLabel(meta.type)),
                formatTs(meta.generatedAt),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ProductLeadTags.snapshot(meta.id))
                    .clickable { viewModel.select(meta.id) }
                    .semantics { contentDescription = snapshotA11y }
                    .padding(8.dp),
            ) {
                Text(
                    text = stringResource(reportTypeLabel(meta.type)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                // "As of: <ts>" on the row — a snapshot, not a live status.
                Text(
                    text = stringResource(Res.string.report_as_of, formatTs(meta.generatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(ProductLeadTags.snapshotTs(meta.id)),
                )
            }
        }
    }
}

@Composable
private fun DetailPane(
    state: ProductLeadUiState,
    // CYP-156: single-pane only — an explicit "back to snapshots" affordance. null in two-pane (no back).
    onBack: (() -> Unit)?,
    modifier: Modifier,
) {
    val snap = state.selected
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag(ProductLeadTags.DETAIL),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) {
            Text(
                text = "‹ " + stringResource(Res.string.comm_back),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .testTag(ProductLeadTags.BACK)
                    .padding(vertical = 4.dp),
            )
        }
        if (snap == null) return
        // Prominent "As of" — snapshot ≠ live, restated with the may-be-outdated hint.
        Text(
            text = stringResource(Res.string.report_as_of, formatTs(snap.generatedAt)),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag(ProductLeadTags.DETAIL_AS_OF).semantics { heading() },
        )
        Text(stringResource(Res.string.report_snapshot_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Provenance: named sources + observation window (observed, not authoritative).
        val windowLabel = listOfNotNull(snap.window.sinceLabel, snap.window.untilLabel).joinToString(" → ").ifBlank { "—" }
        HintLine(
            stringResource(Res.string.report_provenance, snap.sources.joinToString(", "), windowLabel),
            MaterialTheme.colorScheme.onSurfaceVariant, ProductLeadTags.DETAIL_PROVENANCE,
        )
        // Defect register is advisory — observed, not exhaustive/confirmed.
        if (snap.type == ReportType.DEFECTS) {
            HintLine(stringResource(Res.string.report_advisory), MaterialTheme.colorScheme.secondary, ProductLeadTags.DETAIL_ADVISORY)
        }
        // Sections; a defect line carries a reused severity rail (colour + symbol + label).
        var defectIndex = 0
        snap.sections.forEach { section ->
            Column(modifier = Modifier.fillMaxWidth().testTag(ProductLeadTags.section(section.key)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(section.title, style = MaterialTheme.typography.titleSmall)
                section.items.forEach { item ->
                    if (item.severity != null) {
                        DefectRow(defectIndex, item)
                        defectIndex++
                    } else {
                        Text(
                            text = item.refLabel?.let { "${item.text} · $it" } ?: item.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DefectRow(index: Int, item: ReportItem) {
    val severity = item.severity ?: return
    val sevName = severity.name.lowercase()
    Row(
        modifier = Modifier.fillMaxWidth().testTag(ProductLeadTags.defect(index)),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Severity rail: symbol (not colour alone) + the reused severity label, tagged with the qualifier.
        val label = stringResource(severityLabel(severity))
        Text(
            text = severityGlyph(severity),
            color = severityColor(severity),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .testTag(ProductLeadTags.defectSeverity(index, sevName))
                .semantics { contentDescription = label },
        )
        Text(
            text = item.refLabel?.let { "${item.text} · $it" } ?: item.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun HintLine(text: String, tone: Color, tag: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = tone, modifier = Modifier.fillMaxWidth().testTag(tag))
}

private fun reportTypeLabel(type: ReportType): StringResource = when (type) {
    ReportType.USAGE -> Res.string.report_type_usage
    ReportType.STATUS -> Res.string.report_type_status
    ReportType.DEFECTS -> Res.string.report_type_defects
}

private fun severityLabel(s: Severity): StringResource = when (s) {
    Severity.ERROR -> Res.string.event_severity_error
    Severity.WARN -> Res.string.event_severity_warn
    Severity.INFO -> Res.string.event_severity_info
    Severity.DEBUG -> Res.string.event_severity_debug
}

private fun severityGlyph(s: Severity): String = when (s) {
    Severity.ERROR -> "✕"
    Severity.WARN -> "!"
    Severity.INFO -> "i"
    Severity.DEBUG -> "·"
}

@Composable
private fun severityColor(s: Severity): Color = when (s) {
    Severity.ERROR -> MaterialTheme.colorScheme.error
    Severity.WARN -> MaterialTheme.colorScheme.tertiary
    Severity.INFO -> MaterialTheme.colorScheme.secondary
    Severity.DEBUG -> MaterialTheme.colorScheme.outline
}

@Composable
private fun errorRes(key: String): StringResource = when (key) {
    "report_access_denied" -> Res.string.report_access_denied
    else -> Res.string.report_error
}
