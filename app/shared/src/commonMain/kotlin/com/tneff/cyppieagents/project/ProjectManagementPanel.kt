package com.tneff.cyppieagents.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.Project
import com.tneff.cyppieagents.window.PANE_COLLAPSE_WIDTH
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_project_add_name
import kmpcyppieagents.app.shared.generated.resources.project_add
import kmpcyppieagents.app.shared.generated.resources.project_add_confirm
import kmpcyppieagents.app.shared.generated.resources.project_add_error
import kmpcyppieagents.app.shared.generated.resources.project_add_name_empty
import kmpcyppieagents.app.shared.generated.resources.project_add_name_exists
import kmpcyppieagents.app.shared.generated.resources.project_add_name_label
import kmpcyppieagents.app.shared.generated.resources.project_cancel
import kmpcyppieagents.app.shared.generated.resources.project_delete
import kmpcyppieagents.app.shared.generated.resources.project_delete_active_blocked
import kmpcyppieagents.app.shared.generated.resources.project_delete_confirm
import kmpcyppieagents.app.shared.generated.resources.project_delete_consequences
import kmpcyppieagents.app.shared.generated.resources.project_delete_error
import kmpcyppieagents.app.shared.generated.resources.project_delete_last_blocked
import kmpcyppieagents.app.shared.generated.resources.project_delete_title
import kmpcyppieagents.app.shared.generated.resources.project_delete_worktree_delete
import kmpcyppieagents.app.shared.generated.resources.project_delete_worktree_keep
import kmpcyppieagents.app.shared.generated.resources.project_delete_worktree_warning
import kmpcyppieagents.app.shared.generated.resources.project_mgmt_operator_required
import kmpcyppieagents.app.shared.generated.resources.workspace_operator_only
import kmpcyppieagents.app.shared.generated.resources.project_rename
import kmpcyppieagents.app.shared.generated.resources.project_rename_error
import kmpcyppieagents.app.shared.generated.resources.project_rename_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The project-management surface (CYP-91) — structurally a sibling of `AgentManagementPanel`: a list of
 * projects plus operator-gated add/rename/delete dialogs (PROJECT-MANAGEMENT §3/§4). Operator-gated/
 * fail-closed: without a token the list is read-only and a visible gate hint explains why.
 *
 * Delete is the only destructive action and shows its guardrails **before** the action: the active and the
 * last project have their delete disabled with an inline reason (§4.3). Every hint renders through the
 * shared [TonedHint] (CYP-99) — the active/last blocks are INFO (an instruction, not an error), the
 * data-loss warning is ERROR.
 */
@Composable
fun ProjectManagementPanel(viewModel: ProjectViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag(ProjectTags.PANEL),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!state.editable) {
            TonedHint(stringResource(Res.string.workspace_operator_only), HintTone.GATED, ProjectTags.GATE_HINT)
        }
        Button(
            onClick = viewModel::openAdd,
            enabled = state.editable,
            modifier = Modifier.testTag(ProjectTags.ADD),
        ) { Text(stringResource(Res.string.project_add)) }

        Column(
            modifier = Modifier.fillMaxWidth().testTag(ProjectTags.LIST),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.projects.forEach { ProjectRow(it, state, viewModel) }
        }
    }

    if (state.addOpen) AddDialog(state, viewModel)
    state.renameTarget?.let { RenameDialog(it, state, viewModel) }
    state.deleteTarget?.let { DeleteDialog(it, state, viewModel) }
}

@Composable
private fun ProjectRow(project: Project, state: ProjectUiState, viewModel: ProjectViewModel) {
    val blockedCode = state.deleteBlockedCode(project)
    Column(modifier = Modifier.fillMaxWidth().testTag(ProjectTags.row(project.id))) {
        // CYP-282: mirror the CYP-156 AgentRow reflow — below PANE_COLLAPSE_WIDTH the name + two wide German
        // action buttons ("Umbenennen"/"Löschen") squeeze the name to a few chars; split into a 2-line layout
        // (identity line 1, actions line 2) so the name keeps full width. Same tags; wide keeps the single row.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth < PANE_COLLAPSE_WIDTH) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProjectIdentity(project, state)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProjectActions(project, state, viewModel)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProjectIdentity(project, state)
                    ProjectActions(project, state, viewModel)
                }
            }
        }
        // The reason is visible inline (not a post-hoc rejection); INFO — an instruction/system rule.
        if (blockedCode != null) {
            TonedHint(stringResource(deleteBlockedRes(blockedCode)), HintTone.INFO, ProjectTags.rowDeleteBlocked(project.id))
        }
    }
}

