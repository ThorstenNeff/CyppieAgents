package com.tneff.cyppieagents.connector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.agent_edit_effect_hint
import kmpcyppieagents.app.shared.generated.resources.connector_default_note
import kmpcyppieagents.app.shared.generated.resources.connector_dim_coordination
import kmpcyppieagents.app.shared.generated.resources.connector_dim_rate_limit_signal
import kmpcyppieagents.app.shared.generated.resources.connector_dim_reliable_result
import kmpcyppieagents.app.shared.generated.resources.connector_dim_structured_usage
import kmpcyppieagents.app.shared.generated.resources.connector_dim_tool_granularity
import kmpcyppieagents.app.shared.generated.resources.connector_dim_unknown
import kmpcyppieagents.app.shared.generated.resources.connector_kind_mcp
import kmpcyppieagents.app.shared.generated.resources.connector_kind_stream_json
import kmpcyppieagents.app.shared.generated.resources.connector_optin_ack
import kmpcyppieagents.app.shared.generated.resources.connector_optin_cancel
import kmpcyppieagents.app.shared.generated.resources.connector_optin_confirm
import kmpcyppieagents.app.shared.generated.resources.connector_optin_error
import kmpcyppieagents.app.shared.generated.resources.connector_optin_human_only
import kmpcyppieagents.app.shared.generated.resources.connector_optin_intro
import kmpcyppieagents.app.shared.generated.resources.connector_optin_preview_title
import kmpcyppieagents.app.shared.generated.resources.connector_optin_risk_account
import kmpcyppieagents.app.shared.generated.resources.connector_optin_risk_bypass
import kmpcyppieagents.app.shared.generated.resources.connector_optin_risk_fragile
import kmpcyppieagents.app.shared.generated.resources.connector_optin_title
import kmpcyppieagents.app.shared.generated.resources.connector_picker_label
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-123 (spec §3) — the connector **picker** + the **B opt-in dialog**, host-anchored inside the already
 * operator-gated agent-config dialogs (the picker inherits that gate — no second gate, §3.3). Mirrors the
 * CYP-86 `RolePicker`/`RadioRow` shape for the picker and the CYP-93 `AuthorizeDialog`/`ownerConsent` shape
 * for the deliberate, ack-gated opt-in.
 *
 * Security (spec §3.2/§3.3/§5, load-bearing): **A is the first-class default and pre-selected; B is never
 * pre-selected.** Selecting B does not switch the connector — it opens the opt-in dialog, and B activates
 * **only** via the named confirm, which is enabled solely when [ConnectorSelectionUiState.canConfirm]
 * (operator-gated AND acknowledged). The human-only note makes the anti-injection invariant visible.
 */
@Composable
fun ConnectorPicker(
    viewModel: ConnectorSelectionViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = modifier.fillMaxWidth().testTag(ConnectorTags.PICKER),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(Res.string.connector_picker_label), style = MaterialTheme.typography.labelMedium)

        // Option A (stream-json) — the first-class default, pre-selected (draftKind starts STREAM_JSON).
        ConnectorRadioRow(
            selected = state.draftKind == ConnectorKind.STREAM_JSON,
            onClick = { viewModel.selectKind(ConnectorKind.STREAM_JSON) },
            label = stringResource(Res.string.connector_kind_stream_json),
            tag = ConnectorTags.pickerOption(ConnectorKind.STREAM_JSON),
        )
        // Option B (MCP) — fail-closed, NEVER pre-selected; selecting it opens the deliberate opt-in dialog.
        ConnectorRadioRow(
            selected = state.draftKind == ConnectorKind.MCP,
            onClick = { viewModel.selectKind(ConnectorKind.MCP) },
            label = stringResource(Res.string.connector_kind_mcp),
            tag = ConnectorTags.pickerOption(ConnectorKind.MCP),
        )

        // Make the fail-closed default explicit: A first-class, B an opt-in alternative.
        TonedHint(stringResource(Res.string.connector_default_note), HintTone.INFO, "${ConnectorTags.PICKER}.defaultNote")

        // "Saved ≠ active" — the picker is INTENT, never a claim that B is ACTIVE (the active connector is the
        // server-truth capability display only). The VM derives [ConnectorSelectionUiState.showEffectHint] =
        // a change from the agent's current connector in EDIT context (a fresh add carries the kind in
        // NewAgentSpec → no restart, no hint). So changing an existing agent's connector reads as "saved ≠
        // active — restart to apply" (reused CYP-88 amber hint), never "B is now live" (spec §3.4; CYP-126).
        if (state.showEffectHint) {
            TonedHint(stringResource(Res.string.agent_edit_effect_hint), HintTone.EFFECT_DEFERRED, "${ConnectorTags.PICKER}.effectHint")
        }
    }

    if (state.optInDialogOpen) OptInDialog(state, viewModel)
}

