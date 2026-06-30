package com.tneff.cyppieagents.agentmgmt
import com.tneff.cyppieagents.model.WorktreeFate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.agentview.AgentViewTags
import com.tneff.cyppieagents.window.PANE_COLLAPSE_WIDTH
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.Role
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_agent_add_id
import kmpcyppieagents.app.shared.generated.resources.a11y_agent_add_persona
import kmpcyppieagents.app.shared.generated.resources.agent_add
import kmpcyppieagents.app.shared.generated.resources.agent_add_confirm
import kmpcyppieagents.app.shared.generated.resources.agent_add_error
import kmpcyppieagents.app.shared.generated.resources.agent_add_id_exists
import kmpcyppieagents.app.shared.generated.resources.agent_add_id_label
import kmpcyppieagents.app.shared.generated.resources.agent_add_launch_label
import kmpcyppieagents.app.shared.generated.resources.agent_add_name_label
import kmpcyppieagents.app.shared.generated.resources.agent_add_persona_label
import kmpcyppieagents.app.shared.generated.resources.agent_add_po_exists
import kmpcyppieagents.app.shared.generated.resources.agent_add_role_label
import kmpcyppieagents.app.shared.generated.resources.agent_add_spawn_hint
import kmpcyppieagents.app.shared.generated.resources.agent_add_worktree_label
import kmpcyppieagents.app.shared.generated.resources.agent_cancel
import kmpcyppieagents.app.shared.generated.resources.agent_edit
import kmpcyppieagents.app.shared.generated.resources.agent_edit_effect_hint
import kmpcyppieagents.app.shared.generated.resources.agent_edit_error
import kmpcyppieagents.app.shared.generated.resources.agent_edit_id_locked_hint
import kmpcyppieagents.app.shared.generated.resources.agent_edit_last_po
import kmpcyppieagents.app.shared.generated.resources.agent_edit_po_exists
import kmpcyppieagents.app.shared.generated.resources.agent_edit_title
import kmpcyppieagents.app.shared.generated.resources.agent_mgmt_operator_required
import kmpcyppieagents.app.shared.generated.resources.agent_remove
import kmpcyppieagents.app.shared.generated.resources.agent_remove_confirm
import kmpcyppieagents.app.shared.generated.resources.agent_remove_confirm_delete
import kmpcyppieagents.app.shared.generated.resources.agent_remove_consequences
import kmpcyppieagents.app.shared.generated.resources.agent_remove_error
import kmpcyppieagents.app.shared.generated.resources.agent_remove_last_po
import kmpcyppieagents.app.shared.generated.resources.agent_remove_title
import kmpcyppieagents.app.shared.generated.resources.agent_remove_worktree_delete
import kmpcyppieagents.app.shared.generated.resources.agent_remove_worktree_keep
import kmpcyppieagents.app.shared.generated.resources.agent_remove_worktree_warning
import kmpcyppieagents.app.shared.generated.resources.agent_role_po
import kmpcyppieagents.app.shared.generated.resources.agent_role_product_lead
import kmpcyppieagents.app.shared.generated.resources.agent_role_worker
import kmpcyppieagents.app.shared.generated.resources.agent_save
import kmpcyppieagents.app.shared.generated.resources.agent_status_error
import kmpcyppieagents.app.shared.generated.resources.agent_status_running
import kmpcyppieagents.app.shared.generated.resources.agent_status_stopped
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Agent-Management window body (S14: CYP-86 add / CYP-87 remove / CYP-88 edit) — one panel: a list
 * of agents plus operator-gated add/remove/edit dialogs (AGENT-MANAGEMENT §3). Operator-gated/fail-closed:
 * without a token the list is read-only and a visible gate hint explains why.
 *
 * Guardrails are shown **before** the action (disabled control + explanation, not a post-hoc rejection):
 * the PO role is disabled when a PO exists, an id collision is a field error, and the only PO's remove is
 * disabled. Lifecycle status/controls REUSE CYP-73 — this panel shows the read status and never spawns or
 * restarts itself (add → start via lifecycle; edit → amber "restart to apply").
 */
