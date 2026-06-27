package com.tneff.cyppieagents.eventlog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tneff.cyppieagents.model.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which correlation axis a drilldown is following (PO decision (c): two explicit axes). */
enum class DrilldownAxis { CORRELATION, SESSION }

/** Immutable UI state for the Browse surface (CYP-41). */
data class EventBrowseUiState(
    val filter: EventFilter = EventFilter(),
    val events: List<Event> = emptyList(),
    val selected: Event? = null,
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val nextAfterSeq: Long? = null,
    val drilldown: DrilldownAxis? = null,
    val error: String? = null,
)

/**
 * Drives the Browse surface (CYP-41): a filter-/seq-paged master list over [EventsApi.query] with a
 * detail selection and a correlation drilldown — "zeig den ganzen Lauf" by `correlationId` and "ganze
 * Session" by `sessionId` (two explicit axes). Read-only: no send/optimistic path. The UI virtualizes
 * the list and calls [loadMore] at the scroll end; this VM accumulates pages over the [Event.seq] cursor.
 *
 * [scope] is injectable so tests drive it deterministically without `viewModelScope`/Main.
 */
class EventBrowseViewModel(
    private val api: EventsApi,
    initialFilter: EventFilter = EventFilter(),
    private val pageSize: Int = 100,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope
    private val _state = MutableStateFlow(EventBrowseUiState(filter = initialFilter))
    val state: StateFlow<EventBrowseUiState> = _state.asStateFlow()

    /** The filter to restore when a drilldown is cleared. */
    private var baseFilter: EventFilter = initialFilter

    init { applyFilter(initialFilter, drilldown = null, base = true) }

    /** Replace the active filter and (re)load the first page. */
    fun applyFilter(filter: EventFilter) = applyFilter(filter, drilldown = null, base = true)

    private fun applyFilter(filter: EventFilter, drilldown: DrilldownAxis?, base: Boolean) {
        if (base) baseFilter = filter
        _state.update {
            it.copy(
                filter = filter, events = emptyList(), selected = null, loading = true,
                hasMore = false, nextAfterSeq = null, drilldown = drilldown, error = null,
            )
        }
        runScope.launch { loadPage(filter, afterSeq = null, reset = true) }
    }

    /** Load the next page (cursor = current [EventBrowseUiState.nextAfterSeq]); no-op if none/loading. */
    fun loadMore() {
        val s = _state.value
        if (s.loading || !s.hasMore || s.nextAfterSeq == null) return
        _state.update { it.copy(loading = true) }
        runScope.launch { loadPage(s.filter, afterSeq = s.nextAfterSeq, reset = false) }
    }

    private suspend fun loadPage(filter: EventFilter, afterSeq: Long?, reset: Boolean) {
        val result = runCatching { api.query(filter, Page(afterSeq = afterSeq, limit = pageSize)) }
        _state.update { st ->
            if (st.filter != filter) return@update st // a newer filter superseded this load
            result.fold(
                onSuccess = { page ->
                    val base = if (reset) emptyList() else st.events
                    st.copy(
                        events = EventReducer.mergeAll(base, page.events),
                        loading = false, hasMore = page.hasMore, nextAfterSeq = page.nextAfterSeq, error = null,
                    )
                },
                onFailure = { st.copy(loading = false, error = "events_load_failed") },
            )
        }
    }

    fun select(event: Event) = _state.update { it.copy(selected = event) }

    /** Drill into the full work-run of [event] (correlationId axis). No-op if it carries none. */
    fun showRun(event: Event) {
        val cid = event.correlationId ?: return
        applyFilter(EventFilter(correlationId = cid), drilldown = DrilldownAxis.CORRELATION, base = false)
    }

    /** Drill into the full session of [event] (sessionId axis, spans compaction). No-op if none. */
    fun showSession(event: Event) {
        val sid = event.sessionId ?: return
        applyFilter(EventFilter(sessionId = sid), drilldown = DrilldownAxis.SESSION, base = false)
    }

    /** Leave a drilldown, restoring the prior base filter. */
    fun clearDrilldown() {
        if (_state.value.drilldown == null) return
        applyFilter(baseFilter, drilldown = null, base = true)
    }
}
