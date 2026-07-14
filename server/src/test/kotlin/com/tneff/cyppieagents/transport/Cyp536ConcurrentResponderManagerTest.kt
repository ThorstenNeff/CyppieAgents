package com.tneff.cyppieagents.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-536 (M2 Option A, WS1) — [ConcurrentRelayResponderManager]: the serial→N fan-out over the epoch-derived
 * rendezvous-id set. Pure-logic teeth over a fake [RelayConnector] (no Netty/relay) — the manager's contract is
 * *how many* responders it launches and *for which ids*, plus the WS6-axis-2 per-operator cap and the INERT gate.
 */
class Cyp536ConcurrentResponderManagerTest {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest fun tearDown() = scope.cancel()

    /** A fake responder recording ITS OWN start/stop counts (each responder self-counts — no shared aggregate). */
    private class FakeResponder(val id: String) : RelayConnector {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        override suspend fun start() { starts.incrementAndGet() }
        override suspend fun stop() { stops.incrementAndGet() }
    }

    private fun manager(ids: List<String>?, poolCap: Int, built: CopyOnWriteArrayList<FakeResponder>): ConcurrentRelayResponderManager =
        ConcurrentRelayResponderManager(
            source = { ids },
            responderFor = { id -> FakeResponder(id).also { built += it } },
            poolCap = poolCap,
            scope = scope,
        )

    @Test
    fun nIds_startN_concurrentResponders_oneEachId() = runBlocking {
        val built = CopyOnWriteArrayList<FakeResponder>()
        val ids = listOf("id0", "id1", "id2", "id3")
        manager(ids, poolCap = 10, built).start()
        assertEquals(4, built.size, "one responder per rendezvous-id in the set")
        assertEquals(ids.toSet(), built.map { it.id }.toSet(), "each responder is bound to its own id")
        assertTrue(built.all { it.starts.get() == 1 }, "every responder was started exactly once (concurrent presence at the relay)")
    }

    @Test
    fun rendezvousSetOverCap_cappedToPoolCap_theDosFloor() {
        // WS6 axis 2 (the mutation target): the set has 6 ids but the per-operator cap is 3 → only 3 responders launch.
        // A missing `.take(poolCap)` would launch all 6 (RED), defeating the server-side per-operator tunnel CAP.
        val built = CopyOnWriteArrayList<FakeResponder>()
        runBlocking { manager(listOf("a", "b", "c", "d", "e", "f"), poolCap = 3, built).start() }
        assertEquals(3, built.size, "the manager launches at most poolCap responders (server-side per-operator tunnel CAP)")
    }

    @Test
    fun nullSet_isInert_startsNoResponder() {
        val built = CopyOnWriteArrayList<FakeResponder>()
        runBlocking { manager(null, poolCap = 10, built).start() }
        assertEquals(0, built.size, "a null rendezvous set (relay INERT / not owned) starts no responder — fail-closed")
    }

    @Test
    fun emptySet_isInert_startsNoResponder() {
        val built = CopyOnWriteArrayList<FakeResponder>()
        runBlocking { manager(emptyList(), poolCap = 10, built).start() }
        assertEquals(0, built.size, "an empty rendezvous set starts no responder — fail-closed INERT")
    }

    @Test
    fun stop_stopsEveryStartedResponder() = runBlocking {
        val built = CopyOnWriteArrayList<FakeResponder>()
        val m = manager(listOf("id0", "id1"), poolCap = 10, built)
        m.start()
        m.stop()
        assertEquals(2, built.size)
        assertTrue(built.all { it.stops.get() == 1 }, "stop() tore down every per-id responder exactly once")
    }
}
