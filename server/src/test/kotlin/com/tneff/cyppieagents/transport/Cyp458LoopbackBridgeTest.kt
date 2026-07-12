package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.routing.ApiException
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.requireAgent
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CYP-458 (S2) — the [LoopbackBridge] teeth (Reviewer-adversarial-gated). The bridge presents a terminated Noise L2
 * (a fake tunnel here — the real crypto is CYP-457) to the **real** hub auth guard over a real `127.0.0.1` socket:
 *  - **T1** loopback-without-auth → **401**, **+ positive** the SAME loopback WITH a valid bearer → **200** (non-vacuous);
 *  - **T2 ★ HEADLINE** a forged "already-authenticated" marker on a loopback request (no real credential) → still
 *    **401** — the route re-verifies the real bearer, never a transport claim; the loopback origin grants nothing;
 *  - **T3** the bridge target MUST be loopback — a wildcard/public host fails closed at construction;
 *  - **AC2 truncation-guard** an untrusted relay drop → the hub socket is **RESET (RST)**, never a clean EOF.
 *
 * The guard used is the production [requireAgent] (bearer-only), mounted exactly as a route sees it.
 */
class Cyp458LoopbackBridgeTest {

    // ---- controllable fake L2 tunnel (client bytes in, hub bytes captured) ----
    private class ControllableTunnel : ServerNoiseTunnel {
        private val inbound = Channel<ByteArray>(Channel.UNLIMITED)  // test → bridge (client→hub)
        val outbound = Channel<ByteArray>(Channel.UNLIMITED)         // bridge → test (hub→client)
        override val handshakeHash: ByteArray get() = ByteArray(32)
        override suspend fun receive(): ByteArray? = inbound.receiveCatching().getOrNull()
        override suspend fun send(plaintext: ByteArray) { outbound.trySend(plaintext) }
        override suspend fun close() { inbound.close(); outbound.close() }
        fun deliver(bytes: ByteArray) { inbound.trySend(bytes) }
        /** The UNTRUSTED relay/L2 dropping — `receive()` then returns null (the truncation trigger). */
        fun dropRelay() { inbound.close() }
    }

    private val cleanups = mutableListOf<() -> Unit>()
    @AfterTest fun tearDown() { cleanups.forEach { runCatching { it() } }; cleanups.clear() }

