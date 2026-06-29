package com.tneff.cyppieagents.connector

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.connector_cancel
import kmpcyppieagents.app.shared.generated.resources.connector_confirm
import kmpcyppieagents.app.shared.generated.resources.connector_error
import kmpcyppieagents.app.shared.generated.resources.connector_gate_hint
import kmpcyppieagents.app.shared.generated.resources.connector_human_only
import kmpcyppieagents.app.shared.generated.resources.connector_operator_required
import kmpcyppieagents.app.shared.generated.resources.connector_risk_ack
import kmpcyppieagents.app.shared.generated.resources.connector_risk_note
import kmpcyppieagents.app.shared.generated.resources.connector_risk_required
import kmpcyppieagents.app.shared.generated.resources.connector_select_button
import kmpcyppieagents.app.shared.generated.resources.connector_select_current
import kmpcyppieagents.app.shared.generated.resources.connector_select_title
import org.jetbrains.compose.resources.stringResource

/**
 * Operator-gated trigger for the connector-selection dialog (CYP-123). When the operator token is present it
 * is the change affordance; otherwise it shows a **visible gate hint** (why the connector can't be changed)
 * rather than silently vanishing — the guardrail is visible before the action.
 */
@Composable
fun ConnectorSelectButton(agentId: String, viewModel: ConnectorViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    if (state.editable) {
        TextButton(
            onClick = { viewModel.openSelect(agentId) },
            modifier = modifier.testTag(ConnectorTags.selectButton(agentId)),
        ) { Text(stringResource(Res.string.connector_select_button)) }
    } else {
        TonedHint(
            text = stringResource(Res.string.connector_gate_hint),
            tone = HintTone.GATED,
            tag = ConnectorTags.GATE_HINT,
            modifier = modifier,
        )
    }
}

/**
 * The connector-selection dialog (CYP-123) — a **deliberate operator act**, mirroring the CYP-93 consent gate.
 * Connector A (stream-json) is the plain choice; selecting B (MCP) reveals the risk note (Doc 10 §5), an
 * anti-injection "only you choose — never an agent or a message" note, and a risk-acknowledgment checkbox that
 * gates Confirm (`canConfirm = editable && ack-for-B`). There is NO affordance to pick a connector from a
 * message/agent request. The gate is **also** server-side (authoritative + audited); this UI is the affordance.
 */
@Composable
fun ConnectorSelectorDialog(viewModel: ConnectorViewModel) {
    val state by viewModel.state.collectAsState()
    val agentId = state.dialogAgentId ?: return
    AlertDialog(
        onDismissRequest = { viewModel.closeDialog() },
        modifier = Modifier.testTag(ConnectorTags.SELECT_DIALOG),
        title = { Text(stringResource(Res.string.connector_select_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.currentKind(agentId)?.let { cur ->
                    Text(
                        text = stringResource(Res.string.connector_select_current, connectorKindLabel(cur)),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(ConnectorTags.CURRENT),
                    )
                }
                ConnectorOptionRow(
                    selected = state.draftKind == ConnectorKind.STREAM_JSON,
                    label = connectorKindLabel(ConnectorKind.STREAM_JSON),
                    onSelect = { viewModel.setKind(ConnectorKind.STREAM_JSON) },
                    tag = ConnectorTags.OPTION_STREAM_JSON,
                )
                ConnectorOptionRow(
                    selected = state.draftKind == ConnectorKind.MCP,
                    label = connectorKindLabel(ConnectorKind.MCP),
                    onSelect = { viewModel.setKind(ConnectorKind.MCP) },
                    tag = ConnectorTags.OPTION_MCP,
                )
                if (state.needsAcknowledgment) {
                    TonedHint(stringResource(Res.string.connector_risk_note), HintTone.ERROR, ConnectorTags.RISK_NOTE)
                    TonedHint(stringResource(Res.string.connector_human_only), HintTone.INFO, ConnectorTags.HUMAN_ONLY)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setRiskAcknowledged(!state.riskAcknowledged) }
                            .testTag(ConnectorTags.RISK_ACK),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = state.riskAcknowledged, onCheckedChange = { viewModel.setRiskAcknowledged(it) })
                        Text(stringResource(Res.string.connector_risk_ack), style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.error?.let { key -> TonedHint(connectorErrorText(key), HintTone.ERROR, ConnectorTags.ERROR) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.confirmSelection() },
                enabled = state.canConfirm,
                modifier = Modifier.testTag(ConnectorTags.CONFIRM),
            ) { Text(stringResource(Res.string.connector_confirm)) }
        },
        dismissButton = {
            TextButton(
                onClick = { viewModel.closeDialog() },
                modifier = Modifier.testTag(ConnectorTags.CANCEL),
            ) { Text(stringResource(Res.string.connector_cancel)) }
        },
    )
}

@Composable
private fun ConnectorOptionRow(selected: Boolean, label: String, onSelect: () -> Unit, tag: String) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/** Maps a VM error key to its localized text (the VM emits stable keys; the composable resolves them). */
@Composable
private fun connectorErrorText(key: String): String = when (key) {
    "connector_operator_required" -> stringResource(Res.string.connector_operator_required)
    "connector_risk_required" -> stringResource(Res.string.connector_risk_required)
    else -> stringResource(Res.string.connector_error)
}
