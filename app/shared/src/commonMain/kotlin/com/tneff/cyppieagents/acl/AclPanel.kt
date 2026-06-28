package com.tneff.cyppieagents.acl

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.comm.ConnectionStatus
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.acl_change_failed
import kmpcyppieagents.app.shared.generated.resources.acl_conflict
import kmpcyppieagents.app.shared.generated.resources.acl_denied
import kmpcyppieagents.app.shared.generated.resources.acl_empty
import kmpcyppieagents.app.shared.generated.resources.acl_enforced
import kmpcyppieagents.app.shared.generated.resources.acl_granted
import kmpcyppieagents.app.shared.generated.resources.acl_non_member
import kmpcyppieagents.app.shared.generated.resources.acl_operator_required
import kmpcyppieagents.app.shared.generated.resources.acl_partial_view
import kmpcyppieagents.app.shared.generated.resources.acl_pending
import kmpcyppieagents.app.shared.generated.resources.acl_po_critical
import kmpcyppieagents.app.shared.generated.resources.acl_po_lockout_warning
import kmpcyppieagents.app.shared.generated.resources.acl_po_protected
import kmpcyppieagents.app.shared.generated.resources.acl_preset_applying
import kmpcyppieagents.app.shared.generated.resources.acl_preset_partial
import kmpcyppieagents.app.shared.generated.resources.acl_preset_preview
import kmpcyppieagents.app.shared.generated.resources.acl_preset_restore
import kmpcyppieagents.app.shared.generated.resources.acl_preset_restored
import kmpcyppieagents.app.shared.generated.resources.acl_read
import kmpcyppieagents.app.shared.generated.resources.acl_self_blind_warning
import kmpcyppieagents.app.shared.generated.resources.acl_unauthorized
import kmpcyppieagents.app.shared.generated.resources.acl_write
import kmpcyppieagents.app.shared.generated.resources.acl_write_only_hint
import kmpcyppieagents.app.shared.generated.resources.agent_role_po
import kmpcyppieagents.app.shared.generated.resources.comm_back
import kmpcyppieagents.app.shared.generated.resources.comm_status_offline
import org.jetbrains.compose.resources.stringResource

private val NARROW_BREAKPOINT = 600.dp
private val CHANNEL_COL_WIDTH = 150.dp
private val AGENT_COL_WIDTH = 190.dp

/**
 * ACL-matrix panel (CYP-48, design CYP-19): channel × participant grid of independent R/W grants, a
 * **live mirror of the enforced hub state**. Wide = grid; narrow = per-channel cards (reusing
 * `comm_back`). Disclosure-honest: pending ≠ enforced, non-member = N/A, conflict = deny-wins, the
 * PO-guardrail is advisory (server enforces, CYP-49). Renders the inner area only; window chrome is
 * the host's.
 */
@Composable
fun AclPanel(viewModel: AclViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    Column(modifier = modifier.fillMaxSize()) {
        if (!state.editable) Banner(stringResource(Res.string.acl_partial_view), AclMatrixTags.PARTIAL_VIEW)
        if (state.connection == ConnectionStatus.DISCONNECTED) {
            Banner(stringResource(Res.string.comm_status_offline), AclMatrixTags.CONNECTION)
        }
        state.notice?.let { Banner(noticeText(it), tag = null) }
        PresetBar(state, viewModel)

        if (state.channels.isEmpty() || state.agents.isEmpty()) {
            Text(
                text = stringResource(Res.string.acl_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp).testTag(AclMatrixTags.EMPTY),
            )
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                if (maxWidth < NARROW_BREAKPOINT) NarrowCards(state, viewModel) else WideGrid(state, viewModel)
            }
        }
    }

    state.lockoutPrompt?.let { LockoutDialog(it, onConfirm = viewModel::confirmLockout, onCancel = viewModel::cancelLockout) }
}

