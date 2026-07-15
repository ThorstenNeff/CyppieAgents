package com.tneff.cyppieagents.agentevents

import com.tneff.cyppieagents.model.RateLimitEvent
import com.tneff.cyppieagents.model.StoredAgentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-599 — the STORM-GUARD teeth for [agentEventReplayThenLive]. The CYP-588 gap-detect (correct: it fixes the silent
 * DROP_OLDEST transcript loss) SELF-AMPLIFIED under a runaway producer — every dropped event became its own unbounded
 * `Int.MAX_VALUE` re-query + WARN, run inside the live collector (positive feedback; 1310 WARNs, sibling-store starvation
 * in the 2026-07-15 dogfood). The guard = bounded-backfill + gap-coalesce + rate-capped WARN, keeping no-permanent-loss.
 *
 * These teeth pin the guard against the CYP-588 unit-test blind spot (1 agent / bounded burst / convergence-only). They
 * are DETERMINISTIC (no race): "drops" are modelled by delivering only the gap-boundary seqs to `live` while the durable
 * store holds everything — exactly a DROP_OLDEST gap, without depending on real buffer-eviction timing.
 */
class Cyp599StormGuardTest {

    private fun ev(i: Int) = RateLimitEvent(sessionId = "s", uuid = "u$i")
    private fun stored(agentId: String, seq: Long) = StoredAgentEvent(seq, agentId, "p", 0L, ev(seq.toInt()))

    /**
     * bounded-backfill + gap-coalesce + rate-cap + no-loss, all in one deterministic drive. Mutation matrix:
     *  - restore `Int.MAX_VALUE` re-query ⇒ `maxLimit` assert REDs (unbounded);
     *  - drain only to `rec.seq` (no coalesce) ⇒ the consumer never reaches the latest ⇒ the no-loss assert REDs;
     *  - `onGap` per gap (no rate-cap) ⇒ onGap count = 3, not 2 ⇒ REDs;
     *  - never re-arm `behind` ⇒ onGap count = 1, not 2 ⇒ REDs.
     */
    @Test
    fun boundedCoalescedRateCapped_noLoss_deterministic_CYP599() = runBlocking {
        val agent = "a1"
        val durable = CopyOnWriteArrayList<StoredAgentEvent>() // the full durable store (source of truth)
        var maxLimit = 0
        val onGapCount = AtomicInteger(0)
        val query: suspend (String, Long?, Int) -> List<StoredAgentEvent> = { aid, since, limit ->
            if (limit > maxLimit) maxLimit = limit // one subscriber ⇒ query called sequentially ⇒ no race
            val s = since ?: 0L
            durable.filter { it.agentId == aid && it.seq > s }.take(limit.coerceAtLeast(0))
        }
        val live = MutableSharedFlow<StoredAgentEvent>(extraBufferCapacity = 8192) // no drop here — we SIMULATE drops
        val got = CopyOnWriteArrayList<Long>()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val chunk = 128

        durable.add(stored(agent, 1)) // seed seq 1 before subscribe
        val sub = scope.launch {
            agentEventReplayThenLive(agent, 0L, live, query, catchupChunk = chunk, onGap = { _, _ -> onGapCount.incrementAndGet() })
                .collect { got.add(it.seq) }
        }
        withTimeout(5_000) { while (got.lastOrNull() != 1L) delay(5) } // initial replay delivered [1]

        fun growTo(n: Int) { for (s in durable.size + 1..n) durable.add(stored(agent, s.toLong())) }
        suspend fun deliverAndSettle(liveSeq: Long, expectCursor: Long) {
            live.emit(stored(agent, liveSeq))
            withTimeout(5_000) { while (got.lastOrNull() != expectCursor) delay(5) }
        }

        // Episode 1: durable grows to 500, live delivers ONLY seq 250 (a gap) → COALESCE catches up to 500 (not 250).
        growTo(500); deliverAndSettle(250, 500L)
        // Still behind (no re-arm): grow to 1000, live delivers seq 750 (gap) → same episode → NO new onGap.
        growTo(1000); deliverAndSettle(750, 1000L)
        // Re-arm: a CONTIGUOUS live event (seq == cursor+1 == 1001) → behind cleared.
        growTo(1001); deliverAndSettle(1001, 1001L)
        // Episode 2 (re-armed): grow to 1500, live delivers seq 1250 (gap) → onGap fires AGAIN.
        growTo(1500); deliverAndSettle(1250, 1500L)

        sub.cancel(); scope.cancel()
        assertEquals((1L..1500L).toList(), got.toList(), "no-loss + coalesce-to-latest: every seq delivered ONCE, in order")
        assertTrue(maxLimit <= chunk, "bounded backfill: no query ever exceeds catchupChunk=$chunk (mutant Int.MAX_VALUE → RED); observed max=$maxLimit")
        assertEquals(2, onGapCount.get(), "rate-cap: one onGap per lag-episode, re-armed after catch-up (mutant per-gap → 3; mutant never-rearm → 1)")
    }

