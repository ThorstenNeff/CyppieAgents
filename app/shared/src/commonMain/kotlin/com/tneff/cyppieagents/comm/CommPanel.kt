package com.tneff.cyppieagents.comm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.window.COMPOSER_MIN_WIDTH
import com.tneff.cyppieagents.window.PANE_COLLAPSE_WIDTH
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.testing.testTagA11y
import com.tneff.cyppieagents.ui.AgentAvatarView
import com.tneff.cyppieagents.ui.SenderPalette
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.agent_role_po
import kmpcyppieagents.app.shared.generated.resources.comm_back
import kmpcyppieagents.app.shared.generated.resources.comm_channels_empty
import kmpcyppieagents.app.shared.generated.resources.comm_composer_placeholder
import kmpcyppieagents.app.shared.generated.resources.comm_composer_send
import kmpcyppieagents.app.shared.generated.resources.comm_msg_pending
import kmpcyppieagents.app.shared.generated.resources.comm_readonly_hint
import kmpcyppieagents.app.shared.generated.resources.comm_send_denied
import kmpcyppieagents.app.shared.generated.resources.comm_send_failed
import kmpcyppieagents.app.shared.generated.resources.comm_status_connecting
import kmpcyppieagents.app.shared.generated.resources.comm_status_offline
import kmpcyppieagents.app.shared.generated.resources.comm_timeline_empty
import org.jetbrains.compose.resources.stringResource

/**
 * Comm panel (CYP-21, design CYP-17): master channel list + detail timeline + composer. Renders the
 * inner area only — the window chrome comes from the host (CYP-10/CYP-15). Honest states throughout:
 * only ACL-readable channels appear (server-filtered), the live banner reflects the real socket
 * state, and optimistic sends are clearly marked until confirmed.
 */
