package com.tneff.cyppieagents.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import com.tneff.cyppieagents.auth.UserTier
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import com.tneff.cyppieagents.workspace.WorkspaceTags
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_project_switch_to
import kmpcyppieagents.app.shared.generated.resources.a11y_project_switcher_menu
import kmpcyppieagents.app.shared.generated.resources.a11y_workspace_role
import kmpcyppieagents.app.shared.generated.resources.workspace_operator_is
import kmpcyppieagents.app.shared.generated.resources.workspace_role_indicator_member
import kmpcyppieagents.app.shared.generated.resources.workspace_role_indicator_operator
import kmpcyppieagents.app.shared.generated.resources.project_cancel
import kmpcyppieagents.app.shared.generated.resources.project_manage
import kmpcyppieagents.app.shared.generated.resources.project_mgmt_title
import kmpcyppieagents.app.shared.generated.resources.project_switch_hint
import kmpcyppieagents.app.shared.generated.resources.project_switcher_active
import kmpcyppieagents.app.shared.generated.resources.project_switcher_scope_hint
import org.jetbrains.compose.resources.stringResource

// CYP-159 (Klasse C): cap the width of the switch-hint inside the DropdownMenu. A DropdownMenu sizes to
// its widest child; the single-line hint was that child → the menu surface stretched edge-to-edge and
// the hint clipped off the right screen edge. Constraining the hint lets it wrap (multi-line) → the menu
// surface shrinks to ~this width. Switcher-specific (distinct from the 600dp pane / 560dp event-row
// breakpoints); not a responsive breakpoint — a plain max width that holds on every form factor.
private val SWITCHER_HINT_MAX_WIDTH = 280.dp

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
fun ProjectSwitcherBar(
    viewModel: ProjectViewModel,
    modifier: Modifier = Modifier,
    /** CYP-186: the signed-in user's workspace tier — drives the persistent role indicator. */
    tier: UserTier = UserTier.MEMBER,
    /** The operator's display name shown to a MEMBER ("Operator: …"). null (e.g. BE1 not yet delivering it) omits
     *  that line — never an email/contact dump (§3.3). */
    operatorName: String? = null,
) {
    val state by viewModel.state.collectAsState()
    val activeName = state.projects.firstOrNull { it.id == state.activeProjectId }?.name ?: state.activeProjectId

    Column(
        modifier = modifier.fillMaxWidth().testTag(ProjectTags.BAR).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // CYP-186 — persistent role indicator (spec §3.1): a neutral identity label (reuse the ● marker), NOT a
        // prestige badge. The honest reason operator controls are gated; names the operator so a MEMBER knows whom
        // to ask. Colour is never the sole carrier — the role is text (WCAG 1.4.1). User-Tier ≠ Agent-Role.
        val roleText = stringResource(
            if (tier == UserTier.OPERATOR) Res.string.workspace_role_indicator_operator
            else Res.string.workspace_role_indicator_member,
        )
        val roleA11y = stringResource(Res.string.a11y_workspace_role, roleText)
        Row(
            modifier = Modifier.testTag(WorkspaceTags.ROLE_INDICATOR).semantics { contentDescription = roleA11y },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("●", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = roleText,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (tier == UserTier.MEMBER && !operatorName.isNullOrBlank()) {
                Text(
                    text = stringResource(Res.string.workspace_operator_is, operatorName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(WorkspaceTags.OPERATOR_NAME),
                )
            }
        }

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
                    // CYP-159: cap the hint width so it wraps instead of stretching the menu edge-to-edge
                    // (the wording stays — "nothing is deleted" is disclosure-bearing; wrap, don't shorten).
                    TonedHint(
                        stringResource(Res.string.project_switch_hint), HintTone.INFO, ProjectTags.SWITCH_HINT,
                        modifier = Modifier.widthIn(max = SWITCHER_HINT_MAX_WIDTH),
                    )
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
                                    // CYP-159: a long project name must not re-stretch the (now capped) menu surface.
                                    Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
