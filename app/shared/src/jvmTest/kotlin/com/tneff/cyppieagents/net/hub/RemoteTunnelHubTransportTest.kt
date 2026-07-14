package com.tneff.cyppieagents.net.hub

import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2 Seam-1 — [RemoteTunnelHubTransport] teeth: the transport exposes a **loopback** base (the workspace dials 127.0.0.1,
 * NEVER the hub's LAN host:port), and an accepted loopback connection's bytes **flow over the Noise tunnel** (the datapath
 * proof — the whole point of M2). Fail-closed when no tunnel is live (RST, never a local/plaintext fallback). The tunnel
 * is RemoteHubSession-owned → the transport's [close] never closes it.
 */
class RemoteTunnelHubTransportTest {

    private class FakeTunnel(responses: List<ByteArray> = emptyList()) : NoiseTunnel {
        override val handshakeHash = ByteArray(32)
        val sent = CopyOnWriteArrayList<ByteArray>()
        @Volatile var closed = false
        private val inbox = ArrayDeque(responses)
        override suspend fun send(plaintext: ByteArray) { sent.add(plaintext) }
        override suspend fun receive(): ByteArray? = synchronized(inbox) { inbox.removeFirstOrNull() }
        override suspend fun close() { closed = true }
    }

    private class FakeConn(request: List<ByteArray> = emptyList()) : BridgeConn {
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

    /** Yields the queued connections once each, then `null` (→ the accept-loop ends, so acceptJob completes for join()). */
    private class FakeAcceptor(override val port: Int, conns: List<BridgeConn> = emptyList()) : LoopbackAcceptor {
        private val queue = ArrayDeque(conns)
        @Volatile var closed = false
        override suspend fun accept(): BridgeConn? = synchronized(queue) { queue.removeFirstOrNull() }
        override fun close() { closed = true }
    }

    private fun transport(
        tunnel: NoiseTunnel?,
        acceptor: LoopbackAcceptor,
        scope: CoroutineScope,
        token: String? = "cp-ticket",
    ) = RemoteTunnelHubTransport(
        tunnelSource = { tunnel },
        sessionTokenProvider = { token },
        scope = scope,
        acceptor = acceptor,
        injectedClient = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO), // test-owned, closed via transport.close()
    )

    @Test
    fun baseUrls_pointToLoopback_notLocalHub() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO)
        val t = transport(FakeTunnel(), FakeAcceptor(port = 54321), scope)
        // The workspace must dial the loopback (every byte then rides the tunnel) — NEVER the hub's real host:port.
        assertEquals("http://127.0.0.1:54321", t.httpBaseUrl)
        assertEquals("ws://127.0.0.1:54321", t.wsBaseUrl)
        t.close(); scope.cancel()
    }

    @Test
    fun acceptedConnection_bytesFlowOverTunnel_theDatapathProof() = runBlocking {
        val req = "GET /api/agents HTTP/1.1\r\nHost: h\r\n\r\n".encodeToByteArray()
        val resp = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n[]".encodeToByteArray()
        val tunnel = FakeTunnel(listOf(resp))
        val conn = FakeConn(listOf(req))
        val scope = CoroutineScope(Dispatchers.IO)
        val t = transport(tunnel, FakeAcceptor(port = 9000, conns = listOf(conn)), scope)
        t.acceptJob.join() // the loop accepts the one conn, pumps it over the tunnel, then drains → completes
        assertTrue(tunnel.sent.any { it.contentEquals(req) }, "the workspace REQUEST flowed over the Noise tunnel (client → hub)")
        assertTrue(conn.written.any { it.contentEquals(resp) }, "the hub RESPONSE flowed back to the loopback socket (hub → client)")
        t.close(); scope.cancel()
    }

    @Test
    fun noLiveTunnel_failsClosed_resetsConnection_neverLocalFallback() = runBlocking {
        val conn = FakeConn(listOf("GET / HTTP/1.1\r\n\r\n".encodeToByteArray()))
        val scope = CoroutineScope(Dispatchers.IO)
        val t = transport(tunnel = null, acceptor = FakeAcceptor(port = 9001, conns = listOf(conn)), scope = scope)
        t.acceptJob.join()
        assertTrue(conn.wasReset, "no live tunnel ⇒ the connection is RESET (fail-closed), never carried in the clear")
        t.close(); scope.cancel()
    }

    @Test
    fun close_closesAcceptor_butNotTheSessionOwnedTunnel() = runBlocking {
        val tunnel = FakeTunnel()
        val acceptor = FakeAcceptor(port = 9002)
        val scope = CoroutineScope(Dispatchers.IO)
        val t = transport(tunnel, acceptor, scope)
        t.close()
        assertTrue(acceptor.closed, "close() closes the transport's own acceptor")
        assertFalse(tunnel.closed, "the tunnel is RemoteHubSession-owned (Seam-3) — the transport never closes it")
        scope.cancel()
    }

    @Test
    fun sessionToken_delegatesToProvider() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO)
        val t = transport(FakeTunnel(), FakeAcceptor(port = 9003), scope, token = "hub-ticket-xyz")
        assertEquals("hub-ticket-xyz", t.sessionToken())
        t.close(); scope.cancel()
    }

    @Test
    fun sessionToken_neverHardcoded_G1_noStaticGodTokenFallback() = runBlocking {
        // M2 (c)/G1 (Reviewer Axis-1): the bridged request may carry ONLY the injected CP-scoped operator session —
        // the transport has NO static/god-token fallback. A null provider yields null (not a hardcoded token), so the
        // client can NEVER send the static OPERATOR_TOKEN over the tunnel (App.kt injects authRepo.currentSessionToken).
        val scope = CoroutineScope(Dispatchers.IO)
        val t = transport(FakeTunnel(), FakeAcceptor(port = 9100), scope, token = null)
        assertNull(t.sessionToken(), "no injected session ⇒ null, never a hardcoded/static god-token")
        t.close(); scope.cancel()
    }

    /** A long-lived connection: signals it has started being pumped, then its read blocks forever (like a live WS that
     *  never closes). Used to prove N pumps run CONCURRENTLY through the real accept-loop. */
    private class LongLivedConn(private val onPumpStarted: () -> Unit) : BridgeConn {
        private val hold = CompletableDeferred<Unit>() // never completed → read suspends forever (until cancelled)
        override suspend fun read(buf: ByteArray): Int {
            onPumpStarted() // the pump's upstream reader reached this connection → it is being served
            hold.await()    // block like a live, open WS
            return -1
        }
        override suspend fun write(bytes: ByteArray) {}
        override fun reset() {}
    }

    @Test
    fun acceptLoop_servesNConnectionsConcurrently_notSingleFlight_CYP556() = runBlocking {
        // CYP-556 fidelity: the WS4 harness proved the pool/dialer; THIS proves the REAL accept-loop serves N
        // long-lived connections CONCURRENTLY (per-connection coroutine), not single-flight. Mutant: revert the
        // accept-loop to an inline `bridge.pump(...)` ⇒ only the 1st connection is ever pumped (the loop suspends
        // at the inline pump and never accepts the rest) ⇒ `started` stalls at 1 ⇒ this times out ⇒ RED (= the
        // F-M2-1 regression this fix closes).
        val n = 4
        val started = AtomicInteger(0)
        val allStarted = CompletableDeferred<Unit>()
        val conns = (0 until n).map { LongLivedConn { if (started.incrementAndGet() == n) allStarted.complete(Unit) } }
        val scope = CoroutineScope(Dispatchers.IO)
        val t = RemoteTunnelHubTransport(
            tunnelSource = { FakeTunnel() }, // a fresh (distinct) tunnel per connection, like PooledTunnelSource
            sessionTokenProvider = { "cp-ticket" },
            scope = scope,
            acceptor = FakeAcceptor(port = 9200, conns = conns),
            injectedClient = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO),
        )
        withTimeout(5_000) { allStarted.await() } // all N pumped at once ⇒ completes; single-flight ⇒ times out (RED)
        assertEquals(n, started.get(), "all N accepted connections are pumped CONCURRENTLY (per-connection, not single-flight)")
        t.close(); scope.cancel()
    }

    @Test
    fun factory_jvm_buildsRealLoopbackTransport_notFailLoudStub() = runBlocking {
        // Seam-3 (a): the commonMain factory returns the REAL tunnel-backed transport on jvm (Path-A), not the
        // fail-loud RemoteHubTransport() stub — the loopback base is what makes AgentShell operate over the tunnel.
        val scope = CoroutineScope(Dispatchers.IO)
        val t = buildRemoteHubTransport(acquireTunnel = { null }, sessionToken = { "cp-ticket" }, scope = scope)
        assertTrue(t != null, "jvm factory builds a transport (Path-A Desktop)")
        assertTrue(t!!.httpBaseUrl.startsWith("http://127.0.0.1:"), "it is the loopback tunnel transport, never the fail-loud stub")
        t.close(); scope.cancel()
    }
}
