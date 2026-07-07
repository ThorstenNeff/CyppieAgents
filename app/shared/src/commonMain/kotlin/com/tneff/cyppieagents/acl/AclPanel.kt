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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.comm.ConnectionStatus
import com.tneff.cyppieagents.ui.LoadErrorRetry
import com.tneff.cyppieagents.window.PANE_COLLAPSE_WIDTH
import com.tneff.cyppieagents.model.WorkspaceMember
import com.tneff.cyppieagents.testing.enableTestTagsAsResourceId
import com.tneff.cyppieagents.testing.testTagA11y
import com.tneff.cyppieagents.workspace.memberLabel
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_acl_human_subject
import kmpcyppieagents.app.shared.generated.resources.acl_agents_group
import kmpcyppieagents.app.shared.generated.resources.acl_channel_gone
import kmpcyppieagents.app.shared.generated.resources.acl_human
import kmpcyppieagents.app.shared.generated.resources.acl_humans_group
import kmpcyppieagents.app.shared.generated.resources.a11y_acl_cell_nonmember
import kmpcyppieagents.app.shared.generated.resources.a11y_acl_pending
import kmpcyppieagents.app.shared.generated.resources.a11y_acl_po_critical
import kmpcyppieagents.app.shared.generated.resources.a11y_acl_toggle_read
import kmpcyppieagents.app.shared.generated.resources.a11y_acl_toggle_write
import kmpcyppieagents.app.shared.generated.resources.acl_cancel
import kmpcyppieagents.app.shared.generated.resources.acl_change_failed
import kmpcyppieagents.app.shared.generated.resources.acl_continue
import kmpcyppieagents.app.shared.generated.resources.acl_conflict
import kmpcyppieagents.app.shared.generated.resources.acl_denied
import kmpcyppieagents.app.shared.generated.resources.acl_empty
import kmpcyppieagents.app.shared.generated.resources.load_failed
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

