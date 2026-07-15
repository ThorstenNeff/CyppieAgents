package com.tneff.cyppieagents.agentevents

import com.tneff.cyppieagents.model.RateLimitEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-588 — the live-buffer-overflow GAP-DETECT regression teeth (on the CYP-571 turn→persistence spine).
 *
 * The prod stores' `live` fan-out is `MutableSharedFlow(DROP_OLDEST)` so a slow `/ws/agent` consumer never blocks
 * `append`; but DROP_OLDEST then SILENTLY drops mid-stream live events (`tryEmit` returns true on a drop), and the
 * `seq>cursor` cursor jumps past them → a permanent transcript hole (a `?since=<lastSeq>` reconnect never re-fetches
 * a seq below its cursor). The fix (agentEventReplayThenLive gap-detect) re-queries the DURABLE rows on a
 * `seq>cursor+1` jump and backfills the gap — nothing permanently lost.
 *
 * ★ TEETH-FIDELITY (the reason this slipped through): the tooth MUST run against the PROD `SqliteAgentEventStore`
 * (DROP_OLDEST). `InMemoryAgentEventStore.live` is SUSPEND (append `emit`s, never drops) → it MASKS the defect. The
 * first test asserts exactly that masking (documenting why an in-memory-store test could never have caught it).
 */
class Cyp588LiveGapDetectTest {

    private fun ev(i: Int) = RateLimitEvent(sessionId = "s", uuid = "u$i")

    @Test
    fun slowConsumer_liveOverflow_gapDetect_backfillsAll_realSqlite_CYP588() = runBlocking<Unit> {
        val db = Files.createTempFile("cyp588-gap", ".db")
        val store = SqliteAgentEventStore(db) // ★ the PROD DROP_OLDEST wiring — NOT InMemory
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val got = CopyOnWriteArrayList<Long>()

        // A SLOW subscriber (a backpressured /ws/agent send loop): ~3ms per delivered event.
        val sub = scope.launch { store.subscribe("a1", sinceSeq = 0L).collect { got.add(it.seq); delay(3) } }
        delay(150) // let onSubscription attach to `live` (empty replay)

        // Burst FAR beyond the 256 live buffer, as fast as the DB accepts → DROP_OLDEST would silently drop mid-stream.
        val n = 600
        repeat(n) { store.append("a1", "default", 0L, ev(it)) }

        // With the gap-detect fix every dropped seq is backfilled from the durable store, so the slow subscriber
        // EVENTUALLY receives ALL n — contiguous, no dup. (Mutant: remove the `seq>cursor+1` backfill branch in
        // agentEventReplayThenLive ⇒ ~half are permanently lost ⇒ RED, exactly the pre-fix repro.)
        // With the fix this converges in ~3s; the MUTANT (no backfill) can never reach n → this times out → RED.
        withTimeout(30_000) { while (got.size < n) delay(20) }

        assertEquals((1L..n.toLong()).toList(), got.toList(), "gap-detect backfilled every dropped live event — contiguous 1..$n, no loss, no dup")
        assertEquals(n, store.query("a1", null, 5_000).size, "sanity: all durable")
        sub.cancel(); scope.cancel(); store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun inMemoryStore_isSUSPEND_neverDrops_soItMASKStheDefect_CYP588() = runBlocking<Unit> {
        // Documents the teeth-fidelity trap: the same slow-consumer burst on the IN-MEMORY store loses NOTHING even
        // WITHOUT gap-detect, because its `live` is SUSPEND (append back-pressures) — so any test written against the
        // in-memory store is blind to the prod DROP_OLDEST defect. This is why CYP-588 was latent.
        val store = InMemoryAgentEventStore()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val got = CopyOnWriteArrayList<Long>()
        val sub = scope.launch { store.subscribe("a1", sinceSeq = 0L).collect { got.add(it.seq); delay(1) } }
        delay(100)
        val n = 600
        launch { repeat(n) { store.append("a1", "default", 0L, ev(it)) } } // append SUSPENDS when the buffer is full
        withTimeout(60_000) { while (got.size < n) delay(20) }
        assertTrue(got.toSet() == (1L..n.toLong()).toSet(), "in-memory SUSPEND never drops → masks the defect (no test against it can catch DROP_OLDEST loss)")
        sub.cancel(); scope.cancel()
    }
}
