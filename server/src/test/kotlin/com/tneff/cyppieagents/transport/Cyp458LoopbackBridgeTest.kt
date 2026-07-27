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
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
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
        @Volatile private var faultOnDrain = false                  // CYP-609: drained end THROWS instead of null
        override val handshakeHash: ByteArray get() = ByteArray(32)
        override suspend fun receive(): ByteArray? {
            val r = inbound.receiveCatching()
            // CYP-609: an ABRUPT L2 fault (AEAD-decrypt-fail / tamper) surfaces as receive() THROWING, distinct from a
            // clean channel EOF (null). The bridge RSTs the former, FINs the latter.
            if (r.isClosed && faultOnDrain) throw RuntimeException("AEAD decrypt-fail / tamper (abrupt L2 fault)")
            return r.getOrNull()
        }
        override suspend fun send(plaintext: ByteArray) { outbound.trySend(plaintext) }
        override suspend fun close() { inbound.close(); outbound.close() }
        fun deliver(bytes: ByteArray) { inbound.trySend(bytes) }
        /** The UNTRUSTED relay/L2 closing CLEANLY — `receive()` then returns null (the CYP-609 graceful-FIN trigger). */
        fun dropRelay() { inbound.close() }
        /** The UNTRUSTED relay/L2 faulting ABRUPTLY — `receive()` then THROWS (the CYP-609 RST / truncation-guard trigger). */
        fun throwRelay() { faultOnDrain = true; inbound.close() }
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
        val registry = TokenRegistry(mapOf("valid-token" to "backend"), operatorToken = null, loopbackPosture = true)
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
        val registry = TokenRegistry(mapOf("valid-token" to "backend"), operatorToken = null, loopbackPosture = true)
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

    /** Drive a partial request through the bridge, apply [fault] (a clean relay drop OR an abrupt relay throw), and
     *  return what the raw hub socket's SECOND read sees: a graceful FIN → success(-1); an RST → failure (throws). */
    private fun observeHubSecondReadAfterUpstreamFault(fault: (ControllableTunnel) -> Unit): Result<Int> = runBlocking {
        withTimeout(15_000) {
            // A raw loopback "hub" that reads what the bridge sends, then reads again after the upstream fault.
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
                secondRead.complete(runCatching { input.read(buf) }) // after the fault: RST → throws; graceful FIN → -1
            }

            val tunnel = ControllableTunnel()
            val bridgeJob = launch(Dispatchers.IO) { LoopbackBridge(port).bridge(tunnel) }
            tunnel.deliver("GET /slow HTTP/1.1\r\nHost: x\r\n".encodeToByteArray()) // a partial request (no terminator)
            assertTrue(partialSeen.await() > 0, "the hub received the in-flight request bytes")

            fault(tunnel) // ★ the upstream (untrusted relay) ends mid-request — cleanly (drop) or abruptly (throw)
            val second = secondRead.await()
            bridgeJob.cancel()
            second
        }
    }

    @Test
    fun ac2a_cleanRelayEof_gracefulFin_hubSeesCleanEof_notReset_CYP609() {
        // CYP-609: a CLEAN upstream EOF (`receive()==null` — the relay/client closed cleanly) → the bridge FIN-half-closes
        // the loopback (`shutdownOutput`), so the hub's Ktor sees a clean input-EOF (-1), NOT a "Connection reset". The
        // truncation guard is NOT lost — Ktor's own HTTP layer rejects a truncated body (Content-Length short / missing
        // terminal chunk → non-2xx), which the CYP-459-T2 real-route teeth pin (Assist-verified sign-off).
        // MUTATION-RED: revert `closeGraceful()`→`reset()` on the clean path ⇒ the hub's 2nd read throws ⇒ this REDs.
        val second = observeHubSecondReadAfterUpstreamFault { it.dropRelay() }
        assertTrue(
            second.isSuccess && second.getOrNull() == -1,
            "a CLEAN relay EOF → graceful FIN → the hub reads -1 (clean EOF), never an RST. Got: $second",
        )
    }

    @Test
    fun ac2b_abruptRelayFault_resetsHubSocket_RST_truncationGuardPreserved_CYP609() {
        // CYP-609 C2: an ABRUPT upstream fault (`receive()` THREW — AEAD-decrypt-fail / tamper-truncation) MUST still RST
        // the loopback (SO_LINGER 0) — in-flight-uncertain, hard-abort so a malicious/lossy relay can NEVER have a
        // tampered/truncated stream completed as clean (§4/AC2 preserved).
        // MUTATION-RED: collapse the throw path to `closeGraceful()` too ⇒ the hub reads -1 instead of throwing ⇒ this REDs.
        val second = observeHubSecondReadAfterUpstreamFault { it.throwRelay() }
        assertTrue(
            second.isFailure,
            "an ABRUPT relay fault (receive threw) RESETS the hub socket (RST) — the 2nd read throws. Got: $second",
        )
    }

    @Test
    fun cyp546_downstream_isBounded_synchronousInline_noReadAheadBuffer() = runBlocking {
        // CYP-546 — refactor-guard for the server downstream bound. The pump is a SYNCHRONOUS inline read→tunnel.send
        // (no Channel buffer), so with the tunnel-send BLOCKED it can hold at most ONE CHUNK in flight (the one stuck
        // in send) — it reads the next chunk only after the prior send returns. A refactor that inserted a read-ahead
        // buffer (unbounded, or even a ≤8 window like the CLIENT bridge) would let the reader drain the endless socket
        // ahead of the blocked sender → a 2nd read happens. This pins that the server bridge is bounded by its inline
        // structure (~1 CHUNK), NOT a mirrored ≤8 Channel (the CYP-546 doc-drift), and that a future refactor can't
        // silently un-bound it. The timeout is a bounded wait to observe read-ahead — the synchronous pump structurally
        // can never read ahead of a blocked send, so this can only fail if a buffer was introduced (never flaky).
        val reads = AtomicInteger()
        val readAheadDetected = kotlinx.coroutines.CompletableDeferred<Unit>()
        val endlessSocket = object : BridgeSocket { // an inexhaustible downstream source (every read yields a full CHUNK)
            override val remote: InetAddress = InetAddress.getLoopbackAddress()
            override suspend fun write(bytes: ByteArray) {} // upstream is idle in this test
            override suspend fun read(buf: ByteArray): Int {
                if (reads.incrementAndGet() >= 2) readAheadDetected.complete(Unit) // a 2nd read ⇒ the pump read ahead
                return buf.size
            }
            override fun reset() {}
            override fun closeGraceful() {} // CYP-609
        }
        val senderBlockedTunnel = object : ServerNoiseTunnel {
            override val handshakeHash: ByteArray = ByteArray(32)
            override suspend fun receive(): ByteArray? = kotlinx.coroutines.CompletableDeferred<ByteArray?>().await() // upstream idle → no RST
            override suspend fun send(plaintext: ByteArray) { kotlinx.coroutines.CompletableDeferred<Unit>().await() } // tunnel-send blocks forever
            override suspend fun close() {}
        }
        val job = launch(Dispatchers.IO) {
            runCatching { LoopbackBridge(9, "127.0.0.1") { _, _ -> endlessSocket }.bridge(senderBlockedTunnel) }
        }
        val readAhead = withTimeoutOrNull(3_000) { readAheadDetected.await() } // null ⇒ never read ahead (bounded)
        job.cancel()
        assertTrue(
            readAhead == null && reads.get() <= 1,
            "the server downstream keeps ≤1 CHUNK in flight (synchronous inline read→send) — a buffering refactor " +
                "would read ahead of the blocked sender (reads=${reads.get()})",
        )
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
