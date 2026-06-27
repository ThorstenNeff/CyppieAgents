package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.model.EventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * ST1 (CYP-35) back-pressure contract — the three non-negotiable gates:
 *  - tap is non-blocking ([EventRecorder.record] is a plain fun over trySend),
 *  - a full queue drops the *newest* and counts it,
 *  - the drop is made VISIBLE as a `log.dropped` event that itself cannot be dropped.
 */
class EventRecorderTest {

    @Test
    fun dropNewest_isCounted_andReportedAsLogDropped() = runBlocking {
        val mem = InMemoryEventSink(SystemTimeSource())
        val cap = 16
        val extra = 9
        val rec = EventRecorder(mem, this, capacity = cap, batchSize = 8)
        // Writer NOT started → the channel buffers to capacity, then rejects the newest.
        repeat(cap) { assertTrue(rec.record(draft(agent = "ok$it")), "within capacity must enqueue") }
        repeat(extra) { assertFalse(rec.record(draft(agent = "drop$it")), "over capacity must drop-newest") }
        assertEquals(extra.toLong(), rec.dropped)

        // Drain + shutdown → the gap becomes visible as a single cumulative log.dropped.
        rec.start()
        rec.stop()
        val drops = mem.query(EventFilter(type = EventType.LOG_DROPPED), Page(limit = 10)).events
        assertEquals(1, drops.size, "one cumulative log.dropped for this drop burst")
        assertEquals(extra.toLong(), drops.first().detail["total"]!!.jsonPrimitive.long)
        assertEquals(extra.toLong(), drops.first().detail["dropped"]!!.jsonPrimitive.long)
        assertEquals((cap + 1).toLong(), mem.all().size.toLong(), "16 buffered events + 1 log.dropped")
    }

    @Test
    fun record_neverBlocks_whenSinkIsStuck() = runBlocking {
        val mem = InMemoryEventSink(SystemTimeSource())
        val stuck = LatchableEventSink(mem).also { it.pause() }
        val recScope = CoroutineScope(Dispatchers.Default)
        val rec = EventRecorder(stuck, recScope, capacity = 8, batchSize = 4)
        rec.start()
        // Flood far beyond capacity while the sink is parked. record() must stay responsive and drop.
        var dropped = 0
        repeat(5_000) { if (!rec.record(draft(agent = "f$it"))) dropped++ }
        assertTrue(dropped > 0, "the queue must saturate and drop while the sink is parked")
        assertEquals(dropped.toLong(), rec.dropped)

        stuck.resume()
        rec.stop()
        recScope.cancel()

        val drops = mem.query(EventFilter(type = EventType.LOG_DROPPED), Page(limit = 100)).events
        assertTrue(drops.isNotEmpty(), "the dropped gap must surface as log.dropped telemetry")
        val cumulative = drops.maxOf { it.detail["total"]!!.jsonPrimitive.long }
        assertEquals(rec.dropped, cumulative, "cumulative log.dropped total must equal the drop counter")
    }

    @Test
    fun noDrop_preservesGaplessTotalOrder() = runBlocking {
        val mem = InMemoryEventSink(SystemTimeSource())
        val n = 300
        val rec = EventRecorder(mem, this, capacity = n + 64, batchSize = 32)
        rec.start()
        coroutineScope {
            repeat(n) { i -> launch(Dispatchers.Default) { rec.record(draft(agent = "a$i")) } }
        }
        rec.stop()

        assertEquals(0L, rec.dropped, "capacity covers the load → no drops")
        val all = mem.all()
        assertTrue(all.none { it.type == EventType.LOG_DROPPED }, "no drops → no log.dropped")
        assertEquals(n, all.size)
        assertEquals((1L..n).toList(), all.map { it.seq }, "queued path keeps a gapless total order")
    }
}
