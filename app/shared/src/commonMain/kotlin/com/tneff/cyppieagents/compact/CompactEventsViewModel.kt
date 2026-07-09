package com.tneff.cyppieagents.compact

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tneff.cyppieagents.eventlog.EventFilter
import com.tneff.cyppieagents.eventlog.EventLiveEvent
import com.tneff.cyppieagents.eventlog.EventLiveSource
import com.tneff.cyppieagents.eventlog.EventsApi
import com.tneff.cyppieagents.eventlog.Page
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.net.reconnecting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * CYP-327 Feature B — the source of compact-orchestration events for the compact window. Reads the operator event
 * feed (history via [EventsApi] + live via [EventLiveSource]) and keeps ONLY the four orchestration event types —
 * `compact.prepare.sent` / `compact.request.sent` / `compact.completed` / `compact.orchestration.done`.
 *
 * **NOT `COMPACT_TRIGGERED`** (PO): that is the native-compaction observation type and would pull foreign events
 * into a run. **No run-scoping here** — the VM exposes every compact event seen; the panel scopes to a single run
 * by the AUTHORITATIVE key `status.lastRun.correlationId` (each event carries the same `correlationId`, stamped
 * server-side), so the list shows exactly one run and rolls to the next automatically.
 *
 * The feed is operator-gated (like the event-log windows), so the shell wires this VM for an operator only.
 */
class CompactEventsViewModel(
    private val eventsApi: EventsApi,
    private val liveSource: EventLiveSource,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope

    // Every compact event seen, de-duplicated by id; the panel derives the current run from these.
    private val seen = LinkedHashMap<String, Event>()

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events.asStateFlow()

    init {
        runScope.launch {
            seedHistory()
            collectLive()
        }
    }

    /** Seed from history — a failed read leaves the honest empty list (never a fabricated event). */
    private suspend fun seedHistory() {
        runCatching { eventsApi.query(EventFilter(), Page(limit = HISTORY_LIMIT)) }.getOrNull()
            ?.events?.forEach { if (isCompact(it)) seen[it.id] = it }
        publish()
    }

    private suspend fun collectLive() {
        liveSource.events(EventFilter()).reconnecting().collect { live ->
            if (live is EventLiveEvent.Received && isCompact(live.event)) {
                seen[live.event.id] = live.event
                publish()
            }
        }
    }

    private fun isCompact(e: Event): Boolean = e.type in COMPACT_TYPES

    private fun publish() {
        _events.value = seen.values.sortedBy { it.seq }
    }

    private companion object {
        const val HISTORY_LIMIT = 200

        /** Exactly the four orchestration events — deliberately EXCLUDING [EventType.COMPACT_TRIGGERED]. */
        val COMPACT_TYPES = setOf(
            EventType.COMPACT_PREPARE_SENT,
            EventType.COMPACT_REQUEST_SENT,
            EventType.COMPACT_COMPLETED,
            EventType.COMPACT_ORCHESTRATION_DONE,
        )
    }
}