/** Identity cluster (active marker + name) — line 1 narrow, leading cells wide. Tags unchanged. */
@Composable
private fun RowScope.ProjectIdentity(project: Project, state: ProjectUiState) {
    // Active marker = form/glyph, not colour (WCAG 1.4.1): a filled dot before the active project.
    if (state.isActive(project)) {
        Text("●", style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag(ProjectTags.rowActive(project.id)))
    }
    Text(
        text = project.name,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
}

/** Action cluster (rename + delete) — line 2 narrow, trailing cells wide. Delete-safety guardrail stays disabled. */
@Composable
private fun RowScope.ProjectActions(project: Project, state: ProjectUiState, viewModel: ProjectViewModel) {
    TextButton(
        onClick = { viewModel.openRename(project) },
        enabled = state.editable,
        modifier = Modifier.testTag(ProjectTags.rowRename(project.id)),
    ) { Text(stringResource(Res.string.project_rename)) }
    TextButton(
        onClick = { viewModel.openDelete(project) },
        // Delete-safety: active + last project are not deletable — disabled BEFORE the action.
        enabled = state.canDelete(project),
        modifier = Modifier.testTag(ProjectTags.rowDelete(project.id)),
    ) { Text(stringResource(Res.string.project_delete)) }
}

@Composable
private fun AddDialog(state: ProjectUiState, viewModel: ProjectViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::closeAdd,
        confirmButton = {
            Button(
                onClick = viewModel::confirmAdd,
                enabled = state.canConfirmAdd,
                modifier = Modifier.testTag(ProjectTags.ADD_CONFIRM),
            ) { Text(stringResource(Res.string.project_add_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::closeAdd, modifier = Modifier.testTag(ProjectTags.ADD_CANCEL)) {
                Text(stringResource(Res.string.project_cancel))
            }
        },
        title = { Text(stringResource(Res.string.project_add)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().testTag(ProjectTags.ADD_DIALOG),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NameField(
                    value = state.addName, onChange = viewModel::setAddName,
                    tag = ProjectTags.ADD_NAME, a11y = stringResource(Res.string.a11y_project_add_name),
                    isError = state.addName.isNotBlank() && state.addCreateCode != null,
                )
                // Reactive name-collision + post-submit server error.
                val errKey = when {
                    state.addName.isNotBlank() && state.addCreateCode == "project_exists" -> "project_add_name_exists"
                    state.addError != null -> state.addError
                    else -> null
                }
                if (errKey != null) TonedHint(stringResource(addErrorRes(errKey)), HintTone.ERROR, ProjectTags.ADD_ERROR)
            }
        },
    )
}

@Composable
private fun RenameDialog(target: Project, state: ProjectUiState, viewModel: ProjectViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::closeRename,
        confirmButton = {
            Button(
                onClick = viewModel::confirmRename,
                enabled = state.canConfirmRename,
                modifier = Modifier.testTag(ProjectTags.RENAME_CONFIRM),
            ) { Text(stringResource(Res.string.project_rename)) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::closeRename, modifier = Modifier.testTag(ProjectTags.RENAME_CANCEL)) {
                Text(stringResource(Res.string.project_cancel))
            }
        },
        title = { Text(stringResource(Res.string.project_rename_title, target.name)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().testTag(ProjectTags.RENAME_DIALOG),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NameField(
                    value = state.renameName, onChange = viewModel::setRenameName,
                    tag = ProjectTags.RENAME_NAME, a11y = stringResource(Res.string.a11y_project_add_name),
                    isError = state.renameNameTaken,
                )
                val errKey = when {
                    state.renameNameTaken -> "project_add_name_exists"
                    state.renameError != null -> state.renameError
                    else -> null
                }
                if (errKey != null) TonedHint(stringResource(renameErrorRes(errKey)), HintTone.ERROR, ProjectTags.RENAME_ERROR)
            }
        },
    )
}

@Composable
private fun DeleteDialog(target: Project, state: ProjectUiState, viewModel: ProjectViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::closeDelete,
        confirmButton = {
            Button(
                onClick = viewModel::confirmDelete,
                enabled = state.canConfirmDelete,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.testTag(ProjectTags.DELETE_CONFIRM),
            ) { Text(stringResource(Res.string.project_delete_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::closeDelete, modifier = Modifier.testTag(ProjectTags.DELETE_CANCEL)) {
                Text(stringResource(Res.string.project_cancel))
            }
        },
        title = { Text(stringResource(Res.string.project_delete_title, target.name)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().testTag(ProjectTags.DELETE_DIALOG),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Guaranteed consequences (categories) — INFO. Counts are a Fast-Follow (§6.5): the advisory
                // counts slot stays unrendered until the backend supplies them (never invented).
                TonedHint(stringResource(Res.string.project_delete_consequences), HintTone.INFO, ProjectTags.DELETE_CONSEQUENCES)

                Column(modifier = Modifier.fillMaxWidth().testTag(ProjectTags.DELETE_WORKTREE_CHOICE)) {
                    RadioRow(
                        selected = !state.deleteWorktrees,
                        onClick = { viewModel.setDeleteWorktrees(false) },
                        label = stringResource(Res.string.project_delete_worktree_keep),
                        tag = ProjectTags.DELETE_WORKTREE_KEEP,
                    )
                    RadioRow(
                        selected = state.deleteWorktrees,
                        onClick = { viewModel.setDeleteWorktrees(true) },
                        label = stringResource(Res.string.project_delete_worktree_delete),
                        tag = ProjectTags.DELETE_WORKTREE_DELETE,
                    )
                }
                // Destructive path names the concrete data loss (ERROR; separates pushed-safe from local-at-risk).
                if (state.deleteWorktrees) {
                    TonedHint(stringResource(Res.string.project_delete_worktree_warning), HintTone.ERROR, ProjectTags.DELETE_WORKTREE_WARNING)
                }
                if (state.deleteError != null) {
                    TonedHint(stringResource(deleteErrorRes(state.deleteError)), HintTone.ERROR, ProjectTags.DELETE_ERROR)
                }
            }
        },
    )
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit, tag: String, a11y: String, isError: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(Res.string.project_add_name_label)) },
        singleLine = true,
        isError = isError,
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = a11y },
    )
}

