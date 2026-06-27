package com.tneff.cyppieagents.eventlog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventReducerTest {

    private fun ev(id: String, seq: Long) = Event(
        id = id, ts = seq, seq = seq, agentId = "a", teamId = "t",
        type = EventType.TURN_START, severity = Severity.INFO,
    )

    @Test
    fun merge_dedupesById() {
        val once = EventReducer.merge(emptyList(), ev("e1", 1))
        val twice = EventReducer.merge(once, ev("e1", 1)) // reconnect replay
        assertEquals(1, twice.size)
    }

    @Test
    fun merge_ordersBySeq() {
        var xs = emptyList<Event>()
        xs = EventReducer.merge(xs, ev("b", 3))
        xs = EventReducer.merge(xs, ev("a", 1))
        xs = EventReducer.merge(xs, ev("c", 2))
        assertEquals(listOf(1L, 2L, 3L), xs.map { it.seq })
    }

    @Test
    fun mergeAll_isIdempotentAcrossReplays() {
        val batch = listOf(ev("e1", 1), ev("e2", 2))
        val first = EventReducer.mergeAll(emptyList(), batch)
        val replayed = EventReducer.mergeAll(first, batch) // backfill catch-up after reconnect
        assertEquals(2, replayed.size)
        assertEquals(listOf("e1", "e2"), replayed.map { it.id })
    }

    @Test
    fun capTail_keepsNewestBySeqAndCountsTrimmed() {
        val xs = (1L..10L).map { ev("e$it", it) }
        val capped = EventReducer.capTail(xs, max = 4)
        assertEquals(listOf(7L, 8L, 9L, 10L), capped.events.map { it.seq })
        assertEquals(6, capped.trimmed)
    }

    @Test
    fun capTail_noTrimWhenUnderCap() {
        val xs = (1L..3L).map { ev("e$it", it) }
        assertEquals(0, EventReducer.capTail(xs, max = 4).trimmed)
    }

    @Test
    fun statusOf_mapsConnectionEvents() {
        assertEquals(ConnectionStatus.LIVE, EventReducer.statusOf(EventLiveEvent.Connected))
        assertEquals(ConnectionStatus.DISCONNECTED, EventReducer.statusOf(EventLiveEvent.Disconnected))
        assertNull(EventReducer.statusOf(EventLiveEvent.Received(ev("e", 1))))
    }
}
