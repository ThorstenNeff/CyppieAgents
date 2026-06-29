package com.tneff.cyppieagents.crossproject

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.eventlog.formatTs
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_crossproject_badge
import kmpcyppieagents.app.shared.generated.resources.crossproject_access_read
import kmpcyppieagents.app.shared.generated.resources.crossproject_access_write
import kmpcyppieagents.app.shared.generated.resources.crossproject_authorize
import kmpcyppieagents.app.shared.generated.resources.crossproject_badge
import kmpcyppieagents.app.shared.generated.resources.crossproject_cancel
import kmpcyppieagents.app.shared.generated.resources.crossproject_confirm
import kmpcyppieagents.app.shared.generated.resources.crossproject_dialog_scope
import kmpcyppieagents.app.shared.generated.resources.crossproject_error
import kmpcyppieagents.app.shared.generated.resources.crossproject_human_only
import kmpcyppieagents.app.shared.generated.resources.crossproject_member_access
import kmpcyppieagents.app.shared.generated.resources.crossproject_member_project
import kmpcyppieagents.app.shared.generated.resources.crossproject_operator_required
import kmpcyppieagents.app.shared.generated.resources.crossproject_owner_consent
import kmpcyppieagents.app.shared.generated.resources.crossproject_revoke
import kmpcyppieagents.app.shared.generated.resources.crossproject_single_owner_note
import kmpcyppieagents.app.shared.generated.resources.crossproject_status_not_shared
import kmpcyppieagents.app.shared.generated.resources.crossproject_status_shared
import kmpcyppieagents.app.shared.generated.resources.crossproject_title
import org.jetbrains.compose.resources.stringResource

/**
 * Cross-project authorization controls for ONE channel (CYP-93), host-anchored into the channel context
 * (Comm channel list / ACL row header). Renders the honest cross-project state — a form/glyph badge
 * (identity ≠ right), the shared/not-shared status, the concrete reachable members with their home project,
 * and the owner-gated authorize/revoke action — plus the authorization dialog.
 *
 * Security (§2.6, load-bearing): there is NO affordance to accept a share from a message/agent — only the
 * owner authorizes, via the explicit dialog, operator/owner-gated (the server enforces too). Without a
 * token the controls are read-only with a gate hint.
 */
@Composable
fun CrossProjectControls(viewModel: CrossProjectViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val channelId = state.channelId

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Badge: shared → authorized cross-project reach; spans-but-unauthorized → the fail-closed qualifier.
        val badgeA11y = stringResource(Res.string.a11y_crossproject_badge)
        when {
            state.shared -> BadgeChip(CrossProjectTags.badge(channelId), badgeA11y)
            state.isCrossProject -> BadgeChip(CrossProjectTags.badgeUnauthorized(channelId), badgeA11y)
        }

        // Status line: shared (when + concrete reach) or the explicit fail-closed default. Both neutral INFO.
        val statusText = if (state.shared) {
            stringResource(Res.string.crossproject_status_shared, formatTs(state.sharedAt ?: 0L), membersSummary(state.reachableMembers))
        } else {
            stringResource(Res.string.crossproject_status_not_shared)
        }
        TonedHint(statusText, HintTone.INFO, CrossProjectTags.STATUS)

        // Per-member home-project disclosure (Text, identity ≠ right).
        state.reachableMembers.forEach { m ->
            Text(
                text = "${m.agentId} · ${stringResource(Res.string.crossproject_member_project, m.homeProjectId)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(CrossProjectTags.member(m.agentId)),
            )
        }

        // Action / gate (owner-gated, fail-closed).
        when {
            !state.editable -> TonedHint(stringResource(Res.string.crossproject_operator_required), HintTone.GATED, CrossProjectTags.GATE_HINT)
            state.shared -> TextButton(onClick = viewModel::revoke, modifier = Modifier.testTag(CrossProjectTags.REVOKE)) {
                Text(stringResource(Res.string.crossproject_revoke))
            }
            else -> Button(onClick = viewModel::openDialog, modifier = Modifier.testTag(CrossProjectTags.AUTHORIZE)) {
                Text(stringResource(Res.string.crossproject_authorize))
            }
        }
    }

    if (state.dialogOpen) AuthorizeDialog(state, viewModel)
}

@Composable
private fun BadgeChip(tag: String, a11y: String) {
    Row(
        modifier = Modifier.testTag(tag).semantics { contentDescription = a11y },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Form/glyph marker (not colour alone, WCAG 1.4.1) + label.
        Text("⇄", style = MaterialTheme.typography.labelMedium)
        Text(stringResource(Res.string.crossproject_badge), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AuthorizeDialog(state: CrossProjectUiState, viewModel: CrossProjectViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::closeDialog,
        confirmButton = {
            Button(
                onClick = viewModel::confirmAuthorize,
                enabled = state.canConfirm,
                modifier = Modifier.testTag(CrossProjectTags.DIALOG_CONFIRM),
            ) { Text(stringResource(Res.string.crossproject_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::closeDialog, modifier = Modifier.testTag(CrossProjectTags.DIALOG_CANCEL)) {
                Text(stringResource(Res.string.crossproject_cancel))
            }
        },
        title = { Text(stringResource(Res.string.crossproject_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().testTag(CrossProjectTags.DIALOG),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Scope/consequences — the CONCRETE member-agents reached, never "project B" wholesale.
                TonedHint(
                    stringResource(Res.string.crossproject_dialog_scope, membersSummary(state.reachableMembers)),
                    HintTone.INFO, CrossProjectTags.DIALOG_SCOPE,
                )
                // Owner consent — a deliberate, named affordance, not a silent default (data form is 1→N-able).
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { viewModel.setOwnerConsent(!state.ownerConsent) }
                        .testTag(CrossProjectTags.DIALOG_OWNER_CONSENT),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The whole row toggles the consent (the Checkbox is the visual; the row is the target).
                    Checkbox(checked = state.ownerConsent, onCheckedChange = null)
                    Text(stringResource(Res.string.crossproject_owner_consent), style = MaterialTheme.typography.bodyMedium)
                }
                // Anti-injection invariant, made visible (§2.6, load-bearing).
                TonedHint(stringResource(Res.string.crossproject_human_only), HintTone.INFO, CrossProjectTags.DIALOG_HUMAN_ONLY)
                // Single-owner honesty: not bilateral consent (S18).
                TonedHint(stringResource(Res.string.crossproject_single_owner_note), HintTone.INFO, CrossProjectTags.DIALOG_SINGLE_OWNER)
                if (state.error != null) {
                    TonedHint(stringResource(Res.string.crossproject_error), HintTone.ERROR, CrossProjectTags.DIALOG_ERROR)
                }
            }
        },
    )
}

/** Join the concrete reachable members as "agent (project X) – access" — never "project B" wholesale. */
@Composable
private fun membersSummary(members: List<CrossMember>): String {
    if (members.isEmpty()) return "—"
    // Resolve the localized templates/labels in the composable body; format per-member in plain Kotlin
    // (stringResource can't be called inside a non-composable lambda like map/joinToString).
    val template = stringResource(Res.string.crossproject_member_access)
    val readLabel = stringResource(Res.string.crossproject_access_read)
    val writeLabel = stringResource(Res.string.crossproject_access_write)
    return members.joinToString(", ") { m ->
        val access = if (m.access == CrossAccess.WRITE) writeLabel else readLabel
        template.replace("%1\$s", m.agentId).replace("%2\$s", m.homeProjectId).replace("%3\$s", access)
    }
}
