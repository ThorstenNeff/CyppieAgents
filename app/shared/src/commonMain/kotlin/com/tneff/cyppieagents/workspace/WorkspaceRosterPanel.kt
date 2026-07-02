package com.tneff.cyppieagents.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.tneff.cyppieagents.model.WorkspaceMember
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_workspace_members
import kmpcyppieagents.app.shared.generated.resources.workspace_members_title
import kmpcyppieagents.app.shared.generated.resources.workspace_tier_member
import kmpcyppieagents.app.shared.generated.resources.workspace_tier_operator
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-186 (roster fold, spec §3.2) — the OPERATOR-only members roster. Mounted only for an OPERATOR (see
 * [showRoster]); a MEMBER never receives this composable at all (enumeration seam). Each row shows a **short,
 * non-identifying** label (the friendly [WorkspaceMember.displayName] when present — currently always null from
 * BE3a — else the shortened identityId) + the tier as **text** (never colour alone, WCAG 1.4.1). No email, no
 * token, never the raw/long id.
 */
@Composable
fun WorkspaceRosterPanel(viewModel: WorkspaceRosterViewModel, modifier: Modifier = Modifier) {
    val members by viewModel.members.collectAsState()
    val rosterA11y = stringResource(Res.string.a11y_workspace_members)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .testTag(WorkspaceTags.MEMBERS)
            .semantics { contentDescription = rosterA11y }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(Res.string.workspace_members_title), style = MaterialTheme.typography.titleMedium)

        members.forEach { m ->
            Row(
                modifier = Modifier.fillMaxWidth().testTag(WorkspaceTags.member(m.identityId)),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = memberLabel(m),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(tierLabelKey(m.tier)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(WorkspaceTags.memberRole(m.identityId)),
                )
            }
        }
    }
}

/** Friendly name when present (BE3a: currently always null), else a SHORTENED identityId — never raw/long/email. */
internal fun memberLabel(m: WorkspaceMember): String =
    m.displayName?.takeIf { it.isNotBlank() } ?: shortId(m.identityId)

/** First 8 chars of the (UUID) identity id — enough to disambiguate, never the full raw id. */
internal fun shortId(identityId: String): String = identityId.take(8)

private fun tierLabelKey(tier: String) =
    if (tier.equals("OPERATOR", ignoreCase = true)) Res.string.workspace_tier_operator else Res.string.workspace_tier_member
