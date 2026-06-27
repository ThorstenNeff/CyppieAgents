package com.tneff.cyppieagents.eventlog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.event_detail_source_ts
import kmpcyppieagents.app.shared.generated.resources.event_drilldown_correlated_by
import kmpcyppieagents.app.shared.generated.resources.event_drilldown_show_run
import kmpcyppieagents.app.shared.generated.resources.event_drilldown_show_session
import kmpcyppieagents.app.shared.generated.resources.event_empty
import kmpcyppieagents.app.shared.generated.resources.event_filter_active
import kmpcyppieagents.app.shared.generated.resources.event_filter_agent
import kmpcyppieagents.app.shared.generated.resources.event_filter_correlation
import kmpcyppieagents.app.shared.generated.resources.event_filter_severity
import kmpcyppieagents.app.shared.generated.resources.event_filter_timewindow
import kmpcyppieagents.app.shared.generated.resources.event_filter_type
import kmpcyppieagents.app.shared.generated.resources.event_load_more
import org.jetbrains.compose.resources.stringResource

/**
 * Browse panel (CYP-41, design EVENT-LOG-UI §6): a seq-paged master table of [EventRow]s + a detail
 * pane whose **two explicit drilldown actions** ("show the whole run" by correlationId, "show the whole
 * session" by sessionId — never a conflated fallback, §6.3) re-query into a seq-ordered timeline.
 * Disclosure-honest: an active filter is announced ("subset"), `sourceTs` is labelled "observed", and a
 * `log.dropped` event renders as a gap row inside the timeline. Renders the inner area only.
 */
@Composable
fun EventBrowsePanel(viewModel: EventBrowseViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    Row(modifier = modifier.fillMaxSize()) {
        MasterPane(
            state = state,
            onSelect = viewModel::select,
            onLoadMore = viewModel::loadMore,
            onClearDrilldown = viewModel::clearDrilldown,
            onApplyFilter = viewModel::applyFilter,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        DetailPane(
            state = state,
            onShowRun = viewModel::showRun,
            onShowSession = viewModel::showSession,
            modifier = Modifier.width(300.dp).fillMaxHeight(),
        )
    }
}

@Composable
private fun MasterPane(
    state: EventBrowseUiState,
    onSelect: (com.tneff.cyppieagents.model.Event) -> Unit,
    onLoadMore: () -> Unit,
    onClearDrilldown: () -> Unit,
    onApplyFilter: (EventFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        FilterBar(state, onApplyFilter)
        if (state.drilldown != null) {
            DrilldownView(state, onClearDrilldown, modifier = Modifier.weight(1f).fillMaxWidth())
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (state.events.isEmpty() && !state.loading) {
                    Text(
                        text = stringResource(Res.string.event_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp).testTag(EventBrowseTags.EMPTY),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().testTag(EventBrowseTags.TABLE)) {
                        itemsIndexed(state.events, key = { _, e -> e.id }) { index, event ->
                            EventRow(
                                event = event,
                                rowTag = EventBrowseTags.row(index),
                                qualifierTag = EventBrowseTags.row(index, rowQualifier(event)),
                                byIdTag = EventBrowseTags.rowById(event.id),
                                onClick = { onSelect(event) },
                            )
                        }
                    }
                }
            }
            if (state.hasMore) {
                Button(onClick = onLoadMore, modifier = Modifier.padding(8.dp).testTag(EventBrowseTags.LOAD_MORE)) {
                    Text(stringResource(Res.string.event_load_more))
                }
            }
        }
    }
}

@Composable
private fun FilterBar(state: EventBrowseUiState, onApply: (EventFilter) -> Unit) {
    val f = state.filter
    val agents = state.events.map { it.agentId }.distinct()
    Column(modifier = Modifier.fillMaxWidth().testTag(EventBrowseTags.FILTER_BAR).padding(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // Interactive cycle chips → applyFilter → server-side query (no client-side post-filter, §6.2).
            FilterCycleChip(EventBrowseTags.FILTER_AGENT, stringResource(Res.string.event_filter_agent), f.agentId) {
                onApply(f.copy(agentId = cycle(f.agentId, agents)))
            }
            FilterCycleChip(EventBrowseTags.FILTER_TYPE, stringResource(Res.string.event_filter_type), f.type?.wire) {
                onApply(f.copy(type = cycle(f.type, TYPE_CYCLE)))
            }
            FilterCycleChip(EventBrowseTags.FILTER_SEVERITY, stringResource(Res.string.event_filter_severity), f.severity?.name) {
                onApply(f.copy(severity = cycle(f.severity, SEVERITY_CYCLE)))
            }
            // Time window + correlation: present per contract; the correlation axis is driven via the drilldown.
            Text(stringResource(Res.string.event_filter_timewindow), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(EventBrowseTags.FILTER_TIME_WINDOW))
            Text(stringResource(Res.string.event_filter_correlation), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(EventBrowseTags.FILTER_CORRELATION))
        }
        // Honest "subset" cue so a filtered result is never misread as "nothing happened" (§6.2).
        if (f != EventFilter()) {
            Text(
                text = stringResource(Res.string.event_filter_active),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(EventBrowseTags.FILTER_ACTIVE),
            )
        }
    }
}

