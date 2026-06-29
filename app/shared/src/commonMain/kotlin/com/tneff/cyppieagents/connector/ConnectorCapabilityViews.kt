package com.tneff.cyppieagents.connector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_connector_badge
import kmpcyppieagents.app.shared.generated.resources.a11y_connector_degraded
import kmpcyppieagents.app.shared.generated.resources.connector_degradation_hint
import kmpcyppieagents.app.shared.generated.resources.connector_degraded
import kmpcyppieagents.app.shared.generated.resources.connector_dim_coordination
import kmpcyppieagents.app.shared.generated.resources.connector_dim_rate_limit_signal
import kmpcyppieagents.app.shared.generated.resources.connector_dim_reliable_result
import kmpcyppieagents.app.shared.generated.resources.connector_dim_structured_usage
import kmpcyppieagents.app.shared.generated.resources.connector_dim_tool_granularity
import kmpcyppieagents.app.shared.generated.resources.connector_kind_mcp
import kmpcyppieagents.app.shared.generated.resources.connector_kind_stream_json
import kmpcyppieagents.app.shared.generated.resources.connector_panel_unknown
import kmpcyppieagents.app.shared.generated.resources.connector_status_available
import kmpcyppieagents.app.shared.generated.resources.connector_status_limited
import kmpcyppieagents.app.shared.generated.resources.connector_status_unavailable
import kmpcyppieagents.app.shared.generated.resources.connector_unknown
import org.jetbrains.compose.resources.stringResource

/**
 * Non-colour tri-state glyph (WCAG 1.4.1): distinct per status, non-emoji (CYP-54), reliable on Desktop-JVM.
 * Colour is never the sole carrier — the status **label** ([capabilityStatusLabel]) always accompanies it.
 */
fun capabilityGlyph(status: CapabilityStatus): String = when (status) {
    CapabilityStatus.AVAILABLE -> "✓"
    CapabilityStatus.LIMITED -> "~"
    CapabilityStatus.UNAVAILABLE -> "✕"
}

@Composable
fun connectorKindLabel(kind: ConnectorKind): String = when (kind) {
    ConnectorKind.STREAM_JSON -> stringResource(Res.string.connector_kind_stream_json)
    ConnectorKind.MCP -> stringResource(Res.string.connector_kind_mcp)
}

@Composable
fun capabilityStatusLabel(status: CapabilityStatus): String = when (status) {
    CapabilityStatus.AVAILABLE -> stringResource(Res.string.connector_status_available)
    CapabilityStatus.LIMITED -> stringResource(Res.string.connector_status_limited)
    CapabilityStatus.UNAVAILABLE -> stringResource(Res.string.connector_status_unavailable)
}

@Composable
fun capabilityDimensionLabel(dim: CapabilityDimension): String = when (dim) {
    CapabilityDimension.STRUCTURED_USAGE -> stringResource(Res.string.connector_dim_structured_usage)
    CapabilityDimension.TOOL_GRANULARITY -> stringResource(Res.string.connector_dim_tool_granularity)
    CapabilityDimension.RELIABLE_RESULT -> stringResource(Res.string.connector_dim_reliable_result)
    CapabilityDimension.RATE_LIMIT_SIGNAL -> stringResource(Res.string.connector_dim_rate_limit_signal)
    CapabilityDimension.COORDINATION -> stringResource(Res.string.connector_dim_coordination)
}

@Composable
private fun capabilityColor(status: CapabilityStatus): Color = when (status) {
    CapabilityStatus.AVAILABLE -> MaterialTheme.colorScheme.secondary
    CapabilityStatus.LIMITED -> MaterialTheme.colorScheme.tertiary
    CapabilityStatus.UNAVAILABLE -> MaterialTheme.colorScheme.error
}

/**
 * **Compact** per-agent connector badge (the header affordance; UIUX plans the CYP-55 badge-slot reuse in
 * CYP-119, so this stays a self-contained composable that can be dropped into that slot). Honest:
 *  - no info yet (loaded but absent) → an explicit "unknown" marker, **never** a green "available" (fail-closed);
 *  - degraded → connector kind + a degraded glyph+label (colour is not the sole carrier — WCAG 1.4.1).
 */
@Composable
fun ConnectorCapabilityBadge(info: AgentConnectorInfo?, agentId: String, modifier: Modifier = Modifier) {
    val a11y = stringResource(Res.string.a11y_connector_badge)
    if (info == null) {
        Text(
            text = stringResource(Res.string.connector_unknown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
                .testTag(ConnectorTags.badgeUnknown(agentId))
                .semantics { contentDescription = a11y },
        )
        return
    }
    val degraded = info.capabilities.isDegraded
    val degradedDesc = stringResource(Res.string.a11y_connector_degraded)
    Row(
        modifier = modifier.testTag(ConnectorTags.badge(agentId)).semantics { contentDescription = a11y },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(connectorKindLabel(info.kind), style = MaterialTheme.typography.bodySmall)
        if (degraded) {
            val worst = info.capabilities.overall()
            Text(
                text = "${capabilityGlyph(worst)} ${stringResource(Res.string.connector_degraded)}",
                color = capabilityColor(worst),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .testTag(ConnectorTags.badgeDegraded(agentId))
                    .semantics { contentDescription = degradedDesc },
            )
        }
    }
}

/**
 * **Detailed** per-agent capability panel: the connector kind, an amber degradation banner when any dimension
 * is reduced, then the five dimensions as a tri-state list (glyph + dimension label + status label). Fail-closed:
 * no info → an honest "not reported" line, never a table of faked rows.
 */
@Composable
fun CapabilityPanel(info: AgentConnectorInfo?, agentId: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().testTag(ConnectorTags.panel(agentId)),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (info == null) {
            Text(
                text = stringResource(Res.string.connector_panel_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(ConnectorTags.panelUnknown(agentId)),
            )
            return@Column
        }
        Text(connectorKindLabel(info.kind), style = MaterialTheme.typography.titleSmall)
        if (info.capabilities.isDegraded) {
            TonedHint(
                text = stringResource(Res.string.connector_degradation_hint),
                tone = HintTone.EFFECT_DEFERRED, // amber Attention — degradation is marked, not neutral grey
                tag = ConnectorTags.degradationHint(agentId),
            )
        }
        info.capabilities.dimensions.forEach { row -> CapabilityRow(agentId, row) }
    }
}

@Composable
private fun CapabilityRow(agentId: String, row: CapabilityDimension.Row) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(ConnectorTags.dimension(agentId, row.dimension)),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(capabilityGlyph(row.status), color = capabilityColor(row.status), style = MaterialTheme.typography.bodySmall)
        Text(capabilityDimensionLabel(row.dimension), style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth(0.62f))
        Text(capabilityStatusLabel(row.status), color = capabilityColor(row.status), style = MaterialTheme.typography.bodySmall)
    }
}