@Composable
fun CommPanel(
    viewModel: CommViewModel,
    modifier: Modifier = Modifier,
    // CYP-93: per-channel cross-project authorization affordance (badge/status/authorize), host-anchored
    // here as the primary entry. Default no-op → no behaviour change where it isn't wired.
    crossProjectSlot: @Composable (channelId: String) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    // CYP-156: collapse to single-pane below PANE_COLLAPSE_WIDTH, measured at the panel inner width
    // (window-agnostic — a comm window can be dragged narrow on desktop or be full-width in the phone
    // pager). Same master/detail nodes as two-pane, just list OR conversation (EventBrowse precedent).
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (maxWidth < PANE_COLLAPSE_WIDTH) {
            if (state.selectedChannelId == null) {
                ChannelListPane(
                    channels = state.channels,
                    selectedId = state.selectedChannelId,
                    onSelect = viewModel::select,
                    crossProjectSlot = crossProjectSlot,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                TimelinePane(
                    state = state,
                    agents = state.agents,
                    onSend = viewModel::send,
                    onBack = viewModel::clearSelection,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                ChannelListPane(
                    channels = state.channels,
                    selectedId = state.selectedChannelId,
                    onSelect = viewModel::select,
                    crossProjectSlot = crossProjectSlot,
                    modifier = Modifier.width(220.dp).fillMaxSize(),
                )
                TimelinePane(
                    state = state,
                    agents = state.agents,
                    onSend = viewModel::send,
                    onBack = null,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun ChannelListPane(
    channels: List<Channel>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    crossProjectSlot: @Composable (channelId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (channels.isEmpty()) {
            Text(
                text = stringResource(Res.string.comm_channels_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp).testTag(CommTags.EMPTY_CHANNELS),
            )
            return@Column
        }
        LazyColumn(modifier = Modifier.fillMaxSize().testTagA11y(CommTags.CHANNEL_LIST)) {
            items(channels, key = { it.id }) { channel ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    ChannelRow(channel, selected = channel.id == selectedId, onClick = { onSelect(channel.id) })
                    // CYP-93: the cross-project authorization affordance for this channel (badge/status/authorize).
                    crossProjectSlot(channel.id)
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(channel: Channel, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .testTag(CommTags.channel(channel.id))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Kind via glyph (not colour) + identity dot via colorSlot(channel.id).
        Text(text = kindGlyph(channel.kind), style = MaterialTheme.typography.labelSmall)
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(SenderPalette.forChannel(channel.id).avatarFill))
        Text(
            text = channel.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun TimelinePane(
    state: CommUiState,
    agents: Map<String, Agent>,
    onSend: (String) -> Unit,
    // CYP-156: single-pane only — an explicit "back to channels" affordance. null in two-pane (no back).
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (onBack != null) {
            Text(
                text = "‹ " + stringResource(Res.string.comm_back),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .testTag(CommTags.BACK)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        ConnectionBanner(state.connection)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.selectedChannelId == null -> Unit
                state.messages.isEmpty() && !state.loadingHistory ->
                    Text(
                        text = stringResource(Res.string.comm_timeline_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp).testTag(CommTags.EMPTY_TIMELINE),
                    )
                else ->
                    LazyColumn(modifier = Modifier.fillMaxSize().testTagA11y(CommTags.TIMELINE)) {
                        items(state.messages, key = { it.message.id }) { item ->
                            MessageRow(item, agents)
                        }
                    }
            }
        }
        val channelName = state.channels.firstOrNull { it.id == state.selectedChannelId }?.name ?: ""
        Composer(canWrite = state.canWrite, sendError = state.sendError, channelName = channelName, onSend = onSend)
    }
}

@Composable
private fun MessageRow(item: MessageItem, agents: Map<String, Agent>) {
    val agent = agents[item.message.from]
    val nameColor = SenderPalette.forSender(item.message.from, agent?.role).nameAccent
    val displayName = agent?.name ?: item.message.from
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTagA11y(CommTags.message(item.message.id))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Avatar — the shared AgentAvatar (CYP-216): initials + identity colour + CYP-209 ring, honouring the
        // agent's custom colour (CYP-211). Name accent stays the CYP-14 slot tint (readable on the surface).
        AgentAvatarView(
            id = item.message.from,
            size = 28.dp,
            displayName = displayName,
            role = agent?.role,
            colorHex = agent?.color,
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(displayName, color = nameColor, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                if (agent?.role == Role.PO) KindBadge(stringResource(Res.string.agent_role_po))
                item.message.meta?.kind?.let { KindBadge(it.name) }
                if (item.pending) Text("· " + stringResource(Res.string.comm_msg_pending), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            Text(item.message.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun KindBadge(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun ConnectionBanner(connection: ConnectionStatus) {
    if (connection == ConnectionStatus.LIVE) return
    val text = when (connection) {
        ConnectionStatus.CONNECTING -> stringResource(Res.string.comm_status_connecting)
        // Shared offline/stale text — one source (CYP-51): the same key the ACL matrix uses (CYP-48).
        ConnectionStatus.DISCONNECTED -> stringResource(Res.string.comm_status_offline)
        ConnectionStatus.LIVE -> ""
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .testTag(CommTags.CONNECTION)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun Composer(canWrite: Boolean, sendError: String?, channelName: String, onSend: (String) -> Unit) {
    if (!canWrite) {
        // Proactive read-only STATE (you may read, just not write) — distinct from a denied send attempt
        // (#6 comm_send_denied) and a generic failure (#7 comm_send_failed). Disclosure must stay separate.
        Text(
            text = stringResource(Res.string.comm_readonly_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(12.dp).testTag(CommTags.COMPOSER_READONLY),
        )
        return
    }
    var draft by remember { mutableStateOf("") }
    fun submit() {
        if (draft.isNotBlank()) {
            onSend(draft)
            draft = ""
        }
    }
    Column {
        sendError?.let {
            // The VM emits a key (comm_send_denied = ACL-rejected attempt) vs the generic else
            // (comm_send_failed). Both distinct from the proactive read-only hint above (CYP-53 §1).
            val msg = if (it == "comm_send_denied") stringResource(Res.string.comm_send_denied) else stringResource(Res.string.comm_send_failed)
            Text(msg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp))
        }
        // CYP-26 §2.2: input holds a min width; below a threshold "Senden" degrades to a glyph (a11y
        // label kept) and the placeholder ellipsizes rather than character-wrapping.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compact = maxWidth < COMPOSER_MIN_WIDTH.dp + 96.dp
            val sendLabel = stringResource(Res.string.comm_composer_send)
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f).widthIn(min = COMPOSER_MIN_WIDTH.dp).testTag(CommTags.COMPOSER_INPUT),
                    placeholder = {
                        Text(
                            stringResource(Res.string.comm_composer_placeholder, channelName),
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    singleLine = true,
                )
                Button(
                    onClick = { submit() },
                    modifier = Modifier
                        .testTag(CommTags.COMPOSER_SEND)
                        .then(if (compact) Modifier.semantics { contentDescription = sendLabel } else Modifier),
                ) {
                    Text(if (compact) "➤" else sendLabel)
                }
            }
        }
    }
}

private fun kindGlyph(kind: ChannelKind): String = when (kind) {
    ChannelKind.HUB -> "★"
    ChannelKind.DIRECT -> "@"
    ChannelKind.GROUP -> "#"
}