    /** A real loopback Netty listener with the production ApiException→status mapping and a bearer-guarded route. */
    private fun startGuardedServer(registry: TokenRegistry): Int {
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            install(StatusPages) { exception<ApiException> { call, e -> call.respond(e.status) } }
            routing { get("/guarded") { call.requireAgent(registry); call.respondText("ok") } }
        }.start(wait = false)
        cleanups += { server.stop(0, 0) }
        return runBlocking { server.engine.resolvedConnectors() }.first().port
    }

    /** Drive one raw HTTP/1.1 request through the bridge → the loopback listener; return the raw response text. */
    private fun driveRequest(port: Int, request: String): String = runBlocking {
        withTimeout(15_000) {
            val tunnel = ControllableTunnel()
            val bridgeJob = launch(Dispatchers.IO) { LoopbackBridge(port).bridge(tunnel) }
            tunnel.deliver(request.encodeToByteArray())
            bridgeJob.join() // completes when the hub closes (Connection: close) and both directions drain
            val sb = StringBuilder()
            while (true) {
                val chunk = tunnel.outbound.receiveCatching().getOrNull() ?: break
                sb.append(chunk.decodeToString())
            }
            sb.toString()
        }
    }

    private fun httpGet(path: String, headers: List<String>): String =
        (listOf("GET $path HTTP/1.1", "Host: 127.0.0.1", "Connection: close") + headers)
            .joinToString("\r\n") + "\r\n\r\n"

    private fun statusLine(resp: String) = resp.lineSequence().first().trim()

    @Test
    fun t1_loopbackWithoutAuth_rejected_andWithAuth_served() {
        val registry = TokenRegistry(mapOf("valid-token" to "backend"), operatorToken = null)
        val port = startGuardedServer(registry)

        // T1 negative — no credential over the loopback bridge → 401 (exactly as a network request).
        val noAuth = driveRequest(port, httpGet("/guarded", emptyList()))
        assertTrue(statusLine(noAuth).contains("401"), "loopback without a credential is rejected: '${statusLine(noAuth)}'")

        // ★ positive leg (non-vacuous): the SAME loopback path WITH a real bearer → 200.
        val withAuth = driveRequest(port, httpGet("/guarded", listOf("Authorization: Bearer valid-token")))
        assertTrue(statusLine(withAuth).contains("200"), "a properly-authed loopback request is served: '${statusLine(withAuth)}'")
    }

    @Test
    fun t2_forgedAlreadyAuthMarker_overLoopback_stillRejected() {
        val registry = TokenRegistry(mapOf("valid-token" to "backend"), operatorToken = null)
        val port = startGuardedServer(registry)

        // ★ HEADLINE: a request carrying markers a naive route MIGHT read as "local ⇒ already trusted", but NO real
        // bearer. The route re-verifies the credential and ignores the transport claim → 401. A route that trusted
        // the marker (the mutation) would serve it = RED.
        val forged = driveRequest(
            port,
            httpGet("/guarded", listOf(
                "X-Already-Authenticated: true",
                "X-Forwarded-For: 127.0.0.1",
                "X-Local-Request: true",
            )),
        )
        assertTrue(statusLine(forged).contains("401"), "a forged already-auth marker over loopback is still rejected: '${statusLine(forged)}'")
    }

    @Test
    fun t3_nonLoopbackTarget_failsClosedAtConstruction() {
        // A wildcard/public bridge target would re-expose the routes → rejected fail-closed (never binds 0.0.0.0).
        assertFailsWith<IllegalArgumentException> { LoopbackBridge(8080, "0.0.0.0") }
        assertFailsWith<IllegalArgumentException> { LoopbackBridge(8080, "10.0.0.5") }
        // A loopback target is accepted.
        LoopbackBridge(8080, "127.0.0.1")
        LoopbackBridge(8080, "localhost")
    }

    @Test
    fun ac2_relayDropMidRequest_resetsHubSocket_notCleanEof() = runBlocking {
        withTimeout(15_000) {
            // A raw loopback "hub" that reads what the bridge sends, then reads again after the relay drops.
            val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
            cleanups += { runCatching { server.close() } }
            val port = server.localPort

            val partialSeen = CompletableDeferred<Int>()
            val secondRead = CompletableDeferred<Result<Int>>()
            launch(Dispatchers.IO) {
                val conn = server.accept()
                cleanups += { runCatching { conn.close() } }
                val input = conn.getInputStream()
                val buf = ByteArray(4096)
                partialSeen.complete(input.read(buf))          // the partial request bytes
                secondRead.complete(runCatching { input.read(buf) }) // after the drop: RST → throws; clean EOF → -1
            }

            val tunnel = ControllableTunnel()
            val bridgeJob = launch(Dispatchers.IO) { LoopbackBridge(port).bridge(tunnel) }
            tunnel.deliver("GET /slow HTTP/1.1\r\nHost: x\r\n".encodeToByteArray()) // a partial request (no terminator)
            assertTrue(partialSeen.await() > 0, "the hub received the in-flight request bytes")

            tunnel.dropRelay() // ★ untrusted relay drop mid-request
            val second = secondRead.await()
            assertTrue(
                second.isFailure,
                "an untrusted relay drop RESETS the hub socket (RST), never a clean EOF — else a malicious relay " +
                    "truncates a response undetected. Got: $second",
            )
            bridgeJob.cancel()
        }
    }

    @Test
    fun realBridgeSocket_connectsToLoopback_only() = runBlocking {
        // T3 (wire): a real bridge socket's peer is a loopback address.
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        cleanups += { runCatching { server.close() } }
        launch(Dispatchers.IO) { runCatching { server.accept() } }
        val sock = RealBridgeSocket("127.0.0.1", server.localPort)
        cleanups += { sock.reset() }
        assertTrue(sock.remote.isLoopbackAddress, "the bridge connects to a loopback address, never a public one")
        assertEquals(true, sock.remote.isLoopbackAddress)
    }
}
