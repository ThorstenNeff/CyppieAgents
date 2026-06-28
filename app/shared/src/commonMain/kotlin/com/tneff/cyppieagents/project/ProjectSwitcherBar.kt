package com.tneff.cyppieagents.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_project_switch_to
import kmpcyppieagents.app.shared.generated.resources.a11y_project_switcher_menu
import kmpcyppieagents.app.shared.generated.resources.project_cancel
import kmpcyppieagents.app.shared.generated.resources.project_manage
import kmpcyppieagents.app.shared.generated.resources.project_mgmt_title
import kmpcyppieagents.app.shared.generated.resources.project_switch_hint
import kmpcyppieagents.app.shared.generated.resources.project_switcher_active
import kmpcyppieagents.app.shared.generated.resources.project_switcher_scope_hint
import org.jetbrains.compose.resources.stringResource

/**
 * The project switcher (CYP-92) — the top-level navigation **above** the `WindowHost` (PROJECT-MANAGEMENT
 * §2). It makes the active project unambiguous (a non-colour marker + "Active project: <name>"), opens a
 * switch menu (switching is a non-destructive context re-fetch, not deletion), states the scope boundary,
 * and hosts the management overlay (CYP-91) — it never duplicates the CRUD.
 *
 * Switching is operator-gated (a `/api/projects` mutation): without an operator token the menu is
 * informational (the active project + scope are still shown), and items are disabled. The already-active
 * project is a no-op, never a "switch target" (no phantom entry).
 */
@Composable
fun ProjectSwitcherBar(viewModel: ProjectViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val activeName = state.projects.firstOrNull { it.id == state.activeProjectId }?.name ?: state.activeProjectId

    Column(
        modifier = modifier.fillMaxWidth().testTag(ProjectTags.BAR).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Active display: form marker (●) + label — never colour alone (WCAG 1.4.1).
            Row(
                modifier = Modifier.weight(1f).testTag(ProjectTags.ACTIVE),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("●", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(Res.string.project_switcher_active, activeName),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box {
                val menuA11y = stringResource(Res.string.a11y_project_switcher_menu, activeName)
                TextButton(
                    onClick = viewModel::openMenu,
                    modifier = Modifier.testTag(ProjectTags.MENU).semantics { contentDescription = menuA11y },
                ) { Text("▾", style = MaterialTheme.typography.titleMedium) }

                DropdownMenu(expanded = state.menuOpen, onDismissRequest = viewModel::closeMenu) {
                    // Disclosure: switching is non-destructive (separates it from delete). Neutral tone.
                    TonedHint(stringResource(Res.string.project_switch_hint), HintTone.INFO, ProjectTags.SWITCH_HINT)
                    state.projects.forEach { project ->
                        val isActive = project.id == state.activeProjectId
                        val switchA11y = stringResource(Res.string.a11y_project_switch_to, project.name)
                        DropdownMenuItem(
                            text = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (isActive) {
                                        Text("●", modifier = Modifier.testTag(ProjectTags.itemActive(project.id)))
                                    }
                                    Text(project.name)
                                }
                            },
                            onClick = { viewModel.switchTo(project.id) },
                            // The active project is a no-op; switching needs operator (server-gated mutation).
                            enabled = state.editable && !isActive,
                            modifier = Modifier.testTag(ProjectTags.item(project.id)).semantics { contentDescription = switchA11y },
                        )
                    }
                    // Management entry — hosts the CRUD overlay (§3), not a duplicate.
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.project_manage)) },
                        onClick = viewModel::openManage,
                        modifier = Modifier.testTag(ProjectTags.MANAGE),
                    )
                }
            }
        }
        // Scope boundary disclosure (§1): all windows/data belong to the active project. Neutral.
        TonedHint(stringResource(Res.string.project_switcher_scope_hint), HintTone.INFO, ProjectTags.SCOPE_HINT)
    }

    if (state.manageOpen) {
        AlertDialog(
            onDismissRequest = viewModel::closeManage,
            confirmButton = {
                TextButton(onClick = viewModel::closeManage) { Text(stringResource(Res.string.project_cancel)) }
            },
            title = { Text(stringResource(Res.string.project_mgmt_title)) },
            text = { ProjectManagementPanel(viewModel) },
        )
    }
}
