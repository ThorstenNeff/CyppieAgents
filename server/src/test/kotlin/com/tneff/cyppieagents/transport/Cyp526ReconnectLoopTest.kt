package com.tneff.cyppieagents.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-526 — the hub-side relay dial-out is now a PERSISTENT responder (was a one-shot that died on the first transient
 * throw between a successful rendezvous-register and an established relay WS → the hub was never present → client msg1
 * unanswered → relay closes). These teeth pin: re-dial after a tunnel ends, recover after a caught dial failure (no
 * more silent death), and — the anti-rotation invariant — re-dial with the SAME cached rendezvous id (never re-register,
 * which would rotate the epoch id and strand the client's resolved id).
 */
class Cyp526ReconnectLoopTest {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> /* swallow fail-closed throws */ },
    )
    @AfterTest fun tearDown() = scope.cancel()

    private class FakeRelayChannel : ServerRelayChannel {
        override suspend fun send(frame: ByteArray) {}
        override suspend fun receive(): ByteArray? = null
        override suspend fun close() {}
    }
    private class FakeTunnel : ServerNoiseTunnel {
        override val handshakeHash = ByteArray(32)
        override suspend fun send(plaintext: ByteArray) {}
        override suspend fun receive(): ByteArray? = null
        override suspend fun close() {}
    }
    private class FakeTerminator : ServerNoiseTerminator {
        override suspend fun terminate(relay: ServerRelayChannel): ServerNoiseTunnel = FakeTunnel()
    }

    @Test
    fun reDialsAfterTunnelEnds_persistentResponder() = runBlocking {
        val dials = AtomicInteger(0)
        val dialer = object : RelayDialer {
            override suspend fun dial(relayUrl: String): ServerRelayChannel { dials.incrementAndGet(); return FakeRelayChannel() }
        }
        // The handler returns immediately → the tunnel "ends" → a persistent responder MUST re-dial (a one-shot stops at 1).
        val c = NoiseRelayConnector(RemoteTransportConfig(enabled = true, relayUrl = "wss://relay/x"), dialer, FakeTerminator(), { }, scope, backoffMs = { 0L })
        c.start()
        withTimeout(5_000) { while (dials.get() < 3) delay(10) }
        assertTrue(dials.get() >= 3, "re-dials after each tunnel end (persistent responder, not one-shot): ${dials.get()}")
        c.stop()
    }

    @Test
    fun recoversAfterCaughtDialFailure_noSilentDeath() = runBlocking {
        val dials = AtomicInteger(0)
        val established = CompletableDeferred<Unit>()
        val dialer = object : RelayDialer {
            override suspend fun dial(relayUrl: String): ServerRelayChannel {
                val n = dials.incrementAndGet()
                if (n <= 2) throw RuntimeException("transient dial failure #$n") // the boot-window transient (silent-death in the old one-shot)
                return FakeRelayChannel()
            }
        }
        val handler: suspend (ServerNoiseTunnel) -> Unit = { established.complete(Unit); awaitCancellation() } // hold as responder
        val c = NoiseRelayConnector(RemoteTransportConfig(enabled = true, relayUrl = "wss://relay/x"), dialer, FakeTerminator(), handler, scope, backoffMs = { 0L })
        c.start()
        withTimeout(5_000) { established.await() } // the loop CAUGHT 2 throws (didn't die) and established on the 3rd dial
        assertTrue(dials.get() >= 3, "a dial failure is caught (not fatal) and retried until the responder is established: ${dials.get()}")
        c.stop()
    }

    @Test
    fun reDial_reusesCachedRendezvousId_neverReRegisters_antiRotation() = runBlocking {
        val registrations = AtomicInteger(0)
        val cachingId = CachingRendezvousId { "id-${registrations.incrementAndGet()}" } // register mints a FRESH id each call
        val idsSeen = CopyOnWriteArrayList<String?>()
        val dialer = object : RelayDialer {
            // the production WebSocketRelayDialer calls rendezvousId() per dial; here that provider IS the cache.
            override suspend fun dial(relayUrl: String): ServerRelayChannel { idsSeen.add(cachingId.get()); return FakeRelayChannel() }
        }
        val c = NoiseRelayConnector(RemoteTransportConfig(enabled = true, relayUrl = "wss://relay/x"), dialer, FakeTerminator(), { }, scope, backoffMs = { 0L })
        c.start()
        withTimeout(5_000) { while (idsSeen.size < 3) delay(10) }
        c.stop()
        assertEquals(1, registrations.get(), "register ran ONCE — the reconnect loop never re-registers (no epoch rotation)")
        assertTrue(idsSeen.take(3).all { it == "id-1" }, "every re-dial reused the SAME cached id (anti-rotation): ${idsSeen.toList()}")
    }
}
