package com.tneff.cyppieagents.eventlog

import com.tneff.cyppieagents.model.Event

/** Honest connection state for the live-tail banner (mirrors comm CYP-17 §5). */
enum class ConnectionStatus { CONNECTING, LIVE, DISCONNECTED }

/** Result of capping a tail buffer: the kept window plus how many old events were [trimmed]. */
data class CappedTail(val events: List<Event>, val trimmed: Int)

/**
 * Pure event-log reducers. Identity is [Event.id] (ULID) → dedupe across reconnect/backfill; ordering
 * is [Event.seq] (monotonic total order, the append-time authority), with [Event.id] only as a defensive
 * tiebreaker. No optimistic/pending state — the log is read-only. Mirrors `CommReducer`'s philosophy.
 */
object EventReducer {

    private val ORDER: Comparator<Event> = compareBy<Event> { it.seq }.thenBy { it.id }

    /** Inserts or replaces [event] by id, keeping the list ordered by seq. Idempotent. */
    fun merge(events: List<Event>, event: Event): List<Event> =
        (events.filterNot { it.id == event.id } + event).sortedWith(ORDER)

    /** Merges a batch (REST page or reconnect backfill), deduping by id. Idempotent across replays. */
    fun mergeAll(events: List<Event>, batch: List<Event>): List<Event> =
        batch.fold(events) { acc, e -> merge(acc, e) }

    /**
     * Bounds a live-tail buffer to the most recent [max] events (by seq), returning the kept window and
     * the number trimmed — surfaced in the UI ("ältere getrimmt (N)"), never a silent cap (PRD §3.3).
     */
    fun capTail(events: List<Event>, max: Int): CappedTail {
        if (max <= 0 || events.size <= max) return CappedTail(events, 0)
        val ordered = events.sortedWith(ORDER)
        val kept = ordered.takeLast(max)
        return CappedTail(kept, ordered.size - kept.size)
    }

    /** Maps a live event to the connection status it implies, or null if it carries none. */
    fun statusOf(event: EventLiveEvent): ConnectionStatus? = when (event) {
        is EventLiveEvent.Connected -> ConnectionStatus.LIVE
        is EventLiveEvent.Disconnected -> ConnectionStatus.DISCONNECTED
        is EventLiveEvent.Received -> null
    }
}