@Composable
fun AgentManagementPanel(
    viewModel: AgentManagementViewModel,
    modifier: Modifier = Modifier,
    // CYP-123/CYP-126: the connector picker (+ B opt-in dialog), host-anchored inside the add/edit dialogs
    // (spec §3.1). Two context-bound slots so the picker binds to the right write target (CYP-126): the ADD
    // slot feeds NewAgentSpec.connectorKind (no endpoint); the EDIT slot is bound to the specific agent and
    // writes the dedicated connector endpoint. Default no-op = no regress; the dialogs are already
    // operator-gated so the picker inherits that gate (§3.3).
    addConnectorPickerSlot: @Composable () -> Unit = {},
    editConnectorPickerSlot: @Composable (Agent) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag(AgentMgmtTags.PANEL),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!state.editable) {
            TonedHint(stringResource(Res.string.agent_mgmt_operator_required), HintTone.GATED, AgentMgmtTags.GATE_HINT)
        }

        Button(
            onClick = viewModel::openAdd,
            enabled = state.editable,
            modifier = Modifier.testTag(AgentMgmtTags.ADD_BUTTON),
        ) {
            Text(stringResource(Res.string.agent_add))
        }

        Column(
            modifier = Modifier.fillMaxWidth().testTag(AgentMgmtTags.LIST),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.agents.forEach { agent ->
                AgentRow(agent, state, viewModel)
            }
        }
    }

    if (state.addOpen) AddDialog(state, viewModel, addConnectorPickerSlot)
    state.removeTarget?.let { RemoveDialog(it, state, viewModel) }
    state.editTarget?.let { EditDialog(it, state, viewModel, editConnectorPickerSlot) }
}

@Composable
private fun AgentRow(agent: Agent, state: AgentMgmtUiState, viewModel: AgentManagementViewModel) {
    // CYP-156 §3.1: below PANE_COLLAPSE_WIDTH the 5-column row squeezes the action buttons off-screen →
    // deterministic 2-line layout (line 1 = identity name+role+status, line 2 = actions). The
    // only-PO-unremovable guardrail stays visible+disabled before any action. Same tags
    // (item/itemEdit/itemRemove/status); wide (≥600dp) keeps the single row unchanged.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().testTag(AgentMgmtTags.item(agent.id))) {
        if (maxWidth < PANE_COLLAPSE_WIDTH) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AgentIdentity(agent)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AgentActions(agent, state, viewModel)
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AgentIdentity(agent)
                AgentActions(agent, state, viewModel)
            }
        }
    }
}

