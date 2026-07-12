package com.tneff.cyppieagents.relay

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-506 — the untrusted rendezvous relay. The 4 security teeth run against the transport-agnostic core
 * ([RendezvousRelay] + in-memory [FakePeer]) so they are deterministic; 3 integration tests exercise the live Ktor
 * WS route (header pairing, real binary forward, fail-closed on missing headers) + health.
 *
 * ★ Mutation map (revert the guard → the named tooth reds):
 *  - forward `to.send(from.receive())` byte-copy → [t1a]/[t1b]/[t4] catch any decode/mutate/merge/split.
 *  - drop the `self.complete` reject → [t3b] (a 3rd peer would splice into a live pair) reds.
 *  - pair on anything but exact id equality → [t2] (cross-rendezvous forward) reds.
 *  - forward before both present → [t3a] (a lone peer would forward) reds.
 */
class Cyp506RelayServerTest {

    // ---------- in-memory peer (transport-agnostic core proof) ----------

    private class FakePeer(override val rendezvousId: String, override val role: RelayRole) : RelayPeer {
        private val inbound = Channel<ByteArray>(Channel.UNLIMITED)   // frames this peer SENDS INTO the relay
        val delivered = Channel<ByteArray>(Channel.UNLIMITED)          // frames the relay FORWARDED to this peer (sink)
        @Volatile var closed = false; private set

        fun enqueue(vararg frames: ByteArray) = frames.forEach { check(inbound.trySend(it).isSuccess) }
        fun endInput() { inbound.close() }

        override suspend fun receive(): ByteArray? = inbound.receiveCatching().getOrNull()
        override suspend fun send(frame: ByteArray) { delivered.send(frame) }
        // Closes ONLY the inbound (a real close ends the session); `delivered` stays open as a test sink so a
        // teardown race can never drop an already-forwarded frame from the assertion.
        override suspend fun close() { closed = true; inbound.close() }
    }

    private fun Channel<ByteArray>.drainNow(): List<ByteArray> =
        generateSequence { tryReceive().getOrNull() }.toList()

    /** Pair two peers and run to completion (both join()s return when the pair tears down). */
    private suspend fun RendezvousRelay.pairAndRun(a: FakePeer, b: FakePeer) = coroutineScope {
        val ja = launch { join(a) }
        val jb = launch { join(b) }
        ja.join(); jb.join()
    }

    // ---------- ★ T1 — never plaintext / byte-identical passthrough (both directions) ----------

    @Test fun t1a_hubToClient_byteIdentical_inclNonUtf8() = runTest {
        val relay = RendezvousRelay()
        val hub = FakePeer("rzv", RelayRole.HUB)
        val client = FakePeer("rzv", RelayRole.CLIENT)
        val nonUtf8 = byteArrayOf(0xFF.toByte(), 0x00, 0xDE.toByte(), 0xAD.toByte())
        hub.enqueue(nonUtf8); hub.endInput() // client input stays open → clean single-direction teardown
        relay.pairAndRun(hub, client)
        assertContentEquals(nonUtf8, client.delivered.drainNow().single(), "hub→client forwarded verbatim (opaque bytes)")
        assertTrue(hub.delivered.drainNow().isEmpty(), "nothing echoed back to the sender")
    }

    @Test fun t1b_clientToHub_byteIdentical() = runTest {
        val relay = RendezvousRelay()
        val hub = FakePeer("rzv", RelayRole.HUB)
        val client = FakePeer("rzv", RelayRole.CLIENT)
        val payload = byteArrayOf(0x10, 0x20, 0x30)
        client.enqueue(payload); client.endInput()
        relay.pairAndRun(hub, client)
        assertContentEquals(payload, hub.delivered.drainNow().single(), "client→hub forwarded verbatim")
    }

    // ---------- ★ T4 — frame boundaries preserved (no split / merge / splice) ----------

    @Test fun t4_frameBoundaries_preserved_oneForOne() = runTest {
        val relay = RendezvousRelay()
        val hub = FakePeer("rzv", RelayRole.HUB)
        val client = FakePeer("rzv", RelayRole.CLIENT)
        hub.enqueue(byteArrayOf(1), byteArrayOf(2, 2), byteArrayOf(3, 3, 3)); hub.endInput()
        relay.pairAndRun(hub, client)
        val got = client.delivered.drainNow()
        assertEquals(3, got.size, "3 frames in → 3 frames out (no merge/split)")
        assertContentEquals(byteArrayOf(1), got[0])
        assertContentEquals(byteArrayOf(2, 2), got[1])
        assertContentEquals(byteArrayOf(3, 3, 3), got[2])
    }

    // ---------- ★ T2 — rendezvous id is an OPAQUE equality key (no cross-pair, never parsed) ----------

