package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.model.EventType
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * ST10 (CYP-44) cross-cutting evidence — the **adversarial deltas on top of ST1's** already-green
 * unit suite (`EventSinkTest`/`EventRecorderTest` cover §1 plain concurrency, clock-jump, §2 drop,
 * §2b never-self-dropped, §3 non-blocking tap, §5 restart-WAL). These two prove the *combined* /
 * *conservation* properties ST1 asserts only separately:
 *  - total order survives concurrency **and** a hostile clock at the same time;
 *  - under saturation **no event vanishes unaccounted** (accepted+dropped==K, persisted==accepted).
 *
 * §4 (metadata-only needle-absence) and §6 (operator-only `/api/events` + `/ws/events` real handshake)
 * activate with ST3 (CYP-37) / ST5-6 (CYP-39/40) and will be added here then.
 */
class EventLogContractTest {

    /** §1 combined: 500 concurrent writers while the wall-clock jumps backwards/forwards → `seq` is still a gapless total order. */
    @Test
    fun totalOrder_holdsUnderConcurrentWriters_withBackwardClockJumps() = runBlocking {
        val time = ManualTimeSource(start = 1_000_000L)
        val sink = InMemoryEventSink(time)
        val n = 500
        coroutineScope {
            // Chaos: jerk the (non-monotonic) wall-clock around while writers append concurrently.
            launch(Dispatchers.Default) {
                var t = 1_000_000L
                repeat(2_000) { i ->
                    t += if (i % 2 == 0) -777L else 321L // mostly backwards — NTP-style jumps
                    time.clock = t
                    yield()
                }
            }
            repeat(n) { i -> launch(Dispatchers.Default) { sink.append(draft(agent = "a$i")) } }
        }
        val all = sink.all()
        assertEquals(n, all.size, "every event stored")
        assertEquals((1L..n).toList(), all.map { it.seq }, "seq is a gapless 1..n total order despite clock chaos")
        assertEquals(n, all.map { it.seq }.toSet().size, "seq unique")
    }

    /** §2 conservation: under saturation, every event is either persisted or counted as dropped — none vanish; survivors keep a clean seq. */
    @Test
    fun conservation_underSaturation_noEventVanishesUnaccounted() = runBlocking {
        val mem = InMemoryEventSink(SystemTimeSource())
        val latched = LatchableEventSink(mem).also { it.pause() } // sink parked → recorder queue saturates
        val recScope = CoroutineScope(Dispatchers.Default)
        val rec = EventRecorder(latched, recScope, capacity = 64, batchSize = 16)
        rec.start()

        val k = 5_000
        val accepted = AtomicInteger(0)
        val rejected = AtomicInteger(0)
        coroutineScope {
            repeat(k) { i ->
                launch(Dispatchers.Default) {
                    if (rec.record(draft(agent = "f$i"))) accepted.incrementAndGet() else rejected.incrementAndGet()
                }
            }
        }

        latched.resume() // let the writer drain everything it accepted
        rec.stop()
        recScope.cancel()

        // Conservation: nothing is silently lost — every one of K is accounted for.
        assertEquals(k, accepted.get() + rejected.get(), "accepted + dropped must equal the total offered (no event vanishes)")
        assertEquals(rejected.get().toLong(), rec.dropped, "rejected count must equal the authoritative drop counter")
        assertTrue(rejected.get() > 0, "the parked sink must force real drops (else the test proves nothing)")

        val all = mem.all()
        val real = all.filter { it.type != EventType.LOG_DROPPED }
        val drops = all.filter { it.type == EventType.LOG_DROPPED }
        assertEquals(accepted.get(), real.size, "persisted real events must equal accepted — accepted events are never lost")
        assertTrue(drops.isNotEmpty(), "the gap must be visible as log.dropped (never silent, never self-dropped)")
        assertEquals(rejected.get().toLong(), drops.maxOf { it.detail["total"]!!.jsonPrimitive.long }, "cumulative log.dropped total == dropped count")

        // Survivors keep a strictly-increasing, unique seq — dropping must not corrupt the order of what remains.
        val seqs = real.map { it.seq }
        assertEquals(seqs.size, seqs.toSet().size, "no duplicate seq among survivors")
        assertTrue(seqs.zipWithNext().all { (a, b) -> b > a }, "survivor seq is strictly increasing")
    }
}
