package com.tneff.cyppieagents.net.hub

import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
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
}
