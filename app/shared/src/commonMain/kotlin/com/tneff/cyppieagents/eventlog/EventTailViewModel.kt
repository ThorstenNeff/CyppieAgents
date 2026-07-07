package com.tneff.cyppieagents.eventlog

import androidx.lifecycle.ViewModel
import com.tneff.cyppieagents.net.Backoff
import com.tneff.cyppieagents.net.reconnecting
import androidx.lifecycle.viewModelScope
import com.tneff.cyppieagents.model.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    /** Cumulative count of old events trimmed by the live ring cap — surfaced, never silent (PRD §3.3). */
    val trimmedCount: Int = 0,
    /** Cumulative count of paused events dropped on pause-buffer overflow — surfaced, never silent. */
    val bufferOverflowCount: Int = 0,
    /** The operator token was rejected at the socket (WS close 1008) — render fail-closed, never "live". */
    val accessRevoked: Boolean = false,
    /** CYP-94 cross-project lens: null = forced-active (default); concrete id / `all` = an explicit override. */
    val projectId: String? = null,
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
    /** CYP-289 — reconnect backoff for the live `/ws/events` tail; injectable so tests drive fast reconnects.
     *  Mirrors CommViewModel so the tail auto-heals on a socket drop (was previously honest-but-inert). */
    private val backoff: Backoff = Backoff(),
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope
    private val _state = MutableStateFlow(EventTailUiState(projectId = filter.projectId))
    val state: StateFlow<EventTailUiState> = _state.asStateFlow()

    /** Events received while paused (bounded by [pauseBufferCapacity]); not visible until resume. */
    private val pending = mutableListOf<Event>()

    /** The live filter (CYP-94: re-subscribed when the cross-project lens changes). */
    private var currentFilter = filter
    private var collectJob: Job? = null

    init { startCollect() }

    private fun startCollect() {
        collectJob?.cancel()
        collectJob = runScope.launch { collect() }
    }

    /**
     * CYP-94: switch the cross-project read lens (operator-only). Resets the visible ring + pause buffer and
     * re-subscribes with the new `projectId` (null = forced-active default; `all`/concrete id = override). The
     * WS carries it via `SubscribeEvents.projectId` (Backend `:core` seam); the stub source mirrors it.
     */
    fun applyProjectFilter(projectId: String?) {
        if (currentFilter.projectId == projectId) return
        currentFilter = currentFilter.copy(projectId = projectId)
        pending.clear()
        _state.update { it.copy(events = emptyList(), pendingCount = 0, projectId = projectId) }
        startCollect()
    }

    private suspend fun collect() {
        // CYP-289: auto-reconnect with backoff on a TRANSIENT socket drop (CYP-73 pattern, same as Comm/AgentView) —
        // each re-subscribe replays the source's `Connected` marker → statusOf() clears the DISCONNECTED banner; the
        // seq-ordered id-deduped ring drops duplicate replays.
        source.events(currentFilter).reconnecting(backoff).collect { event ->
            EventReducer.statusOf(event)?.let { s -> _state.update { it.copy(connection = s) } }
            when (event) {
                is EventLiveEvent.Received -> onReceived(event.event)
                is EventLiveEvent.AccessRevoked -> {
                    // A 1008 revoke is TERMINAL — access won't return without re-auth. Cancel the collect so
                    // `.reconnecting()` does NOT re-subscribe; otherwise a revoked client re-opens /ws/events every
                    // backoff period forever, re-sending the revoked token (a reconnect hammer, security-adjacent).
                    // Transient drops (Disconnected → the source completes without AccessRevoked) still reconnect.
                    _state.update { it.copy(accessRevoked = true) }
                    collectJob?.cancel()
                }
                else -> Unit
            }
        }
    }

    private fun onReceived(event: Event) {
        if (_state.value.paused) {
            pending.add(event)
            var overflow = 0
            if (pending.size > pauseBufferCapacity) {
                // Overflowing the pause buffer is still a (visible) cap, not a silent drop.
                val capped = EventReducer.capTail(pending, pauseBufferCapacity)
                pending.clear(); pending.addAll(capped.events)
                overflow = capped.trimmed
            }
            _state.update { it.copy(pendingCount = pending.size, bufferOverflowCount = it.bufferOverflowCount + overflow) }
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
