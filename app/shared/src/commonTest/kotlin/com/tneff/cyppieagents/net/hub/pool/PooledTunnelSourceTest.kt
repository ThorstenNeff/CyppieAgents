package com.tneff.cyppieagents.net.hub.pool

import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-537 (M2 Option A, WS2) — the pooling [PooledTunnelSource] teeth: it is the F-M2-1 fix, so the load-bearing
 * proof is **distinct, concurrent tunnels** (the milestone: 2 concurrent), bounded by [TUNNEL_POOL_CAP], with the C2
 * fail-closed invariants (at cap ⇒ `null`; a dial failure ⇒ `null` + slot released; the pool owns lifecycle) and the
 * C3 [TunnelPoolState] observability (DIALING→UP→DOWN + aggregate). Driven over a fake [PoolTunnelDialer] — no crypto.
 */
class PooledTunnelSourceTest {

    private class FakeTunnel(val id: String) : NoiseTunnel {
        override val handshakeHash = ByteArray(32)
        var closed = false
        override suspend fun send(plaintext: ByteArray) = Unit
        override suspend fun receive(): ByteArray? = null
        override suspend fun close() { closed = true }
    }

    /** A fake dialer: [setIds] is the CP N-set; each [dial] mints a fresh [FakeTunnel] for the id (or `null` to fail). */
    private class FakeDialer(
        private val setIds: List<String>?,
        private val failDial: Boolean = false,
    ) : PoolTunnelDialer {
        val dialedIds = mutableListOf<String>()
        val minted = mutableListOf<FakeTunnel>()
        override suspend fun rendezvousSet(): List<String>? = setIds
        override suspend fun dial(rendezvousId: String): NoiseTunnel? {
            dialedIds.add(rendezvousId)
            if (failDial) return null
            return FakeTunnel(rendezvousId).also { minted.add(it) }
        }
    }

    private fun pool(dialer: PoolTunnelDialer, cap: Int = 4) =
        PooledTunnelSource(dialer = dialer, cap = cap, nowMs = { 0L })

    @Test
    fun acquire_twoDistinctConcurrentTunnels_theMilestone() = runTest {
        // The M2-A first milestone: two concurrent logical connections each get a DISTINCT live tunnel (single-flight
        // could only ever hand back ONE — that is F-M2-1). Both coexist ⇒ aggregate.active == 2, two distinct ids.
        val dialer = FakeDialer(setIds = listOf("id-0", "id-1", "id-2"))
        val p = pool(dialer)
        val a = assertNotNull(p.acquire(), "first tunnel")
        val b = assertNotNull(p.acquire(), "second tunnel")
        assertTrue(a !== b, "each acquire() yields a DISTINCT tunnel (never the same shared one — the F-M2-1 fix)")
        assertEquals(2, dialer.dialedIds.toSet().size, "two DISTINCT rendezvous-ids from the CP set were dialed")
        assertEquals(2, p.state.value.aggregate.active, "both tunnels are live in the pool")
        assertFalse(p.state.value.aggregate.anyBackpressured)
    }

    @Test
    fun acquire_concurrent_bothEstablish_offLock() = runTest {
        // Reserve is serialized but the DIAL runs off-lock ⇒ two concurrent acquire()s both establish (no deadlock,
        // no serialization to one). Proven by launching both concurrently and awaiting two distinct live tunnels.
        val dialer = FakeDialer(setIds = listOf("id-0", "id-1"))
        val p = pool(dialer)
        val results = listOf(async { p.acquire() }, async { p.acquire() }).awaitAll()
        assertTrue(results.all { it != null }, "both concurrent acquires establish a tunnel")
        assertEquals(2, p.state.value.aggregate.active)
    }

    @Test
    fun acquire_atCap_returnsNull_failClosed() = runTest {
        // C2: up to poolCap. At cap every set id is held ⇒ the next acquire is null (fail-closed → the transport RSTs),
        // NEVER a shared/reused live tunnel. The set has 3 ids but cap is 2 ⇒ the third acquire caps out.
        val dialer = FakeDialer(setIds = listOf("id-0", "id-1", "id-2"))
        val p = pool(dialer, cap = 2)
        assertNotNull(p.acquire())
        assertNotNull(p.acquire())
        assertNull(p.acquire(), "at cap ⇒ null (fail-closed), never a reused tunnel")
        assertEquals(2, p.state.value.aggregate.active)
        assertEquals(2, p.state.value.aggregate.cap)
    }