// CYP-156: migrated to the shared PANE_COLLAPSE_WIDTH (value unchanged at 600.dp). Was a local
// NARROW_BREAKPOINT; now one token drives comm/report/acl collapse.
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
        // Partial view = INFO (read-only), not an error → neutral/info tone (A3).
        if (!state.editable) {
            Banner(stringResource(Res.string.acl_partial_view), AclMatrixTags.PARTIAL_VIEW,
                MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        }
        // Offline/stale = amber warning, not error-red (CYP-17 semantics) → tertiary container (A3).
        if (state.connection == ConnectionStatus.DISCONNECTED) {
            Banner(stringResource(Res.string.comm_status_offline), AclMatrixTags.CONNECTION,
                MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        }
        // Actual error (operator-required / unauthorized / change-failed) → error tone.
        state.notice?.let {
            Banner(noticeText(it), tag = null, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        }
        PresetBar(state, viewModel)

        // CYP-276 (CYP-270 class): gate the "no data" message on !loading so it never FLASHES during the async
        // load window (cold open / project switch) before the ACL matrix arrives — only a settled-empty shows it.
        if (state.loadError) {
            // CYP-288: a failed non-gated matrix load previously rendered as the "empty matrix" state
            // (failure-as-empty, Sweep-#4 class A). Error beats empty; Retry re-runs the load.
            LoadErrorRetry(
                message = stringResource(Res.string.load_failed),
                onRetry = viewModel::retryLoad,
                containerTag = AclMatrixTags.ERROR,
                retryTag = AclMatrixTags.ERROR_RETRY,
            )
        } else if (!state.loading && (state.channels.isEmpty() || state.agents.isEmpty())) {
            Text(
                text = stringResource(Res.string.acl_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp).testTag(AclMatrixTags.EMPTY),
            )
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                if (maxWidth < PANE_COLLAPSE_WIDTH) NarrowCards(state, viewModel) else WideGrid(state, viewModel)
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
    // CYP-189 Invariante E: human subjects render ONLY for the operator. `state.members` is already empty for a
    // non-operator (never fetched); the `editable` guard is the structural defense-in-depth — the human band's
    // nodes are NEVER built into a non-operator tree (no roster leak through the matrix).
    val showHumans = state.editable && state.members.isNotEmpty()
    val humanCells = remember(state.channels, state.members, state.entries, showHumans) {
        if (showHumans) AclReducer.humanCells(state.channels, state.members, state.entries) else emptyMap()
    }
    Column(
        modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState())
            .verticalScroll(rememberScrollState()).testTagA11y(AclMatrixTags.GRID),
    ) {
        // Subject-band group heads (§2): Agents always; Humans operator-only. Each spans its columns' width.
        Row {
            Box(Modifier.width(CHANNEL_COL_WIDTH))
            Text(
                stringResource(Res.string.acl_agents_group),
                Modifier.width(AGENT_COL_WIDTH * state.agents.size).padding(horizontal = 4.dp).testTag(AclMatrixTags.AGENTS_GROUP),
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showHumans) Text(
                stringResource(Res.string.acl_humans_group),
                Modifier.width(AGENT_COL_WIDTH * state.members.size).padding(horizontal = 4.dp).testTag(AclMatrixTags.HUMANS_GROUP),
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row {
            Box(Modifier.width(CHANNEL_COL_WIDTH))
            state.agents.forEach { agent ->
                Column(Modifier.width(AGENT_COL_WIDTH).padding(4.dp).testTag(AclMatrixTags.colHeader(agent.id))) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(agent.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        if (agent.role == com.tneff.cyppieagents.model.Role.PO) {
                            Text(stringResource(Res.string.agent_role_po), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (agent.id == "operator") {
                            Text("◆", style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.semantics { contentDescription = "Operator" })
                        }
                    }
                }
            }
            if (showHumans) state.members.forEach { member -> HumanColHeader(member) }
        }
        state.channels.forEach { channel ->
            val rowCells = cells[channel.id].orEmpty()
            Row(Modifier.testTag(AclMatrixTags.rowHeader(channel.id)), verticalAlignment = Alignment.CenterVertically) {
                Text(channel.name, Modifier.width(CHANNEL_COL_WIDTH).padding(4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                rowCells.forEach { cell -> AclCellView(cell, state, viewModel, Modifier.width(AGENT_COL_WIDTH)) }
                if (showHumans) humanCells[channel.id].orEmpty().forEach { cell -> AclCellView(cell, state, viewModel, Modifier.width(AGENT_COL_WIDTH)) }
            }
        }
    }
}

/** A human subject's column head: neutral [memberLabel] + the text `acl_human` marker (§2, never colour-only). */
@Composable
private fun HumanColHeader(member: WorkspaceMember) {
    val label = memberLabel(member)
    val a11y = stringResource(Res.string.a11y_acl_human_subject, label)
    Column(Modifier.width(AGENT_COL_WIDTH).padding(4.dp).testTag(AclMatrixTags.colHeader(member.identityId))) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { contentDescription = a11y })
            // The "Mensch" text is DECORATIVE here — the label's contentDescription already announces "Mensch
            // <label>", so the screen-reader must not read "Mensch" a second time. The testTag stays on the Box (QA
            // asserts the Human-vs-Agent marker); clearAndSetSemantics on the inner text drops it from a11y.
            Box(Modifier.testTag(AclMatrixTags.humanMarker(member.identityId))) {
                Text(stringResource(Res.string.acl_human), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics { })
            }
        }
    }
}

@Composable
private fun NarrowCards(state: AclUiState, viewModel: AclViewModel) {
    // CYP-283: key the selection on the VM instance so it RESETS on a project switch. The shell re-keys aclVm
    // per activeProjectId (CYP-246), so a new VM = a new project; without this key the position-bound selection
    // survives the switch and — because hub-and-spoke seeds same-id channels (po-<worker>) per project — silently
    // maps to the WRONG channel's ACL card (an access-context correctness risk), self-healing only on next tap.
    var selected by remember(viewModel) { mutableStateOf<String?>(null) }
    val channel = state.channels.firstOrNull { it.id == selected }
    if (channel == null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTagA11y(AclMatrixTags.GRID)) {
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
        // CYP-189 Invariante E: human rows operator-only (see WideGrid).
        val showHumans = state.editable && state.members.isNotEmpty()
        val humanCells = remember(channel, state.members, state.entries, showHumans) {
            if (showHumans) AclReducer.humanCells(listOf(channel), state.members, state.entries)[channel.id].orEmpty() else emptyList()
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
            // Human subject rows: no column grouping here, so the inline acl_human marker per row is the
            // load-bearing Human-vs-Agent signal (§6). Label = neutral memberLabel, never the raw identityId.
            humanCells.forEach { cell ->
                val member = state.members.first { it.identityId == cell.agentId }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.width(CHANNEL_COL_WIDTH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(memberLabel(member))
                        Text(stringResource(Res.string.acl_human), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(AclMatrixTags.humanMarker(member.identityId)))
                    }
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
    // Display names for screenreader labels (A1) — never the raw ids. CYP-189: a human subject resolves to its
    // neutral memberLabel (displayName ?? shortId), NEVER the raw identityId (§4 no-secrets).
    val subjectName = state.agents.firstOrNull { it.id == cell.agentId }?.name
        ?: state.members.firstOrNull { it.identityId == cell.agentId }?.let { memberLabel(it) }
        ?: cell.agentId
    val channelName = state.channels.firstOrNull { it.id == cell.channelId }?.name ?: cell.channelId
    Column(modifier = modifier.padding(4.dp).testTag(AclMatrixTags.cell(cell.channelId, cell.agentId))) {
        if (!cell.isMember) {
            val nonMemberCd = stringResource(Res.string.a11y_acl_cell_nonmember, subjectName, channelName)
            Text(
                text = "—",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .testTag(AclMatrixTags.cellQualifier(cell.channelId, cell.agentId, CellQualifier.NON_MEMBER))
                    .semantics { contentDescription = nonMemberCd },
            )
            return@Column
        }
        val readCd = stringResource(Res.string.a11y_acl_toggle_read, subjectName, channelName)
        val writeCd = stringResource(Res.string.a11y_acl_toggle_write, subjectName, channelName)
        val poCriticalCd = stringResource(Res.string.a11y_acl_po_critical)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            GrantControl(stringResource(Res.string.acl_read), cell.canRead, state.editable, pending,
                AclMatrixTags.read(cell.channelId, cell.agentId), AclMatrixTags.readonly(cell.channelId, cell.agentId), readCd) {
                viewModel.toggleRead(cell.channelId, cell.agentId)
            }
            GrantControl(stringResource(Res.string.acl_write), cell.canWrite, state.editable, pending,
                AclMatrixTags.write(cell.channelId, cell.agentId), AclMatrixTags.readonly(cell.channelId, cell.agentId), writeCd) {
                viewModel.toggleWrite(cell.channelId, cell.agentId)
            }
            if (cell.poCritical) {
                Text("⚑", color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .testTag(AclMatrixTags.cellQualifier(cell.channelId, cell.agentId, CellQualifier.PO_CRITICAL))
                        .semantics { contentDescription = poCriticalCd })
            }
        }
        // Disclosure markers: pending ≠ enforced; protected (server-rejected) ≠ either.
        when {
            pending -> StateMarker(stringResource(Res.string.acl_pending), AclMatrixTags.cellQualifier(cell.channelId, cell.agentId, CellQualifier.PENDING), stringResource(Res.string.a11y_acl_pending))
            protectedNotice -> StateMarker(stringResource(Res.string.acl_po_protected), AclMatrixTags.cellQualifier(cell.channelId, cell.agentId, CellQualifier.PROTECTED))
            // Enforced = the settled hub state. A real-size (not zero-size) node so it surfaces in the
            // merged tree / on-device (F1) — visually negligible, carries the acl_enforced a11y (§8).
            else -> {
                val enforcedCd = stringResource(Res.string.acl_enforced)
                Box(
                    Modifier.size(8.dp)
                        .testTag(AclMatrixTags.cellQualifier(cell.channelId, cell.agentId, CellQualifier.ENFORCED))
                        .semantics { contentDescription = enforcedCd },
                )
            }
        }
        if (cell.conflict) StateMarker(stringResource(Res.string.acl_conflict), AclMatrixTags.cellQualifier(cell.channelId, cell.agentId, CellQualifier.CONFLICT))
        if (cell.canWrite && !cell.canRead) Text(stringResource(Res.string.acl_write_only_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GrantControl(
    label: String, granted: Boolean, editable: Boolean, pending: Boolean,
    switchTag: String, readonlyTag: String, contentDescription: String, onToggle: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        if (editable) {
            Switch(
                checked = granted,
                onCheckedChange = { onToggle() },
                enabled = !pending,
                // Glyph in the thumb so the on/off state isn't carried by colour alone (B2 / WCAG 1.4.1).
                thumbContent = { Text(if (granted) "✓" else "✕", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.testTag(switchTag).semantics { this.contentDescription = contentDescription },
            )
        } else {
            // Read-only chip (no switch that fakes editability) — honest partial/agent view (CYP-19 §8).
            Text(
                text = stringResource(if (granted) Res.string.acl_granted else Res.string.acl_denied),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.testTag(readonlyTag).semantics { this.contentDescription = contentDescription },
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
                TextButton(onClick = viewModel::applyPreset, modifier = Modifier.testTag(AclMatrixTags.PRESET_PREVIEW_CONFIRM)) { Text(stringResource(Res.string.acl_continue)) }
                TextButton(onClick = viewModel::cancelPreset, modifier = Modifier.testTag(AclMatrixTags.PRESET_PREVIEW_CANCEL)) { Text(stringResource(Res.string.acl_cancel)) }
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
        // The dialog renders in its OWN Compose window, which does NOT inherit the root's
        // enableTestTagsAsResourceId() → re-apply it here so the dialog/confirm/cancel testTags resolve
        // as Android resource-ids for Maestro (F2). No-op on non-Android targets.
        modifier = Modifier.enableTestTagsAsResourceId()
            .testTag(if (prompt.selfBlind) AclMatrixTags.SELF_BLIND_WARNING else AclMatrixTags.LOCKOUT_DIALOG),
        title = { Text(stringResource(Res.string.acl_po_critical)) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag(AclMatrixTags.LOCKOUT_DIALOG_CONFIRM)) { Text(stringResource(Res.string.acl_continue)) } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.testTag(AclMatrixTags.LOCKOUT_DIALOG_CANCEL)) { Text(stringResource(Res.string.acl_cancel)) } },
    )
}

@Composable
private fun Banner(text: String, tag: String?, container: Color, onContainer: Color) {
    var m = Modifier.fillMaxWidth().background(container).padding(horizontal = 12.dp, vertical = 4.dp)
    if (tag != null) m = m.testTag(tag)
    Text(text, style = MaterialTheme.typography.labelSmall, color = onContainer, modifier = m)
}

@Composable
private fun StateMarker(text: String, tag: String, a11y: String? = null) {
    var m = Modifier.testTag(tag)
    if (a11y != null) m = m.semantics { contentDescription = a11y }
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = m)
}

@Composable
private fun noticeText(key: String): String = when (key) {
    "acl_operator_required" -> stringResource(Res.string.acl_operator_required)
    "acl_unauthorized" -> stringResource(Res.string.acl_unauthorized)
    // CYP-189 §5: ghost-channel 404 — honest, non-retryable (distinct from the retryable acl_change_failed).
    "acl_channel_gone" -> stringResource(Res.string.acl_channel_gone)
    else -> stringResource(Res.string.acl_change_failed)
}