    @Test fun t2_differentRendezvousIds_neverCrossPair() = runTest {
        val relay = RendezvousRelay()
        val hubA = FakePeer("rzv-A", RelayRole.HUB).apply { enqueue(byteArrayOf(1)) }
        val clientB = FakePeer("rzv-B", RelayRole.CLIENT).apply { enqueue(byteArrayOf(2)) }
        val ja = launch { relay.join(hubA) }
        val jb = launch { relay.join(clientB) }
        runCurrent()
        assertEquals(2, relay.activePairings(), "different opaque ids form SEPARATE unpaired slots — no cross-pair")
        assertTrue(clientB.delivered.drainNow().isEmpty(), "a hub on rzv-A never forwards to a client on rzv-B")
        assertTrue(hubA.delivered.drainNow().isEmpty())
        ja.cancel(); jb.cancel()
    }

    // ---------- ★ T3 — unpaired → no forward; 3rd/duplicate-role peer rejected + closed ----------

    @Test fun t3a_lonePeer_forwardsNothing() = runTest {
        val relay = RendezvousRelay()
        val hub = FakePeer("rzv", RelayRole.HUB).apply { enqueue(byteArrayOf(9)) }
        val job = launch { relay.join(hub) }
        runCurrent()
        assertEquals(1, relay.activePairings(), "the lone peer is registered but UNPAIRED")
        assertTrue(hub.delivered.drainNow().isEmpty(), "an unpaired peer forwards nothing")
        assertFalse(hub.closed, "still waiting for a partner (not torn down)")
        job.cancel()
    }

    @Test fun t3b_thirdPeer_rejectedAndClosed_notSpliced() = runTest {
        val relay = RendezvousRelay()
        val hub = FakePeer("rzv", RelayRole.HUB)
        val client = FakePeer("rzv", RelayRole.CLIENT)
        val jh = launch { relay.join(hub) }
        val jc = launch { relay.join(client) }
        runCurrent() // hub+client paired and forwarding (both inputs open)

        val secondHub = FakePeer("rzv", RelayRole.HUB)
        relay.join(secondHub) // returns at once — the HUB slot is taken
        assertTrue(secondHub.closed, "a 2nd HUB on a full rendezvous is rejected + closed, never spliced")

        val secondClient = FakePeer("rzv", RelayRole.CLIENT)
        relay.join(secondClient)
        assertTrue(secondClient.closed, "a 2nd CLIENT on a full rendezvous is rejected + closed")
        assertTrue(secondHub.delivered.drainNow().isEmpty() && secondClient.delivered.drainNow().isEmpty(), "rejected peers get no frames")

        hub.endInput(); client.endInput(); jh.join(); jc.join() // clean teardown
    }

    @Test fun teardown_whenOneSideCloses_dropsThePair() = runTest {
        val relay = RendezvousRelay()
        val hub = FakePeer("rzv", RelayRole.HUB)
        val client = FakePeer("rzv", RelayRole.CLIENT)
        hub.endInput() // hub hangs up immediately
        relay.pairAndRun(hub, client)
        assertTrue(client.closed, "when the hub closes, the client leg is torn down too (no half-open)")
        assertEquals(0, relay.activePairings(), "the pairing is removed on teardown")
    }

    // ---------- integration — live Ktor WS route ----------

    @Test fun integration_health_returnsOk() = testApplication {
        application { relayModule() }
        val ws = createClient { install(ClientWebSockets) }
        assertEquals("ok", ws.get("/health").bodyAsText())
    }

    @Test fun integration_pairsAndForwards_overRealWebSockets() = testApplication {
        application { relayModule() }
        val ws = createClient { install(ClientWebSockets) }
        val delivered = CompletableDeferred<ByteArray>()
        coroutineScope {
            val clientJob = launch {
                ws.webSocket("/relay", request = { header(RENDEZVOUS_HEADER, "rzv1"); header(ROLE_HEADER, "client") }) {
                    val f = incoming.receive()
                    delivered.complete((f as Frame.Binary).readBytes())
                }
            }
            val hubJob = launch {
                ws.webSocket("/relay", request = { header(RENDEZVOUS_HEADER, "rzv1"); header(ROLE_HEADER, "hub") }) {
                    send(Frame.Binary(true, byteArrayOf(7, 7, 7)))
                    delivered.await() // hold the hub session open until the frame is delivered
                }
            }
            assertContentEquals(byteArrayOf(7, 7, 7), withTimeout(5_000) { delivered.await() }, "binary frame forwarded hub→client over real WS")
            clientJob.cancel(); hubJob.cancel()
        }
    }

    @Test fun integration_missingHeaders_failClosed_violatedPolicy() = testApplication {
        application { relayModule() }
        val ws = createClient { install(ClientWebSockets) }
        ws.webSocket("/relay") { // no rendezvous / role headers
            val reason = withTimeout(5_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code, "a header-less dial is fail-closed")
        }
    }
}
