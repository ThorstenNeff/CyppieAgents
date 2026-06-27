package com.tneff.cyppieagents.eventlog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Immutable UI state for the Live-Tail surface (CYP-42). */
data class EventTailUiState(
    val events: List<Event> = emptyList(),
    val connection: ConnectionStatus = ConnectionStatus.CONNECTING,
    val paused: Boolean = false,
    /** Events buffered while paused, awaiting a flush on resume. */
    val pendingCount: Int = 0,
    /** Cumulative count of old events trimmed by the ring cap — surfaced, never silent (PRD §3.3). */
    val trimmedCount: Int = 0,
)

/**
 * Drives the Live-Tail surface (CYP-42): folds the live [EventLiveSource] stream into a capped,
 * seq-ordered, id-deduped ring buffer. **Pause** freezes the visible list and buffers incoming events;
 * **resume** flushes them in one merge — no loss, no duplicates. Deliberately separate from Browse
 * (PRD §6). Caps are client memory bounds (PO answer 4), surfaced via [EventTailUiState], never silent.
 *
 * Single-collector / single-dispatcher VM: [onReceived], [pause] and [resume] never run concurrently
 * (collect + UI callbacks share the VM's dispatcher), so [pending] needs no extra synchronization.
 *
 * [scope] is injectable so tests drive it deterministically without `viewModelScope`/Main.
 */
class EventTailViewModel(
    private val source: EventLiveSource,
    private val filter: EventFilter = EventFilter(),
    private val ringCapacity: Int = TAIL_RING_DEFAULT,
    private val pauseBufferCapacity: Int = PAUSE_BUFFER_DEFAULT,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope
    private val _state = MutableStateFlow(EventTailUiState())
    val state: StateFlow<EventTailUiState> = _state.asStateFlow()

    /** Events received while paused (bounded by [pauseBufferCapacity]); not visible until resume. */
    private val pending = mutableListOf<Event>()

    init { runScope.launch { collect() } }

    private suspend fun collect() {
        source.events(filter).collect { event ->
            EventReducer.statusOf(event)?.let { status -> _state.update { it.copy(connection = status) } }
            if (event is EventLiveEvent.Received) onReceived(event.event)
        }
    }

    private fun onReceived(event: Event) {
        if (_state.value.paused) {
            pending.add(event)
            var trimmedExtra = 0
            if (pending.size > pauseBufferCapacity) {
                // Overflowing the pause buffer is still a (visible) trim, not a silent drop.
                val capped = EventReducer.capTail(pending, pauseBufferCapacity)
                pending.clear(); pending.addAll(capped.events)
                trimmedExtra = capped.trimmed
            }
            _state.update { it.copy(pendingCount = pending.size, trimmedCount = it.trimmedCount + trimmedExtra) }
        } else {
            val capped = EventReducer.capTail(EventReducer.merge(_state.value.events, event), ringCapacity)
            _state.update { it.copy(events = capped.events, trimmedCount = it.trimmedCount + capped.trimmed) }
        }
    }

    fun pause() = _state.update { it.copy(paused = true) }

    /** Flush the pause buffer into the visible tail in one merge (no loss, no dup), then unpause. */
    fun resume() {
        if (!_state.value.paused) return
        val flushed = EventReducer.capTail(EventReducer.mergeAll(_state.value.events, pending.toList()), ringCapacity)
        pending.clear()
        _state.update {
            it.copy(
                events = flushed.events, paused = false, pendingCount = 0,
                trimmedCount = it.trimmedCount + flushed.trimmed,
            )
        }
    }

    companion object {
        /** Visible ring buffer bound (PO answer 4). */
        const val TAIL_RING_DEFAULT = 1000

        /** Pause buffer bound (PO answer 4). */
        const val PAUSE_BUFFER_DEFAULT = 5000
    }
}
