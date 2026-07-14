package com.tneff.cyppieagents.net.hub

import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.pool.BackpressureSignal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CR3 ① — [ClientLoopbackBridge] pump teeth: the workspace request bytes reach the tunnel, the hub response bytes reach
 * the local socket, and an in-flight-uncertain tunnel end (a relay drop) RESETs the local socket (the truncation guard)
 * so a truncated response is never completed as clean. The prod `RealBridgeConn` (SO_LINGER 0 = RST) + the ServerSocket
 * accept are the wiring; the pump logic is the load-bearing part, driven here over the [BridgeConn] seam.
 */
class ClientLoopbackBridgeTest {

    private class FakeTunnel(responses: List<ByteArray>) : NoiseTunnel {
        override val handshakeHash = ByteArray(32)
        val sent = CopyOnWriteArrayList<ByteArray>()
        @Volatile var closed = false
        private val inbox = ArrayDeque(responses)
        override suspend fun send(plaintext: ByteArray) { sent.add(plaintext) }
        override suspend fun receive(): ByteArray? = synchronized(inbox) { inbox.removeFirstOrNull() }
        override suspend fun close() { closed = true }
    }

    private class FakeConn(request: List<ByteArray>) : BridgeConn {
        private val outbox = ArrayDeque(request)
        val written = CopyOnWriteArrayList<ByteArray>()
        @Volatile var wasReset = false
        override suspend fun read(buf: ByteArray): Int {
            val chunk = synchronized(outbox) { outbox.removeFirstOrNull() } ?: return -1
            chunk.copyInto(buf); return chunk.size
        }
        override suspend fun write(bytes: ByteArray) { written.add(bytes) }
        override fun reset() { wasReset = true }
    }

    @Test
    fun roundTrips_requestToTunnel_responseToLocalSocket() = runBlocking {
        val req = "GET /api/agents HTTP/1.1\r\nHost: h\r\n\r\n".encodeToByteArray()
        val resp = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n[]".encodeToByteArray()
        val tunnel = FakeTunnel(listOf(resp))
        val conn = FakeConn(listOf(req))
        ClientLoopbackBridge().pump(tunnel, conn)
        assertTrue(tunnel.sent.any { it.contentEquals(req) }, "the workspace request reached the tunnel (client → hub)")
        assertTrue(conn.written.any { it.contentEquals(resp) }, "the hub response reached the local socket (hub → client)")
    }

    @Test
    fun tunnelEndMidResponse_resetsLocalSocket_truncationGuard() = runBlocking {
        // The relay drops after a PARTIAL response (the tunnel then EOFs). The local socket MUST be RESET (abortive),
        // so the workspace HttpClient aborts the in-flight request rather than reading a truncated response as clean.
        val partial = "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\npartial-body".encodeToByteArray()
        val tunnel = FakeTunnel(listOf(partial)) // receive() → partial, then null (the drop)
        val conn = FakeConn(listOf("GET /api/agents\r\n\r\n".encodeToByteArray()))
        ClientLoopbackBridge().pump(tunnel, conn)
        assertTrue(conn.wasReset, "an in-flight-uncertain tunnel end RESETs the local socket (never completes a truncated response as clean)")
    }

    @Test
    fun rejectsNonLoopbackHost_failClosed() {
        assertFailsWith<IllegalArgumentException> { ClientLoopbackBridge("0.0.0.0") }
        assertFailsWith<IllegalArgumentException> { ClientLoopbackBridge("10.0.0.5") }
    }

    // --- CYP-535 (H7) — the ≤8-frame per-tunnel send-window: bounded in-flight, backpressure signal, no deadlock ---

    /** A tunnel whose [send] never completes (a stalled relay), recording any [BackpressureSignal] toggles. */
    private class StallingSendTunnel : NoiseTunnel, BackpressureSignal {
        override val handshakeHash = ByteArray(32)
        private val stall = CompletableDeferred<Unit>()
        val backpressured = CopyOnWriteArrayList<Boolean>()
        override suspend fun send(plaintext: ByteArray) { stall.await() } // never drains → the window fills
        override suspend fun receive(): ByteArray? = CompletableDeferred<ByteArray?>().await() // no downstream traffic
        override suspend fun close() { stall.complete(Unit) }
        override fun onBackpressured(active: Boolean) { backpressured.add(active) }
    }

    /** A conn that yields [frame] up to [limit] times (counting reads), then EOFs — probes how far the reader got. */
    private class CountingConn(private val frame: ByteArray, private val limit: Int) : BridgeConn {
        val reads = AtomicInteger(0)
        override suspend fun read(buf: ByteArray): Int {
            if (reads.getAndIncrement() >= limit) return -1
            frame.copyInto(buf); return frame.size
        }
        override suspend fun write(bytes: ByteArray) = Unit
        override fun reset() = Unit
    }

    @Test
    fun stalledTunnel_boundsInFlightToWindow_readerBlocks_memoryDoesNotGrow() = runBlocking {
        // With the tunnel-send stalled, the reader may only get ≤ window (8) + the one in the sender + the one it's
        // blocked on ≈ 10 frames into flight, then it SUSPENDS (TCP backpressure). It must NOT drain all 200 frames
        // (the pre-H7 implicit/unbounded buffer would). Bounded in-flight = bounded memory.
        val tunnel = StallingSendTunnel()
        val conn = CountingConn(frame = ByteArray(64) { 1 }, limit = 200)
        val job = launch { ClientLoopbackBridge().pump(tunnel, conn) }
        delay(300) // let the window fill + the reader block
        val consumed = conn.reads.get()
        assertTrue(consumed in 1..12, "in-flight is bounded to ~window (was $consumed) — the reader blocked, memory did not grow to 200")
        job.cancelAndJoin()
    }

    @Test
    fun stalledTunnel_raisesBackpressureSignal_forPooledTunnel() = runBlocking {
        // A pooled tunnel (BackpressureSignal) sees the full window as C3 BACKPRESSURED (onBackpressured(true)).
        val tunnel = StallingSendTunnel()
        val conn = CountingConn(frame = ByteArray(64) { 2 }, limit = 200)
        val job = launch { ClientLoopbackBridge().pump(tunnel, conn) }
        delay(300)
        assertTrue(tunnel.backpressured.any { it }, "a full send-window raises the backpressure signal (→ C3 BACKPRESSURED)")
        job.cancelAndJoin()
    }

    @Test
    fun multiFrameBothDirections_completesWithoutDeadlock_windowDrains() = runBlocking {
        // Deadlock check (H7 §2): several frames each way, both directions independent → the window drains and the
        // pump completes cleanly. The request frames all reach the tunnel; the response frames all reach the socket.
        val reqFrames = List(20) { "req-$it".encodeToByteArray() }
        val respFrames = List(20) { "resp-$it".encodeToByteArray() }
        val tunnel = FakeTunnel(respFrames)
        val conn = FakeConn(reqFrames)
        ClientLoopbackBridge().pump(tunnel, conn) // returns ⇒ no deadlock under the window
        assertTrue(tunnel.sent.size >= 20, "every request frame drained through the window to the tunnel (${tunnel.sent.size})")
        assertTrue(conn.written.size >= 20, "every response frame reached the local socket (${conn.written.size})")
    }
}
