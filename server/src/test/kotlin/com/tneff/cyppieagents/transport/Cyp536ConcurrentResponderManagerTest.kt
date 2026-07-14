package com.tneff.cyppieagents.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-536 (M2 Option A, WS1) + CYP-553 — [ConcurrentRelayResponderManager]: the serial→N fan-out over the
 * epoch-derived rendezvous-id set, with the **persistent set-fetch retry** (CYP-553: a transient CP failure /
 * boot-admission race no longer leaves the hub dark; the manager retries instead of a one-shot INERT). Pure-logic
 * teeth over fake [RelayConnector]s (no Netty/relay). The fan-out runs on the manager's supervisory coroutine, so the
 * teeth await it (the injected `sleep = { yield() }` gives a cancellable, real-time-free retry cadence).
 */
class Cyp536ConcurrentResponderManagerTest {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest fun tearDown() = scope.cancel()

    private class FakeResponder(val id: String) : RelayConnector {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        override suspend fun start() { starts.incrementAndGet() }
        override suspend fun stop() { stops.incrementAndGet() }
    }

    private fun manager(source: SessionRendezvousSource, poolCap: Int, built: CopyOnWriteArrayList<FakeResponder>) =
        ConcurrentRelayResponderManager(
            source = source,
            responderFor = { id -> FakeResponder(id).also { built += it } },
            poolCap = poolCap,
            scope = scope,
            backoffMs = { 0 },
            sleep = { yield() }, // cancellable, real-time-free retry cadence for the tests
        )

    private suspend fun awaitBuilt(built: CopyOnWriteArrayList<FakeResponder>, n: Int) =
        withTimeout(10_000) { while (built.size < n) yield() }

    @Test
    fun nIds_startN_concurrentResponders_oneEachId() = runBlocking {
        val built = CopyOnWriteArrayList<FakeResponder>()
        val ids = listOf("id0", "id1", "id2", "id3")
        manager({ ids }, poolCap = 10, built).start()
        awaitBuilt(built, 4)
        assertEquals(ids.toSet(), built.map { it.id }.toSet(), "one responder per rendezvous-id in the set")
        assertTrue(built.all { it.starts.get() == 1 }, "every responder started exactly once (concurrent presence at the relay)")
    }

    @Test
    fun rendezvousSetOverCap_cappedToPoolCap_theDosFloor() = runBlocking {
        // WS6 axis 2: 6 ids but poolCap 3 → only 3 responders launch. A missing `.take(poolCap)` launches 6 (RED).
        val built = CopyOnWriteArrayList<FakeResponder>()
        manager({ listOf("a", "b", "c", "d", "e", "f") }, poolCap = 3, built).start()
        awaitBuilt(built, 3)
        repeat(50) { yield() } // give the manager ample opportunity to (wrongly) launch more
        assertEquals(3, built.size, "the manager launches at most poolCap responders (server-side per-operator tunnel CAP)")
    }

    @Test
    fun cyp553_retriesPastTransientNulls_thenFansOut() = runBlocking {
        // ★ CYP-553: the set is unavailable (transient CP / boot race) for the first 3 fetches, then resolves. The
        // manager RETRIES and fans out — it does NOT one-shot INERT on the first null (the old bug: hub silently dark).
        val built = CopyOnWriteArrayList<FakeResponder>()
        val calls = AtomicInteger()
        val source = SessionRendezvousSource { if (calls.getAndIncrement() < 3) null else listOf("id0", "id1") }
        manager(source, poolCap = 10, built).start()
        awaitBuilt(built, 2)
        assertEquals(setOf("id0", "id1"), built.map { it.id }.toSet(), "retried past null×3 → fanned out (not a one-shot INERT)")
        assertTrue(calls.get() >= 4, "the source was retried (null×3 then the set), not called once: ${calls.get()}")
    }

    @Test
    fun cyp553_persistentlyUnavailable_keepsRetrying_launchesNothing_stopHalts() = runBlocking {
        // A persistently-null source (e.g. a never-owned hub) → the manager KEEPS retrying (visible, not silent) and
        // launches NO responder; stop() halts the retry loop (cancellable — no runaway).
        val built = CopyOnWriteArrayList<FakeResponder>()
        val calls = AtomicInteger()
        val mgr = manager({ calls.incrementAndGet(); null }, poolCap = 10, built)
        mgr.start()
        withTimeout(5_000) { while (calls.get() < 5) yield() } // it retries ≥5×, never gives up
        assertEquals(0, built.size, "a persistently-unavailable set launches NO responder (never a spurious launch)")
        mgr.stop()
        val after = calls.get()
        repeat(100) { yield() }
        assertTrue(calls.get() <= after + 1, "stop() halts the retry loop — no runaway after stop ($after → ${calls.get()})")
    }

    @Test
    fun idempotentStart_neverDoubleFansOut() = runBlocking {
        val built = CopyOnWriteArrayList<FakeResponder>()
        val mgr = manager({ listOf("id0", "id1") }, poolCap = 10, built)
        mgr.start(); mgr.start() // the 2nd start() is a no-op
        awaitBuilt(built, 2)
        repeat(50) { yield() }
        assertEquals(2, built.size, "a 2nd start() never launches a 2nd set of responders (idempotent)")
    }

    @Test
    fun stop_stopsEveryStartedResponder() = runBlocking {
        val built = CopyOnWriteArrayList<FakeResponder>()
        val mgr = manager({ listOf("id0", "id1") }, poolCap = 10, built)
        mgr.start()
        awaitBuilt(built, 2)
        mgr.stop()
        assertEquals(2, built.size)
        assertTrue(built.all { it.stops.get() == 1 }, "stop() tore down every per-id responder exactly once")
    }
}
