package com.tneff.cyppieagents.net.hub.pool

import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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

    /** A fake dialer: [setIds] is the CP N-set; each [dial] mints a fresh [FakeTunnel] for the id (or `null` to fail).
     *  [yieldInDial] yields inside the (off-lock) dial so concurrent acquires interleave — exercising the reserve gate
     *  under real contention (CYP-556 race teeth). */
    private class FakeDialer(
        private val setIds: List<String>?,
        private val failDial: Boolean = false,
        private val yieldInDial: Boolean = false,
    ) : PoolTunnelDialer {
        val dialedIds = mutableListOf<String>()
        val minted = mutableListOf<FakeTunnel>()
        override suspend fun rendezvousSet(): List<String>? = setIds
        override suspend fun dial(rendezvousId: String): NoiseTunnel? {
            dialedIds.add(rendezvousId)
            if (yieldInDial) yield() // let sibling acquires reserve/interleave (the off-lock-dial window)
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
    fun concurrentAcquire_atCap_neverExceedsCap_CYP556() = runTest {
        // CYP-556 race-interleaving: the accept-loop now calls acquire() CONCURRENTLY (α). With N > cap concurrent
        // acquires, the mutex-guarded reserveSlot must admit EXACTLY cap and fail-close the rest — never over-issue
        // past cap. `yieldInDial` forces the reserves to interleave (reserve, yield, sibling reserves) so the gate is
        // exercised under real contention. Mutant: drop `if (held.size >= cap) return null` ⇒ all N succeed ⇒ RED.
        val cap = 4
        val ids = (0 until 12).map { "id-$it" } // more ids than cap ⇒ the CAP is the limiter, not the set size
        val dialer = FakeDialer(setIds = ids, yieldInDial = true)
        val p = pool(dialer, cap = cap)
        val results = (0 until 12).map { async { p.acquire() } }.awaitAll()
        assertEquals(cap, results.count { it != null }, "exactly cap concurrent acquires succeed; the rest fail-closed — never > cap")
        assertEquals(cap, p.state.value.aggregate.active, "live count == cap; never exceeded under concurrency")
        assertTrue(p.state.value.tunnels.count { it.state != TunnelState.DOWN } <= cap, "no over-issue past cap")
    }

    @Test
    fun closeDuringDial_doesNotLeakTheInFlightTunnel_CYP556() = runTest {
        // CYP-556 H2: the dial runs OFF-lock, so a close() that races an in-flight dial must NOT leave the freshly
        // dialed tunnel live (leaked, never torn down). The gate = the `closed` recheck UNDER liveLock, atomic with
        // the insert. Mutant: remove that recheck (insert without re-checking closed) ⇒ the tunnel is inserted AFTER
        // close() cleared `live` ⇒ live-but-never-closed ⇒ acquire returns it (not null) + it stays open ⇒ RED.
        val gate = CompletableDeferred<Unit>()
        val minted = mutableListOf<FakeTunnel>()
        val dialer = object : PoolTunnelDialer {
            override suspend fun rendezvousSet() = listOf("id-0")
            override suspend fun dial(rendezvousId: String): NoiseTunnel? {
                gate.await() // hold the dial in-flight (off-lock) until the test releases it — AFTER close()
                return FakeTunnel(rendezvousId).also { minted.add(it) }
            }
        }
        val p = pool(dialer, cap = 4)
        val acq = async { p.acquire() } // suspends inside dial (awaiting `gate`)
        advanceUntilIdle() // let `acq` reach the suspended dial
        p.close() // teardown races the in-flight dial: closed=true; snapshot+clear live (still empty — not inserted yet)
        gate.complete(Unit) // now the dial completes, POST-close
        val result = acq.await()
        assertNull(result, "a dial that completes AFTER close() hands out NULL (fail-closed), never a live tunnel")
        assertEquals(1, minted.size, "the tunnel WAS dialed (the race is real)")
        assertTrue(minted.single().closed, "the in-flight-dialed tunnel is CLOSED, not leaked, when close() raced the dial")
        assertTrue(
            p.state.value.tunnels.none { it.rendezvousId == "id-0" && it.state != TunnelState.DOWN },
            "no live/dialing entry leaks for the raced id",
        )
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

    @Test
    fun controlLane_acquiresUnderDataSaturation_theBreakGlassReservation_CYP616() = runTest {
        // CYP-616 (THE fix tooth, non-vacuous): with controlReserved=2, the DATA (WS) lane is gated `controlReserved`
        // BELOW the usable set, so once WS churn saturates it a DATA acquire fail-closes — but a CONTROL (lifecycle-REST)
        // acquire STILL succeeds from the reserved break-glass headroom. This is exactly the incident (WS storm →
        // pool-exhaust → lifecycle "Server unreachable") and its fix. Mutant: drop the `if (lane==CONTROL)` in
        // reserveSlot (so CONTROL also subtracts controlReserved) ⇒ CONTROL null under saturation ⇒ RED. Mutant:
        // controlReserved=0 (no reservation) ⇒ DATA fills all 5, the "4th DATA fail-closes" assert reddens.
        val cap = 5
        val ids = (0 until cap).map { "id-$it" } // usable = min(cap, set.size) = 5 ⇒ DATA effectiveCap = 5 − 2 = 3
        val dialer = FakeDialer(setIds = ids)
        val p = PooledTunnelSource(dialer = dialer, cap = cap, nowMs = { 0L }, controlReserved = 2)
        // Saturate the DATA lane up to its effectiveCap (3), like a WS-churn storm holding every data slot.
        val data = (0 until 3).map { p.acquire(TunnelLane.DATA) }
        assertTrue(data.all { it != null }, "DATA fills up to usable − controlReserved (3)")
        assertNull(p.acquire(TunnelLane.DATA), "the next DATA acquire fail-closes at effectiveCap — the reserve is off-limits to WS")
        // The break-glass headroom is still available ONLY to CONTROL (lifecycle-REST) — the whole point of the fix.
        assertNotNull(p.acquire(TunnelLane.CONTROL), "CONTROL acquires the reserved slot even under full DATA saturation")
        assertNotNull(p.acquire(TunnelLane.CONTROL), "the 2nd reserved slot is CONTROL-available too")
        assertNull(p.acquire(TunnelLane.CONTROL), "beyond the usable set even CONTROL fail-closes — no over-issue past the ids")
    }
}
