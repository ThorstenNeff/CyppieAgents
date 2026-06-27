package com.tneff.cyppieagents.eventlog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.event_connection_offline
import kmpcyppieagents.app.shared.generated.resources.event_empty
import kmpcyppieagents.app.shared.generated.resources.event_filter_agent
import kmpcyppieagents.app.shared.generated.resources.event_filter_severity
import kmpcyppieagents.app.shared.generated.resources.event_filter_type
import kmpcyppieagents.app.shared.generated.resources.event_tail_buffer_overflow
import kmpcyppieagents.app.shared.generated.resources.event_tail_buffered_count
import kmpcyppieagents.app.shared.generated.resources.event_tail_live
import kmpcyppieagents.app.shared.generated.resources.event_tail_pause
import kmpcyppieagents.app.shared.generated.resources.event_tail_paused
import kmpcyppieagents.app.shared.generated.resources.event_tail_resume
import kmpcyppieagents.app.shared.generated.resources.event_tail_trimmed
import org.jetbrains.compose.resources.stringResource

/**
 * Live-Tail panel (CYP-42, design EVENT-LOG-UI §7): a scrolling seq-ordered ring of [EventRow]s with a
 * **Pause** toggle. Disclosure-honest throughout (§5/§7.2): the live ● shows only while open AND not
 * paused; pausing surfaces an honest buffered count; both client caps (live-ring trim + pause-buffer
 * overflow) are shown, never silent; a `log.dropped` event renders as a gap row. Renders the inner
 * area only — window chrome comes from the host (operator-gated presence in `AgentShell`).
 */
@Composable
fun EventTailPanel(viewModel: EventTailViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    Column(modifier = modifier.fillMaxSize()) {
        TailHeader(state, onToggle = { if (state.paused) viewModel.resume() else viewModel.pause() })
        TailMarkers(state)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.events.isEmpty()) {
                Text(
                    text = stringResource(Res.string.event_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp).testTag(EventTailTags.EMPTY),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().testTag(EventTailTags.STREAM)) {
                    itemsIndexed(state.events, key = { _, e -> e.id }) { index, event ->
                        EventRow(
                            event = event,
                            rowTag = EventTailTags.row(index),
                            qualifierTag = EventTailTags.row(index, rowQualifier(event)),
                            byIdTag = EventTailTags.rowById(event.id),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TailHeader(state: EventTailUiState, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onToggle, modifier = Modifier.testTag(EventTailTags.PAUSE_TOGGLE)) {
            Text(stringResource(if (state.paused) Res.string.event_tail_resume else Res.string.event_tail_pause))
        }
        // Paused != live: when paused, ONLY the paused indicator; the live ● is absent (§7.2).
        when {
            state.paused -> Text(
                text = "⏸ " + stringResource(Res.string.event_tail_paused),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(EventTailTags.PAUSED_INDICATOR),
            )
            state.connection == ConnectionStatus.LIVE -> Text(
                text = "● " + stringResource(Res.string.event_tail_live),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(EventTailTags.LIVE_INDICATOR),
            )
        }
        Text(stringResource(Res.string.event_filter_agent), style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag(EventTailTags.FILTER_AGENT))
        Text(stringResource(Res.string.event_filter_type), style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag(EventTailTags.FILTER_TYPE))
        Text(stringResource(Res.string.event_filter_severity), style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag(EventTailTags.FILTER_SEVERITY))
    }
    // Honest offline banner (never shown as live) — only when the socket is actually down.
    if (state.connection == ConnectionStatus.DISCONNECTED) {
        Text(
            text = stringResource(Res.string.event_connection_offline, "—"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer)
                .testTag(EventTailTags.CONNECTION).padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** The two visible client caps + the paused buffer count — none silent (§7.1/§7.2, tags §3). */
@Composable
private fun TailMarkers(state: EventTailUiState) {
    if (state.trimmedCount > 0) {
        Text(
            text = stringResource(Res.string.event_tail_trimmed, state.trimmedCount.toString()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).testTag(EventTailTags.TRIMMED),
        )
    }
    if (state.bufferOverflowCount > 0) {
        Text(
            text = stringResource(Res.string.event_tail_buffer_overflow),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).testTag(EventTailTags.BUFFER_OVERFLOW),
        )
    }
    if (state.paused && state.pendingCount > 0) {
        Text(
            text = stringResource(Res.string.event_tail_buffered_count, state.pendingCount.toString()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).testTag(EventTailTags.BUFFERED_COUNT),
        )
    }
}