@Composable
private fun WideGrid(state: AclUiState, viewModel: AclViewModel) {
    val cells = remember(state.channels, state.agents, state.entries) {
        AclReducer.cells(state.channels, state.agents, state.entries)
    }
    Column(
        modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState())
            .verticalScroll(rememberScrollState()).testTag(AclMatrixTags.GRID),
    ) {
        Row {
            Box(Modifier.width(CHANNEL_COL_WIDTH))
            state.agents.forEach { agent ->
                Column(Modifier.width(AGENT_COL_WIDTH).padding(4.dp).testTag(AclMatrixTags.colHeader(agent.id))) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(agent.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        if (agent.role == com.tneff.cyppieagents.model.Role.PO) {
                            Text(stringResource(Res.string.agent_role_po), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (agent.id == "operator") Text("◆", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        state.channels.forEach { channel ->
            val rowCells = cells[channel.id].orEmpty()
            Row(Modifier.testTag(AclMatrixTags.rowHeader(channel.id)), verticalAlignment = Alignment.CenterVertically) {
                Text(channel.name, Modifier.width(CHANNEL_COL_WIDTH).padding(4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                rowCells.forEach { cell -> AclCellView(cell, state, viewModel, Modifier.width(AGENT_COL_WIDTH)) }
            }
        }
    }
}

@Composable
private fun NarrowCards(state: AclUiState, viewModel: AclViewModel) {
    var selected by remember { mutableStateOf<String?>(null) }
    val channel = state.channels.firstOrNull { it.id == selected }
    if (channel == null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(AclMatrixTags.GRID)) {
            state.channels.forEach { ch ->
                TextButton(onClick = { selected = ch.id }, modifier = Modifier.fillMaxWidth().testTag(AclMatrixTags.rowHeader(ch.id))) {
                    Text(ch.name, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    } else {
        val cells = remember(channel, state.agents, state.entries) {
            AclReducer.cells(listOf(channel), state.agents, state.entries)[channel.id].orEmpty()
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TextButton(onClick = { selected = null }) { Text("‹ " + stringResource(Res.string.comm_back)) }
            Text(channel.name, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.titleSmall)
            cells.forEach { cell ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.agents.first { it.id == cell.agentId }.name, Modifier.width(CHANNEL_COL_WIDTH))
                    AclCellView(cell, state, viewModel, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AclCellView(cell: AclCell, state: AclUiState, viewModel: AclViewModel, modifier: Modifier = Modifier) {
    val key = AclReducer.cellKey(cell.channelId, cell.agentId)
    val pending = key in state.pending
    val protectedNotice = state.cellNotice[key] == "acl_po_protected"
    Column(modifier = modifier.padding(4.dp).testTag(AclMatrixTags.cell(cell.channelId, cell.agentId))) {
        if (!cell.isMember) {
            Text(
                text = "—",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .testTag(AclMatrixTags.cellQualifier(cell.channelId, cell.agentId, CellQualifier.NON_MEMBER))
                    .semantics { contentDescription = "${cell.agentId}/${cell.channelId} non-member" },
            )
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            GrantControl(cell.channelId, cell.agentId, stringResource(Res.string.acl_read), cell.canRead, state.editable, pending,
                AclMatrixTags.read(cell.channelId, cell.agentId), AclMatrixTags.readonly(cell.channelId, cell.agentId)) {
                viewModel.toggleRead(cell.channelId, cell.agentId)
            }
            GrantControl(cell.channelId, cell.agentId, stringResource(Res.string.acl_write), cell.canWrite, state.editable, pending,
                AclMatrixTags.write(cell.channelId, cell.agentId), AclMatrixTags.readonly(cell.channelId, cell.agentId)) {
                viewModel.toggleWrite(cell.channelId, cell.agentId)
            }
            if (cell.poCritical) {
                Text("⚑", color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag(AclMatrixTags.cellQualifier(cell.channelId, cell.agentId, CellQualifier.PO_CRITICAL)))
            }
        }
        // Disclosure markers: pending ≠ enforced; protected (server-rejected) ≠ either.
        when {
            pending -> StateMarker(stringResource(Res.string.acl_pending), AclMatrixTags.cellQualifier(cell.channelId, cell.agentId, CellQualifier.PENDING))
            protectedNotice -> StateMarker(stringResource(Res.string.acl_po_protected), AclMatrixTags.cellQualifier(cell.channelId, cell.agentId, CellQualifier.PROTECTED))
            else -> Box(Modifier.testTag(AclMatrixTags.cellQualifier(cell.channelId, cell.agentId, CellQualifier.ENFORCED)))
        }
        if (cell.conflict) StateMarker(stringResource(Res.string.acl_conflict), AclMatrixTags.cellQualifier(cell.channelId, cell.agentId, CellQualifier.CONFLICT))
        if (cell.canWrite && !cell.canRead) Text(stringResource(Res.string.acl_write_only_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GrantControl(
    channelId: String, agentId: String, label: String, granted: Boolean, editable: Boolean, pending: Boolean,
    switchTag: String, readonlyTag: String, onToggle: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        if (editable) {
            Switch(checked = granted, onCheckedChange = { onToggle() }, enabled = !pending, modifier = Modifier.testTag(switchTag))
        } else {
            // Read-only chip (no switch that fakes editability) — honest partial/agent view (CYP-19 §8).
            Text(
                text = stringResource(if (granted) Res.string.acl_granted else Res.string.acl_denied),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.testTag(readonlyTag),
            )
        }
    }
}

@Composable
private fun PresetBar(state: AclUiState, viewModel: AclViewModel) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.editable) {
            TextButton(onClick = viewModel::previewPreset, modifier = Modifier.testTag(AclMatrixTags.PRESET_RESTORE)) {
                Text(stringResource(Res.string.acl_preset_restore))
            }
        }
        when (state.preset?.phase) {
            PresetPhase.PREVIEW -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.acl_preset_preview, state.preset.total.toString()), modifier = Modifier.testTag(AclMatrixTags.PRESET_PREVIEW))
                // NOTE: preset apply/cancel have no dedicated tag in acl-matrix-tags.md yet; a preset
                // device-flow is out of this S7-gate's scope (advisory toggle + 409 path are the criticals).
                TextButton(onClick = viewModel::applyPreset) { Text("✓") }
                TextButton(onClick = viewModel::cancelPreset) { Text("✕") }
            }
            PresetPhase.APPLYING -> Text(
                stringResource(Res.string.acl_preset_applying, state.preset.done.toString(), state.preset.total.toString()),
                modifier = Modifier.testTag(AclMatrixTags.PRESET_PROGRESS),
            )
            PresetPhase.PARTIAL -> Text(
                stringResource(Res.string.acl_preset_partial, state.preset.done.toString(), state.preset.total.toString(), state.preset.failed.toString()),
                color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(AclMatrixTags.PRESET_PARTIAL),
            )
            PresetPhase.RESTORED -> Text(stringResource(Res.string.acl_preset_restored))
            null -> Unit
        }
    }
}

@Composable
private fun LockoutDialog(prompt: LockoutPrompt, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val text = if (prompt.selfBlind) stringResource(Res.string.acl_self_blind_warning, prompt.channelName)
    else stringResource(Res.string.acl_po_lockout_warning, prompt.channelName)
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag(if (prompt.selfBlind) AclMatrixTags.SELF_BLIND_WARNING else AclMatrixTags.LOCKOUT_DIALOG),
        title = { Text(stringResource(Res.string.acl_po_critical)) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag(AclMatrixTags.LOCKOUT_DIALOG_CONFIRM)) { Text("OK") } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.testTag(AclMatrixTags.LOCKOUT_DIALOG_CANCEL)) { Text(stringResource(Res.string.comm_back)) } },
    )
}

@Composable
private fun Banner(text: String, tag: String?) {
    var m = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 12.dp, vertical = 4.dp)
    if (tag != null) m = m.testTag(tag)
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = m)
}

@Composable
private fun StateMarker(text: String, tag: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(tag))
}

@Composable
private fun noticeText(key: String): String = when (key) {
    "acl_operator_required" -> stringResource(Res.string.acl_operator_required)
    "acl_unauthorized" -> stringResource(Res.string.acl_unauthorized)
    else -> stringResource(Res.string.acl_change_failed)
}