/** A clickable filter chip that cycles its axis on tap; the current value is shown when set. */
@Composable
private fun FilterCycleChip(tag: String, label: String, value: String?, onClick: () -> Unit) {
    Text(
        text = if (value != null) "$label: $value" else label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (value != null) FontWeight.SemiBold else FontWeight.Normal,
        color = if (value != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(onClick = onClick).testTag(tag).padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** Cycle a nullable filter axis: null → first → … → last → null (a tap can also clear it). */
private fun <T> cycle(current: T?, options: List<T>): T? {
    if (options.isEmpty()) return null
    return options.getOrNull(options.indexOf(current) + 1)
}

private val SEVERITY_CYCLE = Severity.entries.toList()
private val TYPE_CYCLE = listOf(EventType.TURN_START, EventType.TOOL_CALL, EventType.RESULT_FINAL, EventType.ERROR_RATELIMIT)

@Composable
private fun DrilldownView(state: EventBrowseUiState, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val axisValue = state.filter.correlationId ?: state.filter.sessionId ?: "—"
    Column(modifier = modifier.testTag(EventBrowseTags.DRILLDOWN)) {
        // Header names the axis + scope explicitly (§6.3) and clears the drilldown on click.
        Text(
            text = stringResource(Res.string.event_drilldown_correlated_by, axisValue),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClear)
                .padding(horizontal = 12.dp, vertical = 6.dp).testTag(EventBrowseTags.DRILLDOWN_HEADER),
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(state.events, key = { _, e -> e.id }) { index, event ->
                EventRow(
                    event = event,
                    rowTag = EventBrowseTags.drilldownRow(index),
                    qualifierTag = "${EventBrowseTags.drilldownRow(index)}.${rowQualifier(event)}",
                    byIdTag = "${EventBrowseTags.DRILLDOWN}.rowById.${event.id}",
                )
            }
        }
    }
}

@Composable
private fun DetailPane(
    state: EventBrowseUiState,
    onShowRun: (com.tneff.cyppieagents.model.Event) -> Unit,
    onShowSession: (com.tneff.cyppieagents.model.Event) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sel = state.selected
    Column(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)) {
        if (sel == null) return@Column
        Column(modifier = Modifier.fillMaxWidth().testTag(EventBrowseTags.DETAIL)) {
            Text("${sel.type.wire} · ${severityLabel(sel.severity)}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            Text("${formatTs(sel.ts)} · seq ${sel.seq} · ${sel.agentId}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // sourceTs is "observed", never authoritative (§5.2).
            sel.sourceTs?.let {
                Text(
                    text = stringResource(Res.string.event_detail_source_ts, formatTs(it)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(EventBrowseTags.DETAIL_SOURCE_TS),
                )
            }
            // Content-free metadata payload, as-is (§5.5 — nothing fabricated).
            Text(
                text = sel.detail.toString(),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag(EventBrowseTags.DETAIL_JSON),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Each action enabled only if its field is present — never guessed (§6.3).
                Button(onClick = { onShowRun(sel) }, enabled = sel.correlationId != null, modifier = Modifier.testTag(EventBrowseTags.DETAIL_SHOW_RUN)) {
                    Text(stringResource(Res.string.event_drilldown_show_run))
                }
                Button(onClick = { onShowSession(sel) }, enabled = sel.sessionId != null, modifier = Modifier.testTag(EventBrowseTags.DETAIL_SHOW_SESSION)) {
                    Text(stringResource(Res.string.event_drilldown_show_session))
                }
            }
        }
    }
}