/** Identity cluster (name + role + status) — line 1 narrow, leading cells wide. Tags unchanged. */
@Composable
private fun RowScope.AgentIdentity(agent: Agent) {
    Text(
        text = agent.name,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
    // Role: text carries the meaning (not colour). Reuse role labels.
    Text(stringResource(roleLabel(agent.role)), style = MaterialTheme.typography.labelMedium)
    // Lifecycle status (read) — reuse the CYP-73 status tag + agent_status_* labels.
    Text(
        text = stringResource(runStateLabel(agent.runState)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(AgentViewTags.status(agent.id)),
    )
}

/** Action cluster (edit + remove) — line 2 narrow, trailing cells wide. Guardrail stays disabled. */
@Composable
private fun RowScope.AgentActions(agent: Agent, state: AgentMgmtUiState, viewModel: AgentManagementViewModel) {
    TextButton(
        onClick = { viewModel.openEdit(agent) },
        enabled = state.editable,
        modifier = Modifier.testTag(AgentMgmtTags.itemEdit(agent.id)),
    ) { Text(stringResource(Res.string.agent_edit)) }
    TextButton(
        onClick = { viewModel.openRemove(agent) },
        // The only PO is unremovable — the guardrail is visible (disabled) before any dialog.
        enabled = state.editable && !state.isOnlyPo(agent),
        modifier = Modifier.testTag(AgentMgmtTags.itemRemove(agent.id)),
    ) { Text(stringResource(Res.string.agent_remove)) }
}

@Composable
private fun AddDialog(
    state: AgentMgmtUiState,
    viewModel: AgentManagementViewModel,
    addConnectorPickerSlot: @Composable () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = viewModel::closeAdd,
        confirmButton = {
            Button(
                onClick = viewModel::confirmAdd,
                enabled = state.canConfirmAdd,
                modifier = Modifier.testTag(AgentMgmtTags.ADD_CONFIRM),
            ) { Text(stringResource(Res.string.agent_add_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::closeAdd, modifier = Modifier.testTag(AgentMgmtTags.ADD_CANCEL)) {
                Text(stringResource(Res.string.agent_cancel))
            }
        },
        title = { Text(stringResource(Res.string.agent_add)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().testTag(AgentMgmtTags.ADD_DIALOG),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val idA11y = stringResource(Res.string.a11y_agent_add_id)
                LabeledField(
                    value = state.addForm.id, onChange = viewModel::setAddId,
                    label = stringResource(Res.string.agent_add_id_label),
                    tag = AgentMgmtTags.ADD_ID_INPUT, a11y = idA11y, isError = state.addIdCollision,
                )
                LabeledField(
                    value = state.addForm.name, onChange = viewModel::setAddName,
                    label = stringResource(Res.string.agent_add_name_label), tag = AgentMgmtTags.ADD_NAME_INPUT,
                )
                RolePicker(
                    pickerTag = AgentMgmtTags.ADD_ROLE_PICKER, selected = state.addForm.role,
                    onSelect = viewModel::setAddRole, poEnabled = !state.addPoBlocked,
                )
                if (state.addPoBlocked) {
                    Text(
                        stringResource(Res.string.agent_add_po_exists),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                val personaA11y = stringResource(Res.string.a11y_agent_add_persona)
                LabeledField(
                    value = state.addForm.persona, onChange = viewModel::setAddPersona,
                    label = stringResource(Res.string.agent_add_persona_label),
                    tag = AgentMgmtTags.ADD_PERSONA_INPUT, a11y = personaA11y, singleLine = false,
                )
                LabeledField(
                    value = state.addForm.launch, onChange = viewModel::setAddLaunch,
                    label = stringResource(Res.string.agent_add_launch_label), tag = AgentMgmtTags.ADD_LAUNCH_INPUT,
                )
                LabeledField(
                    value = state.addForm.worktree, onChange = viewModel::setAddWorktree,
                    label = stringResource(Res.string.agent_add_worktree_label), tag = AgentMgmtTags.ADD_WORKTREE_INPUT,
                )
                // Disclosure: creating does NOT spawn — start is the CYP-73 lifecycle (no second mechanism).
                TonedHint(stringResource(Res.string.agent_add_spawn_hint), HintTone.INFO, AgentMgmtTags.ADD_SPAWN_HINT)

                // CYP-123/CYP-126: connector picker (+ B opt-in). Add context → feeds NewAgentSpec.connectorKind,
                // no connector-endpoint call, no restart hint (fresh spawn, spec §3.4).
                addConnectorPickerSlot()

                val err = when {
                    state.addIdCollision -> Res.string.agent_add_id_exists
                    state.addError != null -> addErrorRes(state.addError)
                    else -> null
                }
                if (err != null) TonedHint(stringResource(err), HintTone.ERROR, AgentMgmtTags.ADD_ERROR)
            }
        },
    )
}

@Composable
private fun RemoveDialog(target: Agent, state: AgentMgmtUiState, viewModel: AgentManagementViewModel) {
    val deleting = state.removeWorktreeFate == WorktreeFate.DELETE
    AlertDialog(
        onDismissRequest = viewModel::closeRemove,
        confirmButton = {
            Button(
                onClick = viewModel::confirmRemove,
                enabled = state.canConfirmRemove,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.testTag(AgentMgmtTags.REMOVE_CONFIRM),
            ) {
                // Distinct, unambiguous verb; the worktree-delete path names the irreversible action.
                Text(stringResource(if (deleting) Res.string.agent_remove_confirm_delete else Res.string.agent_remove_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::closeRemove, modifier = Modifier.testTag(AgentMgmtTags.REMOVE_CANCEL)) {
                Text(stringResource(Res.string.agent_cancel))
            }
        },
        title = { Text(stringResource(Res.string.agent_remove_title, target.name)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().testTag(AgentMgmtTags.REMOVE_DIALOG),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Explicit consequences (what happens) — never under-specified.
                TonedHint(stringResource(Res.string.agent_remove_consequences), HintTone.INFO, AgentMgmtTags.REMOVE_CONSEQUENCES)

                Column(modifier = Modifier.fillMaxWidth().testTag(AgentMgmtTags.REMOVE_WORKTREE_CHOICE)) {
                    RadioRow(
                        selected = !deleting, onClick = { viewModel.setRemoveWorktreeFate(WorktreeFate.KEEP) },
                        label = stringResource(Res.string.agent_remove_worktree_keep),
                    )
                    RadioRow(
                        selected = deleting, onClick = { viewModel.setRemoveWorktreeFate(WorktreeFate.DELETE) },
                        label = stringResource(Res.string.agent_remove_worktree_delete),
                    )
                }
                // The destructive path names the concrete data loss (error tone + text, not colour alone).
                if (deleting) {
                    TonedHint(
                        stringResource(Res.string.agent_remove_worktree_warning, target.worktree),
                        HintTone.ERROR, AgentMgmtTags.REMOVE_WORKTREE_WARNING,
                    )
                }
                // The only PO cannot be removed — rule visible (confirm disabled above) + explanation.
                if (state.isOnlyPo(target)) {
                    TonedHint(stringResource(Res.string.agent_remove_last_po), HintTone.ERROR, AgentMgmtTags.REMOVE_ERROR)
                } else if (state.removeError != null) {
                    TonedHint(stringResource(removeErrorRes(state.removeError)), HintTone.ERROR, AgentMgmtTags.REMOVE_ERROR)
                }
            }
        },
    )
}

@Composable
private fun EditDialog(
    target: Agent,
    state: AgentMgmtUiState,
    viewModel: AgentManagementViewModel,
    editConnectorPickerSlot: @Composable (Agent) -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = viewModel::closeEdit,
        confirmButton = {
            Button(
                onClick = viewModel::confirmEdit,
                enabled = state.canConfirmEdit,
                modifier = Modifier.testTag(AgentMgmtTags.EDIT_SAVE),
            ) { Text(stringResource(Res.string.agent_save)) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::closeEdit, modifier = Modifier.testTag(AgentMgmtTags.EDIT_CANCEL)) {
                Text(stringResource(Res.string.agent_cancel))
            }
        },
        title = { Text(stringResource(Res.string.agent_edit_title, target.name)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().testTag(AgentMgmtTags.EDIT_DIALOG),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RolePicker(
                    pickerTag = AgentMgmtTags.EDIT_ROLE_PICKER, selected = state.editForm.role,
                    onSelect = viewModel::setEditRole, poEnabled = !state.editPoTakenByOther,
                )
                LabeledField(
                    value = state.editForm.persona, onChange = viewModel::setEditPersona,
                    label = stringResource(Res.string.agent_add_persona_label), tag = AgentMgmtTags.EDIT_PERSONA_INPUT,
                    singleLine = false,
                )
                LabeledField(
                    value = state.editForm.launch, onChange = viewModel::setEditLaunch,
                    label = stringResource(Res.string.agent_add_launch_label), tag = AgentMgmtTags.EDIT_LAUNCH_INPUT,
                )
                // id/worktree are fixed here (identity/path) — say so, don't silently omit.
                Text(
                    stringResource(Res.string.agent_edit_id_locked_hint),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // CYP-123/CYP-126: connector picker (+ B opt-in), bound to THIS agent. Writes the dedicated
                // connector endpoint for target.id; an existing B agent shows B selected (the truth), and a
                // change surfaces the reused amber "restart to apply" hint (spec §3.4). Without this binding a
                // B agent opened in Edit would silently reset to A — the bug CYP-126 fixes.
                editConnectorPickerSlot(target)
                // CYP-101 [Mittel 1]: a disabled confirm needs a visible reason BEFORE the action (like
                // Add/Remove). The only PO giving up the role and PO-taken-elsewhere are distinct messages;
                // a server error shows here too. (po-taken / last-PO are mutually exclusive — role is PO xor not.)
                val reason = when {
                    state.editWouldDropLastPo -> Res.string.agent_edit_last_po
                    state.editPoTakenByOther -> Res.string.agent_edit_po_exists
                    state.editError != null -> editErrorRes(state.editError)
                    else -> null
                }
                if (reason != null) {
                    TonedHint(stringResource(reason), HintTone.ERROR, AgentMgmtTags.EDIT_ERROR)
                }
                // Amber "saved ≠ active — restart to apply" (no second restart mechanism; reuse CYP-73).
                if (state.editEffectHint) {
                    TonedHint(stringResource(Res.string.agent_edit_effect_hint), HintTone.EFFECT_DEFERRED, AgentMgmtTags.EDIT_EFFECT_HINT)
                }
            }
        },
    )
}

@Composable
private fun RolePicker(pickerTag: String, selected: Role, onSelect: (Role) -> Unit, poEnabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(pickerTag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.agent_add_role_label), style = MaterialTheme.typography.labelMedium)
        // PO option — disabled when a PO already exists / is held by another (guardrail visible before action).
        RadioRow(
            selected = selected == Role.PO,
            onClick = { onSelect(Role.PO) },
            label = stringResource(Res.string.agent_role_po),
            enabled = poEnabled,
            tag = AgentMgmtTags.roleOption(pickerTag, "PO"),
        )
        RadioRow(
            selected = selected == Role.WORKER,
            onClick = { onSelect(Role.WORKER) },
            label = stringResource(Res.string.agent_role_worker),
            tag = AgentMgmtTags.roleOption(pickerTag, "WORKER"),
        )
    }
}

@Composable
private fun RadioRow(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    enabled: Boolean = true,
    tag: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Tag the RadioButton itself so its enabled/selected state is directly assertable (the guardrail
        // disables the PO option in place — assertIsNotEnabled on this node proves it).
        RadioButton(
            selected = selected,
            onClick = onClick,
            enabled = enabled,
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LabeledField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    tag: String,
    a11y: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        isError = isError,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .then(if (a11y != null) Modifier.semantics { contentDescription = a11y } else Modifier),
    )
}

private fun roleLabel(role: Role): StringResource = when (role) {
    Role.PO -> Res.string.agent_role_po
    Role.WORKER -> Res.string.agent_role_worker
    Role.PRODUCT_LEAD -> Res.string.agent_role_product_lead // CYP-98
}

private fun runStateLabel(state: AgentRunState): StringResource = when (state) {
    AgentRunState.RUNNING -> Res.string.agent_status_running
    AgentRunState.STOPPED -> Res.string.agent_status_stopped
    AgentRunState.ERROR -> Res.string.agent_status_error
}

private fun addErrorRes(key: String): StringResource = when (key) {
    "agent_add_id_exists" -> Res.string.agent_add_id_exists
    "agent_add_po_exists" -> Res.string.agent_add_po_exists
    "agent_mgmt_operator_required" -> Res.string.agent_mgmt_operator_required
    else -> Res.string.agent_add_error
}

private fun removeErrorRes(key: String): StringResource = when (key) {
    "agent_remove_last_po" -> Res.string.agent_remove_last_po
    "agent_mgmt_operator_required" -> Res.string.agent_mgmt_operator_required
    else -> Res.string.agent_remove_error
}

private fun editErrorRes(key: String): StringResource = when (key) {
    "agent_edit_po_exists" -> Res.string.agent_edit_po_exists
    "agent_edit_last_po" -> Res.string.agent_edit_last_po
    "agent_mgmt_operator_required" -> Res.string.agent_mgmt_operator_required
    else -> Res.string.agent_edit_error
}