    @Test
    fun acquire_dialFailsClosed_returnsNull_slotReleased_noPhantom() = runTest {
        // A fail-closed dial (null) ⇒ acquire null AND the reserved slot is released — no phantom DIALING/UP entry
        // lingers (active stays 0), and a later successful dial can still take the slot.
        val failing = FakeDialer(setIds = listOf("id-0"), failDial = true)
        val p = pool(failing)
        assertNull(p.acquire(), "fail-closed dial ⇒ null")
        assertEquals(0, p.state.value.aggregate.active, "no phantom entry — the slot was released")
        assertTrue(p.state.value.tunnels.none { it.state == TunnelState.DIALING || it.state == TunnelState.UP })
    }

    @Test
    fun acquire_unresolvedSet_returnsNull_inert() = runTest {
        // C4/INERT: the CP N-set is unresolved (null) ⇒ the pool yields no tunnels (fail-closed), never a fabricated id.
        val p = pool(FakeDialer(setIds = null))
        assertNull(p.acquire())
        assertEquals(0, p.state.value.aggregate.active)
    }

    @Test
    fun closingTunnel_marksDown_freesSlot_reusable() = runTest {
        // The pool owns lifecycle: closing a tunnel (the bridge does, at connection-end) marks it DOWN (observable)
        // and frees its slot, so a later acquire reuses the freed set id. cap=1 forces reuse of the one id.
        val dialer = FakeDialer(setIds = listOf("id-0"))
        val p = pool(dialer, cap = 1)
        val first = assertNotNull(p.acquire())
        assertEquals(1, p.state.value.aggregate.active)
        assertNull(p.acquire(), "cap 1, one held ⇒ null")
        first.close() // the bridge closing the tunnel at connection-end
        assertTrue(p.state.value.tunnels.any { it.state == TunnelState.DOWN }, "closed tunnel is observably DOWN")
        assertEquals(0, p.state.value.aggregate.active, "the slot is freed")
        val reused = assertNotNull(p.acquire(), "the freed slot is reusable")
        assertEquals(1, p.state.value.aggregate.active)
        assertEquals(listOf("id-0", "id-0"), dialer.dialedIds, "the freed CP set id is re-dialed (not a new invented id)")
        reused.close()
    }

    @Test
    fun poolClose_tearsDownAllLiveTunnels_thenInert() = runTest {
        // Q5 teardown: pool.close() closes every live tunnel (nothing carried across a switch/leave) and the pool
        // goes inert (further acquire ⇒ null).
        val dialer = FakeDialer(setIds = listOf("id-0", "id-1"))
        val p = pool(dialer)
        p.acquire(); p.acquire()
        assertEquals(2, dialer.minted.size)
        p.close()
        assertTrue(dialer.minted.all { it.closed }, "close() tears down every underlying tunnel")
        assertNull(p.acquire(), "after close ⇒ inert (fail-closed)")
    }

    @Test
    fun backpressureSignal_marksBackpressured_thenClears() = runTest {
        // CYP-535 (H7): the pump flags a full send-window via BackpressureSignal ⇒ the tunnel shows C3 BACKPRESSURED
        // (a healthy-but-slow tunnel, never DOWN), and aggregate.anyBackpressured; clearing returns it to UP.
        val dialer = FakeDialer(setIds = listOf("id-0"))
        val p = pool(dialer)
        val t = assertNotNull(p.acquire())
        val signal = t as BackpressureSignal
        signal.onBackpressured(true)
        assertTrue(p.state.value.tunnels.single().state == TunnelState.BACKPRESSURED)
        assertTrue(p.state.value.aggregate.anyBackpressured)
        assertEquals(1, p.state.value.aggregate.active, "backpressured still counts as active (not down)")
        signal.onBackpressured(false)
        assertEquals(TunnelState.UP, p.state.value.tunnels.single().state)
        assertFalse(p.state.value.aggregate.anyBackpressured)
    }
}