    /**
     * Axis-(b) — sibling NOT starved. Models the Sqlite single-connection serialization: a shared lock whose hold time
     * ∝ the rows a query reads. A "storm" subscriber with a huge backlog and a "sib" subscriber with a tiny backlog
     * connect together. BOUNDED paged catch-up releases the lock between pages → sib interleaves and finishes fast;
     * an UNBOUNDED (`Int.MAX_VALUE`) read would hold the lock for the whole 3000-row backlog → sib starves.
     * Virtual time ([runTest]) → deterministic. Mutation (Int.MAX_VALUE) → `sibDoneAt` blows past the bound → RED.
     */
    @Test
    fun boundedPaging_releasesSharedLock_siblingProgresses_CYP599() = runTest {
        val lock = Mutex()
        val data = mapOf("storm" to (1L..3000L).toList(), "sib" to (1L..4L).toList())
        val chunk = 256
        // The store read is serialized on ONE lock and its hold time ∝ rows returned (the single-conn mutex).
        val query: suspend (String, Long?, Int) -> List<StoredAgentEvent> = { aid, since, limit ->
            lock.withLock {
                val s = since ?: 0L
                val rows = data.getValue(aid).filter { it > s }.take(limit.coerceAtLeast(0))
                delay(rows.size.toLong()) // lock held for `rows`-many virtual ms
                rows.map { stored(aid, it) }
            }
        }
        val liveStorm = MutableSharedFlow<StoredAgentEvent>(extraBufferCapacity = 8192)
        val liveSib = MutableSharedFlow<StoredAgentEvent>(extraBufferCapacity = 8192)
        var sibDoneAt = -1L
        var stormDoneAt = -1L
        val scheduler = testScheduler // captured so the launched coroutines can read the virtual clock

        val jStorm = launch {
            agentEventReplayThenLive("storm", 0L, liveStorm, query, catchupChunk = chunk).collect { if (it.seq == 3000L) stormDoneAt = scheduler.currentTime }
        }
        val jSib = launch {
            var n = 0
            agentEventReplayThenLive("sib", 0L, liveSib, query, catchupChunk = chunk).collect { n++; if (n == 4) sibDoneAt = scheduler.currentTime }
        }
        advanceUntilIdle() // run both initial catch-up drains to completion (they then suspend on live)

        jStorm.cancel(); jSib.cancel()
        assertTrue(sibDoneAt in 0..(2L * chunk), "sib (tiny backlog) finishes within ~one storm page — NOT starved; done@$sibDoneAt (mutant Int.MAX_VALUE holds the lock ~3000ms → RED)")
        assertTrue(stormDoneAt >= 3000L, "sanity: the storm really did read its full 3000-row backlog (done@$stormDoneAt)")
    }
}
