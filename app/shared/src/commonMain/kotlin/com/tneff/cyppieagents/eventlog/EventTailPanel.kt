package com.tneff.cyppieagents.eventlog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import kmpcyppieagents.app.shared.generated.resources.event_access_denied
import kmpcyppieagents.app.shared.generated.resources.event_connection_offline
import kmpcyppieagents.app.shared.generated.resources.event_empty
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import kmpcyppieagents.app.shared.generated.resources.event_filter_agent
import kmpcyppieagents.app.shared.generated.resources.event_filter_project
import kmpcyppieagents.app.shared.generated.resources.event_filter_project_all
import kmpcyppieagents.app.shared.generated.resources.event_filter_severity
import kmpcyppieagents.app.shared.generated.resources.event_filter_type
import kmpcyppieagents.app.shared.generated.resources.event_view_all_projects
import kmpcyppieagents.app.shared.generated.resources.event_view_project
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
fun EventTailPanel(
    viewModel: EventTailViewModel,
    modifier: Modifier = Modifier,
    // CYP-94: the operator's projects + active id drive the cross-project lens (re-subscribes the tail).
    projects: List<com.tneff.cyppieagents.model.Project> = emptyList(),
    activeProjectId: String = "",
    // CYP-224: id→Agent map (from the shell's managedAgents) → the log avatar honours custom colour/avatar/role.
    agents: Map<String, com.tneff.cyppieagents.model.Agent> = emptyMap(),
) {
    val state by viewModel.state.collectAsState()
    val isCrossView = state.projectId != null
    Column(modifier = modifier.fillMaxSize()) {
        TailHeader(
            state, onToggle = { if (state.paused) viewModel.resume() else viewModel.pause() },
            projects = projects, activeProjectId = activeProjectId, onApplyProject = viewModel::applyProjectFilter,
        )
        // Fail-closed: an operator-token reject (WS 1008) shows an honest "operators only", never "live".
        if (state.accessRevoked) {
            Text(
                text = stringResource(Res.string.event_access_denied),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer)
                    .testTag(EventTailTags.ACCESS_REVOKED).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
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
                            showProject = isCrossView,
                            projectTag = EventTailTags.rowProject(index),
                            agents = agents,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TailHeader(
    state: EventTailUiState,
    onToggle: () -> Unit,
    projects: List<com.tneff.cyppieagents.model.Project>,
    activeProjectId: String,
    onApplyProject: (String?) -> Unit,
) {
    // CYP-158 §2.1: the Pause control + the Live/Paused indicator stay PROMINENT on the first line; the
    // filter chips flow in a FlowRow below (wrap on a phone instead of clipping). Pause/Live disclosure
    // (§7.2 "paused ≠ live") is unchanged — only the chip container wraps. Same nodes + tags.
    Column(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
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
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.event_filter_agent), style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag(EventTailTags.FILTER_AGENT))
            Text(stringResource(Res.string.event_filter_type), style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag(EventTailTags.FILTER_TYPE))
            Text(stringResource(Res.string.event_filter_severity), style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag(EventTailTags.FILTER_SEVERITY))
            // CYP-94: interactive project lens — cycle null(active) → other projects → all → null (re-subscribes).
            val projectCycle = projects.map { it.id }.filter { it != activeProjectId } + EventFilter.PROJECT_ALL
            val projectChip = when (state.projectId) {
                null -> stringResource(Res.string.event_filter_project)
                EventFilter.PROJECT_ALL -> stringResource(Res.string.event_filter_project) + ": " + stringResource(Res.string.event_filter_project_all)
                else -> stringResource(Res.string.event_filter_project) + ": " + (projects.firstOrNull { it.id == state.projectId }?.name ?: state.projectId)
            }
            Text(
                text = projectChip,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (state.projectId != null) FontWeight.SemiBold else FontWeight.Normal,
                color = if (state.projectId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable { onApplyProject(cycleProject(state.projectId, projectCycle)) }
                    .testTag(EventTailTags.FILTER_PROJECT),
            )
        }
    }
    // CYP-94: cross-project view indicator — foreign events never mistaken for the active project's.
    if (state.projectId != null) {
        val viewText = if (state.projectId == EventFilter.PROJECT_ALL) {
            stringResource(Res.string.event_view_all_projects)
        } else {
            stringResource(Res.string.event_view_project, projects.firstOrNull { it.id == state.projectId }?.name ?: state.projectId)
        }
        TonedHint(viewText, HintTone.INFO, EventTailTags.CROSS_PROJECT_VIEW)
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

/** Cycle the project lens: null(active) → first other → … → `all` → null (a tap can also clear it). */
private fun cycleProject(current: String?, options: List<String>): String? {
    if (options.isEmpty()) return null
    return options.getOrNull(options.indexOf(current) + 1)
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
