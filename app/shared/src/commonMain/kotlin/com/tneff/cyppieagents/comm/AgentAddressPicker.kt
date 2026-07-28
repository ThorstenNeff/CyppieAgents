package com.tneff.cyppieagents.comm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.Channel
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.agent_address_ambiguous
import kmpcyppieagents.app.shared.generated.resources.agent_address_label
import kmpcyppieagents.app.shared.generated.resources.agent_address_open
import kmpcyppieagents.app.shared.generated.resources.agent_address_unreachable
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-884 (OS-D, Compose mirror of web-ts CYP-876 `AgentAddressPicker.tsx`) — the recipient-addressing control: pick a
 * target agent → resolve its DIRECT spoke ([resolveAgentChannel], client-side) → jump the composer to that channel.
 * Fail-closed, render ≠ authority:
 *  • resolved   → the DM action selects the spoke channel (the existing channel-post carries the message — no new send path).
 *  • unreachable → honest "not directly addressable" — NO fabricated channel (a new direct link is OS-C channel-mgmt).
 *  • ambiguous  → a visible FLAG (Assertive), the action is DISABLED — NEVER a silent-first pick of a DM route from
 *    untrusted data.
 */
@Composable
fun AgentAddressPicker(
    agentIds: List<String>,
    channels: List<Channel>,
    /** Jump the composer to a channel (reused for the resolved spoke — no new send path). */
    onSelectChannel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var target by remember { mutableStateOf("") }
    val resolution: AgentAddress? = if (target.isEmpty()) null else resolveAgentChannel(target, channels)

    Column(modifier.testTag(AgentAddressTags.ROOT), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(Res.string.agent_address_label),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        agentIds.forEach { a ->
            Text(
                (if (target == a) "● " else "○ ") + a,
                modifier = Modifier
                    .testTag(AgentAddressTags.option(a))
                    .selectable(selected = target == a, role = Role.RadioButton) { target = a },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        // DM action — enabled ONLY when the target resolves to exactly one DIRECT spoke; unreachable/ambiguous do
        // NOTHING (the honest state is shown, never a silent route).
        Button(
            onClick = { (resolution as? AgentAddress.Resolved)?.let { onSelectChannel(it.channelId) } },
            enabled = resolution is AgentAddress.Resolved,
            modifier = Modifier.testTag(AgentAddressTags.DM),
        ) { Text(stringResource(Res.string.agent_address_open)) }

        when (resolution) {
            is AgentAddress.Unreachable -> Text(
                stringResource(Res.string.agent_address_unreachable),
                modifier = Modifier.testTag(AgentAddressTags.UNREACHABLE),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            is AgentAddress.Ambiguous -> Text(
                stringResource(Res.string.agent_address_ambiguous),
                modifier = Modifier.testTag(AgentAddressTags.AMBIGUOUS).semantics { liveRegion = LiveRegionMode.Assertive },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            is AgentAddress.Resolved, null -> Unit // resolved shows via the enabled DM; empty selection shows nothing
        }
    }
}