@Composable
private fun RadioRow(selected: Boolean, onClick: () -> Unit, label: String, tag: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick, modifier = Modifier.testTag(tag))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun deleteBlockedRes(code: String): StringResource = when (code) {
    "active_project_protected" -> Res.string.project_delete_active_blocked
    else -> Res.string.project_delete_last_blocked // "last_project"
}

private fun addErrorRes(key: String): StringResource = when (key) {
    "project_add_name_exists" -> Res.string.project_add_name_exists
    "project_add_name_empty" -> Res.string.project_add_name_empty
    "project_mgmt_operator_required" -> Res.string.project_mgmt_operator_required
    else -> Res.string.project_add_error
}

private fun renameErrorRes(key: String): StringResource = when (key) {
    "project_add_name_exists" -> Res.string.project_add_name_exists
    "project_add_name_empty" -> Res.string.project_add_name_empty
    "project_mgmt_operator_required" -> Res.string.project_mgmt_operator_required
    else -> Res.string.project_rename_error
}

private fun deleteErrorRes(key: String): StringResource = when (key) {
    "project_delete_active_blocked" -> Res.string.project_delete_active_blocked
    "project_delete_last_blocked" -> Res.string.project_delete_last_blocked
    "project_mgmt_operator_required" -> Res.string.project_mgmt_operator_required
    else -> Res.string.project_delete_error
}
