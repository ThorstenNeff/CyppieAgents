package com.tneff.cyppieagents.net.hub.pool

import com.tneff.cyppieagents.net.hub.noise.ClientNoiseTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.noise.RelayChannel
import com.tneff.cyppieagents.net.hub.relay.RelayWsConnector
import com.tneff.cyppieagents.net.hub.relay.RendezvousResolution
import com.tneff.cyppieagents.net.hub.relay.RendezvousResolver
import com.tneff.cyppieagents.net.hub.remote.HubTrust
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthOutcome
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthenticator
import com.tneff.cyppieagents.net.hub.remote.TrustResolution
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-537 (M2 Option A, WS2) — the **2-concurrent-tunnel milestone**, proven through the FULL client path composed:
 * `PooledTunnelSource` + the real [NoisePoolTunnelDialer] over Backend's WS1 seams (resolver + id-aware connector).
 * The single differences from the live e2e are the fakes for the crypto/network edges (transport/trust/auth/relay);
 * the pool → resolve → `drop(1)` → per-id dial → handshake → PoP wiring is the real one. This is the local stand-in
 * for the co-located Backend e2e (WS4 graduates it fake→real against the live N-responder).
 */
class Cyp537PoolMilestoneTest {

    private class FakeTunnel(val id: String) : NoiseTunnel {
        override val handshakeHash = ByteArray(32)
        var closed = false
        override suspend fun send(plaintext: ByteArray) = Unit
        override suspend fun receive(): ByteArray? = null
        override suspend fun close() { closed = true }
    }

    private class FakeRelay(val rendezvousId: String) : RelayChannel {
        override suspend fun send(frame: ByteArray) = Unit
        override suspend fun receive(): ByteArray? = null
        override suspend fun close() = Unit
    }

    private fun realDialerPool(): Pair<PooledTunnelSource, MutableList<String>> {
        val openedIds = mutableListOf<String>()
        // The hub always Grants + a stable Pinned trust; the transport mints a distinct tunnel tagged by the dialed id.
        val transport = object : ClientNoiseTransport {
            override suspend fun connect(pinnedHubStatic: ByteArray, relay: RelayChannel, prologue: ByteArray): NoiseTunnel =
                FakeTunnel((relay as FakeRelay).rendezvousId)
        }
        val dialer = NoisePoolTunnelDialer(
            hubId = "hub-1",
            transport = transport,
            trust = HubTrust { TrustResolution.Pinned(ByteArray(32) { 9 }) },
            authenticator = OperatorAuthenticator { _, _ -> OperatorAuthOutcome.Granted },
            // Backend WS1: the CP epoch-set = base id-0 + the workspace ids id-1..id-3 (cap=4 responders).
            resolver = RendezvousResolver { RendezvousResolution.Bound("id-0", "ws://relay/x", listOf("id-0", "id-1", "id-2", "id-3")) },
            connector = RelayWsConnector { _, id -> openedIds.add(id); FakeRelay(id) },
        )
        return PooledTunnelSource(dialer = dialer, nowMs = { 0L }) to openedIds
    }

    @Test
    fun twoConcurrentTunnels_throughTheRealDialer_dropTheBaseId() = runTest {
        val (pool, openedIds) = realDialerPool()
        val a = assertNotNull(pool.acquire(), "first workspace tunnel")
        val b = assertNotNull(pool.acquire(), "second workspace tunnel")
        assertTrue(a !== b, "two DISTINCT concurrent tunnels (the F-M2-1 fix, through the real establishment path)")
        // The pool dials id_1 then id_2 — NEVER id_0 (the session's control tunnel holds it: no 1↔1 collision).
        assertEquals(listOf("id-1", "id-2"), openedIds, "the pool dials the epoch-set MINUS the base id, in order")
        assertEquals(2, pool.state.value.aggregate.active)
        assertTrue(pool.state.value.tunnels.map { it.rendezvousId }.toSet() == setOf("id-1", "id-2"))
    }

    @Test
    fun concurrentAcquires_bothEstablish_capBoundedByDroppedSet() = runTest {
        val (pool, _) = realDialerPool() // set = [id-0..id-3] ⇒ after drop(1), 3 dialable ids (id-1,id-2,id-3)
        val results = listOf(async { pool.acquire() }, async { pool.acquire() }, async { pool.acquire() }).awaitAll()
        assertTrue(results.all { it != null }, "all three concurrent acquires establish (3 workspace ids available)")
        assertEquals(3, pool.state.value.aggregate.active)
        assertNull(pool.acquire(), "the 4th caps out — only 3 workspace ids after dropping the base id (fail-closed)")
    }
}