@Composable
private fun ConnectorRadioRow(selected: Boolean, onClick: () -> Unit, label: String, tag: String) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Tag the RadioButton itself so its selected state is directly assertable (B-never-preselected, §5.4).
        RadioButton(selected = selected, onClick = onClick, modifier = Modifier.testTag(tag))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The B opt-in dialog (spec §3.2): intro, three amber risk-disclosure lines (EFFECT_DEFERRED Attention — a
 * hazard disclosure, **not** ERROR-red), the §2.3 capability preview for connector B (what B trades away,
 * shown BEFORE the act), the deliberate acknowledgment row, the load-bearing human-only note, and the named
 * confirm (enabled only when [ConnectorSelectionUiState.canConfirm]).
 */
@Composable
private fun OptInDialog(state: ConnectorSelectionUiState, viewModel: ConnectorSelectionViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::cancelOptIn,
        confirmButton = {
            Button(
                onClick = viewModel::confirmOptIn,
                enabled = state.canConfirm,
                modifier = Modifier.testTag(ConnectorTags.OPTIN_CONFIRM),
            ) { Text(stringResource(Res.string.connector_optin_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::cancelOptIn, modifier = Modifier.testTag(ConnectorTags.OPTIN_CANCEL)) {
                Text(stringResource(Res.string.connector_optin_cancel))
            }
        },
        title = { Text(stringResource(Res.string.connector_optin_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().testTag(ConnectorTags.OPTIN_DIALOG),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 1. Intro — what B is (opt-in alternative, lower fidelity, stated risk).
                Text(stringResource(Res.string.connector_optin_intro), style = MaterialTheme.typography.bodyMedium)

                // 2. Three risk lines — amber Attention (EFFECT_DEFERRED), NOT ERROR-red: a hazard disclosure,
                //    not an app error (spec §3.2/§5.3).
                TonedHint(stringResource(Res.string.connector_optin_risk_bypass), HintTone.EFFECT_DEFERRED, ConnectorTags.OPTIN_RISK_BYPASS)
                TonedHint(stringResource(Res.string.connector_optin_risk_account), HintTone.EFFECT_DEFERRED, ConnectorTags.OPTIN_RISK_ACCOUNT)
                TonedHint(stringResource(Res.string.connector_optin_risk_fragile), HintTone.EFFECT_DEFERRED, ConnectorTags.OPTIN_RISK_FRAGILE)

                // 3. Capability preview for connector B — the honest "what this reduces" table, BEFORE the act.
                Text(stringResource(Res.string.connector_optin_preview_title), style = MaterialTheme.typography.titleSmall)
                Column(
                    modifier = Modifier.fillMaxWidth().testTag(ConnectorTags.OPTIN_PREVIEW),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    defaultCapabilitiesFor(ConnectorKind.MCP).rows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = previewDimensionLabel(row.dimension),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth(0.55f),
                            )
                            CapabilityStatusChip(row.status, CAPABILITY_PREVIEW_SCOPE, row.dimension)
                        }
                    }
                }

                // 4. Deliberate acknowledgment — the whole row toggles it (hit-area), mirrors ownerConsent.
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { viewModel.setRiskAcknowledged(!state.riskAcknowledged) }
                        .testTag(ConnectorTags.OPTIN_ACK),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = state.riskAcknowledged, onCheckedChange = null)
                    Text(stringResource(Res.string.connector_optin_ack), style = MaterialTheme.typography.bodyMedium)
                }

                // 5. Anti-injection invariant made visible (§3.3/§5, load-bearing, verbatim — INFO tone).
                TonedHint(stringResource(Res.string.connector_optin_human_only), HintTone.INFO, ConnectorTags.OPTIN_HUMAN_ONLY)

                // 6. Error line (real failure → ERROR-red, distinct from the hazard disclosure).
                if (state.error != null) {
                    TonedHint(stringResource(Res.string.connector_optin_error), HintTone.ERROR, ConnectorTags.OPTIN_ERROR)
                }
            }
        },
    )
}

/** Dimension labels for the opt-in preview, reusing the panel's existing dimension keys (no new keys). */
@Composable
private fun previewDimensionLabel(dim: CapabilityDimension): String = when (dim) {
    CapabilityDimension.STRUCTURED_USAGE -> stringResource(Res.string.connector_dim_structured_usage)
    CapabilityDimension.TOOL_GRANULARITY -> stringResource(Res.string.connector_dim_tool_granularity)
    CapabilityDimension.RELIABLE_RESULT -> stringResource(Res.string.connector_dim_reliable_result)
    CapabilityDimension.RATE_LIMIT_SIGNAL -> stringResource(Res.string.connector_dim_rate_limit_signal)
    CapabilityDimension.COORDINATION -> stringResource(Res.string.connector_dim_coordination)
    CapabilityDimension.UNKNOWN -> stringResource(Res.string.connector_dim_unknown)
}
