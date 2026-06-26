package com.tneff.cyppieagents.agentview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * The agent window: a scrolling transcript of [AgentEvent]s over a "message to the agent" composer.
 * 100% commonMain Compose — no platform-specific terminal, no expect/actual (D6, 05 §4).
 *
 * [agentId] parameterizes the test tags per the v0.2 Test-Contract (`agent.<agentId>.stream` etc.).
 */
@Composable
fun AgentWindow(
    agentId: String,
    viewModel: AgentViewModel,
    modifier: Modifier = Modifier,
) {
    val transcript by viewModel.transcript.collectAsState()
    Column(modifier = modifier.fillMaxSize()) {
        AgentTranscript(
            agentId = agentId,
            events = transcript,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        MessageComposer(
            agentId = agentId,
            onSend = viewModel::onSend,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AgentTranscript(
    agentId: String,
    events: List<AgentEvent>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Pin to bottom as new events arrive (streaming feel). A more refined version would release
    // the pin while the user scrolls up; deferred until the window manager (CYP-3) lands.
    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) listState.animateScrollToItem(events.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.testTag(AgentViewTags.stream(agentId)),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(events, key = { it.id }) { event ->
            when (event) {
                is AgentEvent.AssistantText -> AssistantTextRow(event)
                is AgentEvent.ToolCall -> ToolCallRow(event)
                is AgentEvent.Result -> ResultRow(event)
                is AgentEvent.Notice -> NoticeRow(event)
            }
        }
    }
}

@Composable
private fun AssistantTextRow(event: AgentEvent.AssistantText) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = event.text,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!event.complete) {
            Text(
                text = "▌",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ToolCallRow(event: AgentEvent.ToolCall) {
    val (glyph, tint) = when (event.status) {
        ToolStatus.RUNNING -> "⟳" to MaterialTheme.colorScheme.tertiary
        ToolStatus.OK -> "✓" to MaterialTheme.colorScheme.primary
        ToolStatus.ERROR -> "✗" to MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = glyph, color = tint, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "${event.tool}(${event.summary})",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ResultRow(event: AgentEvent.Result) {
    val container =
        if (event.isError) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceVariant
    val content =
        if (event.isError) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(container)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = event.label,
            color = content,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun NoticeRow(event: AgentEvent.Notice) {
    Text(
        text = event.text,
        color = MaterialTheme.colorScheme.outline,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MessageComposer(
    agentId: String,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }
    fun submit() {
        if (draft.isNotBlank()) {
            onSend(draft)
            draft = ""
        }
    }
    Row(
        modifier = modifier.padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f).testTag(AgentViewTags.input(agentId)),
            placeholder = { Text("Nachricht an den Agenten…") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            singleLine = true,
        )
        Button(
            onClick = { submit() },
            modifier = Modifier.testTag(AgentViewTags.sendBtn(agentId)),
        ) {
            Text("Senden")
        }
    }
}
