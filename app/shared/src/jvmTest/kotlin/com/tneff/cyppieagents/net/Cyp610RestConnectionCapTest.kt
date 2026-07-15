package com.tneff.cyppieagents.net

import com.tneff.cyppieagents.net.hub.pool.REST_DEDICATED_CONNS
import com.tneff.cyppieagents.net.hub.pool.TUNNEL_POOL_CAP
import com.tneff.cyppieagents.net.hub.pool.WS_RESERVED_SLOTS
import io.ktor.client.request.get
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-610 (the 6-agent-remote root fix) — the load-bearing tooth: the **REST** loopback client
 * ([pinnedCioRestHttpClient]) shares ONE connection under concurrent load (`maxConnectionsPerRoute = REST_DEDICATED_CONNS`),
 * so the dozen+ REST repos hold ≤1 Noise tunnel and can NEVER starve the [WS_RESERVED_SLOTS] persistent WS out of the
 * pool (the 1-up/6-churn). The **WS** client ([pinnedCioWsHttpClient]) is deliberately UNcapped (contrast tooth: it
 * opens many connections so each long-lived WS gets its own socket = its own tunnel). Verified behaviourally against a
 * raw HTTP/1.1 keep-alive server that tracks peak-concurrent connections — no Compose/UI, so it runs in the filtered gate.
 */
class Cyp610RestConnectionCapTest {

    /** A minimal HTTP/1.1 keep-alive server that answers every request `200 ok` and records peak-concurrent connections. */
    private class CountingHttpServer {
        private val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        val port: Int get() = server.localPort
        private val concurrent = AtomicInteger(0)
        @Volatile var peakConcurrent = 0; private set
        @Volatile private var running = true
        private val accepted = mutableListOf<Socket>()

        private val acceptor = thread(isDaemon = true, name = "cyp610-counting-server") {
            while (running) {
                val sock = try { server.accept() } catch (_: Throwable) { break }
                synchronized(accepted) { accepted += sock }
                thread(isDaemon = true) { serve(sock) }
            }
        }

        private fun serve(sock: Socket) {
            val now = concurrent.incrementAndGet()
            synchronized(this) { if (now > peakConcurrent) peakConcurrent = now }
            try {
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val out = sock.getOutputStream()
                // HTTP/1.1 keep-alive: read request lines until a blank line, respond, loop for the next request.
                while (running && !sock.isClosed) {
                    var sawRequest = false
                    while (true) {
                        val line = reader.readLine() ?: return // peer closed
                        if (line.isEmpty()) break // end of headers
                        sawRequest = true
                    }
                    if (!sawRequest) return
                    Thread.sleep(60) // hold briefly so genuinely-concurrent requests overlap (makes peak observable)
                    val body = "ok"
                    out.write(("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body").toByteArray())
                    out.flush()
                }
            } catch (_: Throwable) {
                // client reset / server stopping — end this connection
            } finally {
                concurrent.decrementAndGet()
            }
        }

        fun close() {
            running = false
            runCatching { server.close() }
            synchronized(accepted) { accepted.forEach { runCatching { it.close() } } }
            runCatching { acceptor.join(1000) }
        }
    }

    private val server = CountingHttpServer()

    @AfterTest fun tearDown() = server.close()

    private fun fireConcurrentGets(client: io.ktor.client.HttpClient, n: Int) = runBlocking {
        val url = "http://127.0.0.1:${server.port}/api/probe"
        (1..n).map { async { client.get(url).status.value } }.awaitAll()
    }

    @Test
    fun restClient_capsConcurrentConnections_toRestDedicatedConns() {
        val client = pinnedCioRestHttpClient()
        try {
            val statuses = fireConcurrentGets(client, n = 8) // 8 concurrent REST calls, one capped socket
            assertTrue(statuses.all { it == 200 }, "all REST probes must complete (serialized over the one socket)")
            // THE fix: maxConnectionsPerRoute = REST_DEDICATED_CONNS ⇒ the server never sees more than that many
            // connections at once ⇒ REST holds ≤ that many Noise tunnels ⇒ the 14 WS slots are never starved.
            assertTrue(
                server.peakConcurrent <= REST_DEDICATED_CONNS,
                "REST client must cap concurrent connections at REST_DEDICATED_CONNS=$REST_DEDICATED_CONNS, saw peak=${server.peakConcurrent}",
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun wsClient_isNotCapped_opensMultipleConnectionsUnderLoad() {
        // Contrast: the WS client is UNcapped by design (each long-lived WS needs its own socket = its own tunnel).
        // Proves the REST cap above is a real, deliberate difference — not an artefact of the test harness.
        val client = pinnedCioWsHttpClient()
        try {
            fireConcurrentGets(client, n = 8)
            assertTrue(
                server.peakConcurrent > REST_DEDICATED_CONNS,
                "the WS client must NOT be connection-capped (each WS gets its own socket), saw peak=${server.peakConcurrent}",
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun partition_fitsUsableDataBudgetAfterControl() {
        // 14 WS + 1 REST must fit the usable data-ids (cap − 1 control), with headroom at cap 24.
        val usable = TUNNEL_POOL_CAP - 1 // Control tunnel holds rendezvous-id 0
        assertTrue(
            WS_RESERVED_SLOTS + REST_DEDICATED_CONNS <= usable,
            "partition ${WS_RESERVED_SLOTS}WS + ${REST_DEDICATED_CONNS}REST must fit $usable usable ids (cap $TUNNEL_POOL_CAP − 1 control)",
        )
        assertEquals(1, REST_DEDICATED_CONNS, "REST is one shared keep-alive socket (Backend: one tunnel = one SOCKET)")
        assertTrue(WS_RESERVED_SLOTS >= 14, "the 7-agent default needs ≥14 WS (7 agent + 7 singleton, no mux)")
    }
}
