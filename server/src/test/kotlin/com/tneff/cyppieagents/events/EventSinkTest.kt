package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * ST1 (CYP-35) sink contract: total ordering under concurrency, stable seq-paging, live subscribe,
 * clock-jump resilience, and — for the persistent impl — restart durability + WAL + seq continuity.
 * The same assertions run against the in-memory double and the SQLite impl, proving the seam.
 */
class EventSinkTest {

    private suspend fun assertTotalOrderUnderConcurrency(sink: EventSink, n: Int) {
        coroutineScope {
            repeat(n) { i -> launch(Dispatchers.Default) { sink.append(draft(agent = "a$i")) } }
        }
        val all = sink.query(EventFilter.ALL, Page(limit = n + 10)).events
        assertEquals(n, all.size, "every appended event must be stored")
        val seqs = all.map { it.seq }
        // Ascending, contiguous, no duplicate, no gap → a total order.
        assertEquals((1L..n).toList(), seqs, "seq must be a gapless 1..n total order")
        assertEquals(n, seqs.toSet().size, "seq must be unique")
    }

    @Test
    fun inMemory_totalOrder_underConcurrentWriters() = runBlocking {
        assertTotalOrderUnderConcurrency(InMemoryEventSink(SystemTimeSource()), n = 500)
    }

    @Test
    fun sqlite_totalOrder_underConcurrentWriters() = runBlocking {
        withTempDb { db ->
            val sink = SqliteEventSink(db, SystemTimeSource())
            try {
                assertTotalOrderUnderConcurrency(sink, n = 500)
            } finally {
                sink.close()
            }
        }
    }

    @Test
    fun query_pagesStablyOverSeq_andFilters() = runBlocking {
        val sink = InMemoryEventSink(SystemTimeSource())
        repeat(10) { sink.append(draft(agent = if (it % 2 == 0) "even" else "odd")) }
        val f = EventFilter(agentId = "even") // 5 events at seq 1,3,5,7,9
        val p1 = sink.query(f, Page(limit = 3))
        assertEquals(3, p1.events.size)
        assertTrue(p1.hasMore)
        assertNotNull(p1.nextAfterSeq)
        val p2 = sink.query(f, Page(afterSeq = p1.nextAfterSeq, limit = 3))
        assertEquals(2, p2.events.size)
        assertFalse(p2.hasMore)
        assertNull(p2.nextAfterSeq)
        val combined = p1.events + p2.events
        assertEquals(combined.map { it.seq }.sorted(), combined.map { it.seq }, "paging is monotonic over seq")
        assertTrue(combined.all { it.agentId == "even" }, "filter must hold across pages")
    }

    @Test
    fun subscribe_pushesNewlyAppendedEvents() = runBlocking {
        val sink = InMemoryEventSink(SystemTimeSource())
        val received = CompletableDeferred<Event>()
        val collector = launch(Dispatchers.Default) {
            sink.subscribe(EventFilter(agentId = "live")).collect {
                if (!received.isCompleted) received.complete(it)
            }
        }
        try {
            // Append until the (hot) subscription observes one — robust against subscribe latency.
            val got = withTimeout(5_000) {
                while (!received.isCompleted) {
                    sink.append(draft(agent = "live", type = EventType.TOOL_CALL))
                    kotlinx.coroutines.delay(20)
                }
                received.await()
            }
            assertEquals("live", got.agentId)
            assertEquals(EventType.TOOL_CALL, got.type)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun totalOrder_survivesBackwardClockJump() = runBlocking {
        val time = ManualTimeSource(start = 1_000)
        val sink = InMemoryEventSink(time)
        val a = sink.append(draft(agent = "a")) // ts=1000, seq=1
        time.clock = 900 // NTP steps wall-clock BACKWARDS
        val b = sink.append(draft(agent = "b")) // ts=900, seq=2
        val c = sink.append(draft(agent = "c")) // ts=900 (equal ms), seq=3
        assertEquals(listOf(1L, 2L, 3L), listOf(a.seq, b.seq, c.seq), "seq monotonic despite clock jump")
        assertTrue(b.ts < a.ts, "ts is intentionally non-monotonic (wall-clock)")
        // query orders by seq, not ts → the total order is stable regardless of the clock.
        val order = sink.query(EventFilter.ALL, Page(limit = 10)).events.map { it.agentId }
        assertEquals(listOf("a", "b", "c"), order)
    }

    @Test
    fun sqlite_restartDurable_walActive_seqContinues() = runBlocking {
        withTempDb { db ->
            val k = 25
            SqliteEventSink(db, SystemTimeSource()).use { s1 ->
                repeat(k) { s1.append(draft(agent = "a$it", type = EventType.TOOL_CALL)) }
            }
            // WAL is a persistent per-database setting; a fresh connection reports it.
            DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("PRAGMA journal_mode").use { rs ->
                        assertTrue(rs.next())
                        assertEquals("wal", rs.getString(1).lowercase())
                    }
                }
            }
            // Reopen on the same file: events survive, and seq resumes ABOVE the persisted max.
            val s2 = SqliteEventSink(db, SystemTimeSource())
            try {
                val page = s2.query(EventFilter.ALL, Page(limit = 1_000))
                assertEquals(k, page.events.size, "events survive restart")
                assertEquals((1L..k).toList(), page.events.map { it.seq })
                val more = s2.append(draft(agent = "after-restart"))
                assertEquals((k + 1).toLong(), more.seq, "seq must continue, never reuse, across restart")
            } finally {
                s2.close()
            }
        }
    }

    private inline fun withTempDb(block: (java.nio.file.Path) -> Unit) {
        val dir = Files.createTempDirectory("events-st1")
        try {
            block(dir.resolve("events.db"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
