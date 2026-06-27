package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.model.Event
import java.security.SecureRandom
import java.util.random.RandomGenerator
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [EventSink] double (PRD §3.2 / §10 DoD: "In-Memory-Test-Double existiert"). Mirrors the
 * SQLite sink's contract — stamps under a write lock so `seq` is gapless and total-ordered, pages
 * over `seq`, and live-pushes via a SharedFlow — without any persistence. Used by tests and as the
 * default sink before a `sinkPath` is configured.
 */
class InMemoryEventSink(
    private val time: TimeSource,
    private val rnd: RandomGenerator = SecureRandom(),
) : EventSink {
    private val mutex = Mutex()
    private val events = ArrayList<Event>() // append order == seq order (mutex serializes stamping)
    private val stream = MutableSharedFlow<Event>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST, // never block the appender; live-tail is best-effort
    )

    override suspend fun appendBatch(drafts: List<EventDraft>): List<Event> {
        if (drafts.isEmpty()) return emptyList()
        val stamped = mutex.withLock {
            drafts.map { time.stamp(it, rnd) }.also { events.addAll(it) }
        }
        stamped.forEach { stream.tryEmit(it) }
        return stamped
    }

    override suspend fun query(filter: EventFilter, page: Page): EventPage = mutex.withLock {
        val after = page.afterSeq
        val matched = ArrayList<Event>()
        for (e in events) { // already in ascending seq order
            if (after != null && e.seq <= after) continue
            if (!filter.matches(e)) continue
            matched.add(e)
            if (matched.size >= page.limit) break
        }
        val next = if (page.limit > 0 && matched.size >= page.limit) matched.last().seq else null
        EventPage(matched, next)
    }

    override fun subscribe(filter: EventFilter): Flow<Event> = stream.asSharedFlow().filter(filter::matches)

    /** Test/diagnostic snapshot of everything stored, in seq order. */
    suspend fun all(): List<Event> = mutex.withLock { events.toList() }
}
