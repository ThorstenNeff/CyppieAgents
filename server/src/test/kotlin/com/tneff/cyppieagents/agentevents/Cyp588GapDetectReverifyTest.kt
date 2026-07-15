package com.tneff.cyppieagents.agentevents

import com.tneff.cyppieagents.model.RateLimitEvent
import com.tneff.cyppieagents.model.StoredAgentEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-588 — **independent two-eyes re-verify** of the live-buffer gap-detect fix (Team-2, complementary to
 * Backend2's `Cyp588LiveGapDetectTest`, NOT a repro-dup).
 *
 * Backend2's tooth drives the REAL `SqliteAgentEventStore` with a statistical 600-burst + slow consumer to
 * provoke DROP_OLDEST. This re-verify takes the **other angle**: it exercises the shared `agentEventReplayThenLive`
 * gap-detect logic **directly** with a controlled `live` flow + a growing durable store, forcing a **deterministic**
 * seq-jump (not a statistical overflow) — so the exact backfill set + order + no-dup + gap-diagnostic are pinned
 * precisely, and the `seq > cursor + 1` condition is proven load-bearing by mutation.
 *
 * Model of the prod defect: `append` writes the DB **before** `tryEmit`, so a DROP_OLDEST drop loses the live
 * frame but never the durable row. Here: rows 4,5 are appended to the durable store but "dropped" from `live`
 * (only 6 is delivered) → a 3→6 jump → the fix must re-query the durable store and backfill 4,5 (and 6) in order.
 */
class Cyp588GapDetectReverifyTest {

    private fun evt(seq: Long, agent: String = "a") =
        StoredAgentEvent(seq = seq, agentId = agent, projectId = "default", tsMs = 0L, event = RateLimitEvent(uuid = "u$seq"))

    @Test
    fun deterministicLiveGap_isBackfilledInOrder_noHole_noDup_gapDiagnosed() = runBlocking {
        val durable = CopyOnWriteArrayList<StoredAgentEvent>().apply { add(evt(1)); add(evt(2)) } // persisted at subscribe
        val live = MutableSharedFlow<StoredAgentEvent>(extraBufferCapacity = 64)
        val query: suspend (String, Long?, Int) -> List<StoredAgentEvent> = { id, since, _ ->
            durable.filter { it.agentId == id && it.seq > (since ?: 0L) }
        }
        val gaps = CopyOnWriteArrayList<Pair<Long, Long>>()
        val got = CopyOnWriteArrayList<Long>()
        val job = launch(Dispatchers.IO) {
            agentEventReplayThenLive("a", null, live, query) { f, t -> gaps.add(f to t) }.collect { got.add(it.seq) }
        }
        withTimeout(3_000) { while (got.size < 2) delay(5) } // replay of 1,2 ⇒ collector attached to `live`

        durable.add(evt(3)); live.emit(evt(3)) // a delivered live frame
        withTimeout(3_000) { while (got.size < 3) delay(5) }

        // 4,5 durably appended but DROPPED from the live buffer; only 6 survives DROP_OLDEST → a 3→6 jump.
        durable.add(evt(4)); durable.add(evt(5)); durable.add(evt(6))
        live.emit(evt(6))
        withTimeout(3_000) { while (got.size < 6) delay(5) }
        job.cancel()

        assertEquals(
            listOf(1L, 2, 3, 4, 5, 6), got.toList(),
            "the dropped live frames (4,5) are backfilled from the durable store in order — contiguous, no hole, no dup",
        )
        assertEquals(listOf(4L to 5L), gaps.toList(), "gap-detect fired exactly once, naming the (4,5) hole")
    }

    @Test
    fun contiguousLiveStream_noGapMisfire_control() = runBlocking {
        // Non-vacuity + no-regression: with NO drops the gap branch must NEVER fire, and every frame is delivered
        // exactly once in order. (Under the keystone mutation that disables gap-detect, THIS stays GREEN — the
        // discriminator is the dropped-frame path above, not a broken happy path.)
        val durable = CopyOnWriteArrayList<StoredAgentEvent>()
        val live = MutableSharedFlow<StoredAgentEvent>(extraBufferCapacity = 64)
        val query: suspend (String, Long?, Int) -> List<StoredAgentEvent> = { id, since, _ ->
            durable.filter { it.agentId == id && it.seq > (since ?: 0L) }
        }
        val gaps = CopyOnWriteArrayList<Pair<Long, Long>>()
        val got = CopyOnWriteArrayList<Long>()
        val job = launch(Dispatchers.IO) {
            agentEventReplayThenLive("a", null, live, query) { f, t -> gaps.add(f to t) }.collect { got.add(it.seq) }
        }
        withTimeout(3_000) { while (live.subscriptionCount.value < 1) delay(5) } // collector attached (empty replay)

        for (s in 1L..5L) { durable.add(evt(s)); live.emit(evt(s)) }
        withTimeout(3_000) { while (got.size < 5) delay(5) }
        job.cancel()

        assertEquals(listOf(1L, 2, 3, 4, 5), got.toList(), "a contiguous stream is delivered exactly once, in order")
        assertTrue(gaps.isEmpty(), "no gap-detect misfire on a contiguous (no-drop) stream")
    }
}
