package com.tneff.cyppieagents.comm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Role
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.agent_role_po
import kmpcyppieagents.app.shared.generated.resources.comm_status_offline
import org.jetbrains.compose.resources.stringResource

/**
 * Comm panel (CYP-21, design CYP-17): master channel list + detail timeline + composer. Renders the
 * inner area only — the window chrome comes from the host (CYP-10/CYP-15). Honest states throughout:
 * only ACL-readable channels appear (server-filtered), the live banner reflects the real socket
 * state, and optimistic sends are clearly marked until confirmed.
 */
@Composable
fun CommPanel(viewModel: CommViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    Row(modifier = modifier.fillMaxSize()) {
        ChannelListPane(
            channels = state.channels,
            selectedId = state.selectedChannelId,
            onSelect = viewModel::select,
            modifier = Modifier.width(220.dp).fillMaxSize(),
        )
        TimelinePane(
            state = state,
            agents = state.agents,
            onSend = viewModel::send,
            modifier = Modifier.weight(1f).fillMaxSize(),
        )
    }
}

@Composable
private fun ChannelListPane(
    channels: List<Channel>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (channels.isEmpty()) {
            Text(
                text = "Keine lesbaren Kanäle",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp).testTag(CommTags.EMPTY_CHANNELS),
            )
            return@Column
        }
        LazyColumn(modifier = Modifier.fillMaxSize().testTag(CommTags.CHANNEL_LIST)) {
            items(channels, key = { it.id }) { channel ->
                ChannelRow(channel, selected = channel.id == selectedId, onClick = { onSelect(channel.id) })
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
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ConnectionBanner(state.connection)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.selectedChannelId == null -> Unit
                state.messages.isEmpty() && !state.loadingHistory ->
                    Text(
                        text = "Noch keine Nachrichten",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp).testTag(CommTags.EMPTY_TIMELINE),
                    )
                else ->
                    LazyColumn(modifier = Modifier.fillMaxSize().testTag(CommTags.TIMELINE)) {
                        items(state.messages, key = { it.message.id }) { item ->
                            MessageRow(item, agents)
                        }
                    }
            }
        }
        Composer(canWrite = state.canWrite, sendError = state.sendError, onSend = onSend)
    }
}

@Composable
private fun MessageRow(item: MessageItem, agents: Map<String, Agent>) {
    val agent = agents[item.message.from]
    val color = SenderPalette.forSender(item.message.from, agent?.role)
    val displayName = agent?.name ?: item.message.from
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(CommTags.message(item.message.id))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Avatar = initials (CYP-14), colour from the slot.
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape).background(color.avatarFill),
            contentAlignment = Alignment.Center,
        ) {
            Text(initialsOf(displayName), color = color.onAvatar, style = MaterialTheme.typography.labelSmall)
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(displayName, color = color.nameAccent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                if (agent?.role == Role.PO) KindBadge(stringResource(Res.string.agent_role_po))
                item.message.meta?.kind?.let { KindBadge(it.name) }
                if (item.pending) Text("· wird gesendet", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
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
        ConnectionStatus.CONNECTING -> "Verbinde…"
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
private fun Composer(canWrite: Boolean, sendError: String?, onSend: (String) -> Unit) {
    if (!canWrite) {
        Text(
            text = "Keine Schreibrechte in diesem Kanal",
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
            val msg = if (it == "comm_send_denied") "Keine Schreibrechte in diesem Kanal" else "Senden fehlgeschlagen"
            Text(msg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f).testTag(CommTags.COMPOSER_INPUT),
                placeholder = { Text("Nachricht an den Kanal…") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                singleLine = true,
            )
            Button(onClick = { submit() }, modifier = Modifier.testTag(CommTags.COMPOSER_SEND)) {
                Text("Senden")
            }
        }
    }
}

private fun kindGlyph(kind: ChannelKind): String = when (kind) {
    ChannelKind.HUB -> "★"
    ChannelKind.DIRECT -> "@"
    ChannelKind.GROUP -> "#"
}
