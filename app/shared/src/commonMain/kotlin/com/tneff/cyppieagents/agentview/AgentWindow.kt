package com.tneff.cyppieagents.agentview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.window.COMPOSER_MIN_WIDTH
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_assistant_streaming
import kmpcyppieagents.app.shared.generated.resources.a11y_notice
import kmpcyppieagents.app.shared.generated.resources.a11y_result_error
import kmpcyppieagents.app.shared.generated.resources.a11y_result_success
import kmpcyppieagents.app.shared.generated.resources.a11y_tool_error
import kmpcyppieagents.app.shared.generated.resources.a11y_tool_ok
import kmpcyppieagents.app.shared.generated.resources.a11y_tool_running
import org.jetbrains.compose.resources.stringResource

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
        itemsIndexed(events, key = { _, event -> event.id }) { index, event ->
            when (event) {
                is AgentEvent.AssistantText -> AssistantTextRow(
                    event,
                    Modifier.testTag(AgentViewTags.event(agentId, index, EventKind.ASSISTANT_TEXT)),
                )
                is AgentEvent.ToolCall -> ToolCallRow(
                    event,
                    Modifier.testTag(AgentViewTags.event(agentId, index, EventKind.TOOL_CALL)),
                )
                is AgentEvent.Result -> ResultRow(
                    event,
                    Modifier.testTag(AgentViewTags.event(agentId, index, EventKind.TOOL_RESULT)),
                )
                // Notice has no kind in the v0.4 vocabulary → index tag only (kind qualifier is optional).
                is AgentEvent.Notice -> NoticeRow(
                    event,
                    Modifier.testTag(AgentViewTags.event(agentId, index)),
                )
            }
        }
    }
}

@Composable
private fun AssistantTextRow(event: AgentEvent.AssistantText, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = event.text,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!event.complete) {
            // The cursor is a glyph; expose "Agent is typing" to the screen reader instead (CYP-23).
            val streamingDesc = stringResource(Res.string.a11y_assistant_streaming)
            Text(
                text = "▌",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clearAndSetSemantics { contentDescription = streamingDesc },
            )
        }
    }
}

@Composable
private fun ToolCallRow(event: AgentEvent.ToolCall, modifier: Modifier = Modifier) {
    val (glyph, tint) = when (event.status) {
        ToolStatus.RUNNING -> "⟳" to MaterialTheme.colorScheme.tertiary
        ToolStatus.OK -> "✓" to MaterialTheme.colorScheme.primary
        ToolStatus.ERROR -> "✗" to MaterialTheme.colorScheme.error
    }
    // Glyph + colour alone aren't screen-reader accessible; announce the status as text (CYP-23).
    // Disclosure-true: OK = "ausgeführt", not "erfolgreich".
    val statusDescription = when (event.status) {
        ToolStatus.RUNNING -> stringResource(Res.string.a11y_tool_running, event.tool)
        ToolStatus.OK -> stringResource(Res.string.a11y_tool_ok, event.tool)
        ToolStatus.ERROR -> stringResource(Res.string.a11y_tool_error, event.tool)
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = glyph,
            color = tint,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clearAndSetSemantics { contentDescription = statusDescription },
        )
        Text(
            text = "${event.tool}(${event.summary})",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ResultRow(event: AgentEvent.Result, modifier: Modifier = Modifier) {
    val container =
        if (event.isError) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceVariant
    val content =
        if (event.isError) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    // Disclosure-true: success = "Turn abgeschlossen", not "erledigt" (CYP-23).
    // M1 (UIUX): the success `label` carries substance (masked tool-result content), so expose it to
    // the screen reader too — Equal Access — appended after the disclosure-safe base.
    val resultDescription =
        if (event.isError) {
            stringResource(Res.string.a11y_result_error, event.label)
        } else {
            val base = stringResource(Res.string.a11y_result_success)
            if (event.label.isBlank()) base else "$base — ${event.label}"
        }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(container)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = event.label,
            color = content,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.clearAndSetSemantics { contentDescription = resultDescription },
        )
    }
}

@Composable
private fun NoticeRow(event: AgentEvent.Notice, modifier: Modifier = Modifier) {
    val noticeDescription = stringResource(Res.string.a11y_notice, event.text)
    Text(
        text = event.text,
        color = MaterialTheme.colorScheme.outline,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = noticeDescription },
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
    // CYP-26 §2.2: keep the input usable when the window is narrow. The input holds a min width; below
    // a threshold the "Senden" label degrades to a glyph (a11y label preserved) so nothing is truncated.
    BoxWithConstraints(modifier = modifier) {
        val compact = maxWidth < COMPOSER_MIN_WIDTH.dp + 96.dp
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f).widthIn(min = COMPOSER_MIN_WIDTH.dp).testTag(AgentViewTags.input(agentId)),
                // Placeholder degrades by ellipsis, never character-wrap, in a narrow field.
                placeholder = { Text("Nachricht an den Agenten…", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                singleLine = true,
            )
            Button(
                onClick = { submit() },
                modifier = Modifier
                    .testTag(AgentViewTags.sendBtn(agentId))
                    .then(if (compact) Modifier.semantics { contentDescription = "Senden" } else Modifier),
            ) {
                Text(if (compact) "➤" else "Senden")
            }
        }
    }
}
