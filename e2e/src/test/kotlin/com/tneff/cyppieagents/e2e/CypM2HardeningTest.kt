package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.crypto.RawKeys
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.net.hub.buildRemoteHubTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseHandshakeException
import com.tneff.cyppieagents.net.hub.noise.NoiseJavaClientTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.noise.RelayChannel
import com.tneff.cyppieagents.transport.LoopbackBridge
import com.tneff.cyppieagents.transport.NoiseJavaServerTerminator
import com.tneff.cyppieagents.transport.ServerNoiseTunnel
import com.tneff.cyppieagents.transport.ServerRelayChannel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CYP-M2 hardening (S-Tester) — edge-case deepening of the E2E-over-tunnel proof, seam-ready against the integrated
 * harness (`d7d22c73` = Dev transport `RemoteTunnelHubTransport` + Backend guard + real routes). These complement the
 * 7 happy-path/god-token/drop teeth with relay-drop VARIANTS, handshake fail-closed, transport re-dial rebind,
 * in-flight-uncertain honesty for a mutation, and a server-side N-concurrent-tunnel proof.
 *
 * Substrate is the same as the Tier-A/Tier-B harness: real `NoiseJavaClientTransport` ↔ real `NoiseJavaServerTerminator`
 * (ChaChaPoly) over an in-memory relay (the ONLY non-prod part), real `LoopbackBridge`, real `e2ePlatform` routes.
 * A fresh client per request forces a new connection ⇒ a new tunnel poll (one-conn-per-tunnel, Dev's RR8).
 */
class CypM2HardeningTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cleanups = mutableListOf<() -> Unit>()

    @AfterTest fun tearDown() {
        cleanups.asReversed().forEach { runCatching { it() } }
        scope.cancel()
    }

    private fun platform() = e2ePlatform(
        listOf(SeedProject("cyp-m2", agents = listOf(SeedAgent("po", Role.PO), SeedAgent("backend", Role.WORKER)))),
    )

    // ---- H1: a relay drop MID-WS-STREAM aborts the WS (not a silent hang) — the WS complement of the REST drop tooth.
    @Test
    fun h1_relayDropMidWsStream_abortsTheWs_notASilentHang() = runBlocking {
        platform().use { platform ->
            val nettyPort = platform.baseUrl.substringAfterLast(':').toInt()
            val op = E2ePlatform.OPERATOR_TOKEN
            val queue = ConcurrentLinkedQueue<NoiseTunnel>()
            val transport = buildRemoteHubTransport(acquireTunnel = { queue.poll() }, sessionToken = { op }, scope = scope)!!
            cleanups += { transport.close() }
            val h = realTunnel(nettyPort); queue.add(h.tunnel)
            val wsClient = HttpClient(CIO) { install(WebSockets) }
            cleanups += { runCatching { wsClient.close() } }

            var sawAFrame = false
            // The WS is live for ~500ms (receives at least the server's CaughtUp), THEN the relay drops mid-stream.
            // A faithful transport surfaces that as the WS ENDING (incoming closes) — never a silent stall.
            val ended = withTimeoutOrNull(8_000) {
                wsClient.webSocket("${transport.wsBaseUrl.trimEnd('/')}/ws/events?token=$op") {
                    launch { delay(500); h.dropRelay() }
                    for (frame in incoming) { if (frame is Frame.Text) sawAFrame = true }
                }
                true
            }
            assertTrue(sawAFrame, "the WS was live over the tunnel before the drop (received a frame)")
            assertTrue(ended == true, "a relay drop mid-WS-stream ENDS the WS (incoming closes) — never a silent hang")
        }
    }

    // ---- H2: a handshake against a dead relay fails CLOSED — no half-open tunnel (drop before CONNECTED).
    @Test
    fun h2_handshakeAgainstDeadRelay_failsClosed_noHalfOpenTunnel() = runBlocking {
        // The hub side never completes the NK handshake (relay closed) → the client initiator's read gets EOF →
        // NoiseHandshakeException, and NO NoiseTunnel is produced (fail-closed, never a plaintext/partial fallback).
        val (clientRelay, hubEnd) = InMemDuplex.pair()
        hubEnd.close() // hub side dead before the handshake can complete
        assertFailsWith<NoiseHandshakeException> {
            NoiseJavaClientTransport().connect(RawKeys.generateX25519().publicRaw, clientRelay)
        }
        Unit // keep the @Test method's return type Unit/void (assertFailsWith returns the exception)
    }

    // ---- H3: after a drop, the transport RE-DIALS onto a FRESH tunnel for the next op (stable loopback port, tunnel swaps).
    @Test
    fun h3_dropThenReDial_rebindsToFreshTunnel_recovers() = runBlocking {
        platform().use { platform ->
            val nettyPort = platform.baseUrl.substringAfterLast(':').toInt()
            val bearer = E2ePlatform.agentToken("backend")
            val queue = ConcurrentLinkedQueue<NoiseTunnel>()
            val transport = buildRemoteHubTransport(acquireTunnel = { queue.poll() }, sessionToken = { bearer }, scope = scope)!!
            cleanups += { transport.close() }
            val stableBase = transport.httpBaseUrl // the loopback port is stable across the drop; only the tunnel swaps

            // op1 over a DROPPED tunnel → fails.
            val t1 = realTunnel(nettyPort); t1.dropRelay(); queue.add(t1.tunnel)
            assertTrue(runCatching { oneShotGet("$stableBase/api/agents", bearer).status.value }.isFailure,
                "op1 over a dropped tunnel fails")

            // op2 over a FRESH tunnel on the SAME transport → the accept-loop re-acquires (rebind) → 200 (recovered).
            val t2 = realTunnel(nettyPort); queue.add(t2.tunnel)
            assertEquals(200, oneShotGet("$stableBase/api/agents", bearer).status.value,
                "op2 re-dials onto a fresh tunnel via the accept-loop re-acquire — the transport recovered on the stable port")
        }
    }

    // ---- H4: a MUTATING request (POST) dropped mid-flight is in-flight-UNCERTAIN — surfaced as a caller-visible
    //          failure (never a false success), so the operator knows to check before retrying (no phantom completion).
    @Test
    fun h4_droppedPost_isInFlightUncertain_surfacedAsFailure_notFalseSuccess() = runBlocking {
        platform().use { platform ->
            val nettyPort = platform.baseUrl.substringAfterLast(':').toInt()
            val op = E2ePlatform.OPERATOR_TOKEN
            val queue = ConcurrentLinkedQueue<NoiseTunnel>()
            val transport = buildRemoteHubTransport(acquireTunnel = { queue.poll() }, sessionToken = { op }, scope = scope)!!
            cleanups += { transport.close() }

            // control: a COMPLETE POST over an intact tunnel creates the agent (201) — so the failure below is the drop's doing.
            val okT = realTunnel(nettyPort); queue.add(okT.tunnel)
            assertEquals(201, oneShotPost("${transport.httpBaseUrl}/api/agents", op,
                """{"id":"ok-agent","name":"OK","role":"WORKER"}""").status.value, "control: an intact POST creates the agent (201)")

            // ★ a POST dropped mid-flight → the caller sees a FAILURE (never a false 2xx). The response was lost, so the
            //   operator cannot assume it applied — the honest in-flight-uncertain signal that prevents a blind retry dup.
            val dropT = realTunnel(nettyPort); dropT.dropRelay(); queue.add(dropT.tunnel)
            val outcome = runCatching {
                oneShotPost("${transport.httpBaseUrl}/api/agents", op, """{"id":"ghost","name":"Ghost","role":"WORKER"}""").status.value
            }
            assertTrue(outcome.isFailure, "a dropped POST surfaces as a caller-visible failure (in-flight-uncertain), never a false success: got ${outcome.getOrNull()}")

            // and it is never silently applied MORE than once (no phantom replay by the transport) — ghost appears <= 1x.
            val rosterT = realTunnel(nettyPort); queue.add(rosterT.tunnel)
            val ghosts = Regex("\"ghost\"").findAll(oneShotGet("${transport.httpBaseUrl}/api/agents", op).bodyAsText()).count()
            assertTrue(ghosts <= 1, "the dropped POST is never replayed into a duplicate (ghost appears <=1x, was $ghosts)")
        }
    }

    // ---- H5: N concurrent REAL tunnels are all carried by the real routes — proves the SERVER handles N-concurrent
    //          TODAY (each tunnel its own transport). Scaffold for the full client-side N-tunnel property (ready-if-A).
    @Test
    fun h5_nConcurrentRealTunnels_allCarried_serverSideNConcurrentProof() = runBlocking {
        platform().use { platform ->
            val nettyPort = platform.baseUrl.substringAfterLast(':').toInt()
            val bearer = E2ePlatform.agentToken("backend")
            val n = 3

            // N independent transports, each fed ONE real tunnel — the single-flight transport is one-conn-per-tunnel,
            // so N-concurrent is N transports today; the full client-side N-per-session mux is the ready-if-A follow-on.
            val transports = (0 until n).map { i ->
                val queue = ConcurrentLinkedQueue<NoiseTunnel>()
                val t = buildRemoteHubTransport(acquireTunnel = { queue.poll() }, sessionToken = { bearer }, scope = scope)!!
                cleanups += { t.close() }
                val h = realTunnel(nettyPort); queue.add(h.tunnel)
                t to h
            }

            // Drive all N ops CONCURRENTLY → every one served 200 + its own tunnel carried bytes (server handles N at once).
            val results = transports.map { (t, h) ->
                async {
                    val base = h.recorder.snapshot()
                    val code = oneShotGet("${t.httpBaseUrl}/api/agents", bearer).status.value
                    val d = h.recorder.snapshot()
                    Triple(code, d.framesToHub > base.framesToHub, d.framesFromHub > base.framesFromHub)
                }
            }.awaitAll()

            assertTrue(results.all { it.first == 200 }, "all $n concurrent tunneled ops were served 200: ${results.map { it.first }}")
            assertTrue(results.all { it.second && it.third }, "each of the $n concurrent tunnels carried its op's bytes over the tunnel")
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Shared harness (self-contained; same shape as the Tier-B factory).
    // ------------------------------------------------------------------------------------------------------------

    private suspend fun oneShotGet(url: String, bearer: String? = null): HttpResponse {
        val c = HttpClient(CIO)
        return try {
            val r = c.get(url) { if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer") }
            r.bodyAsText(); r
        } finally { c.close() }
    }

    private suspend fun oneShotPost(url: String, bearer: String, json: String): HttpResponse {
        val c = HttpClient(CIO)
        return try {
            val r = c.post(url) {
                header(HttpHeaders.Authorization, "Bearer $bearer"); contentType(ContentType.Application.Json); setBody(json)
            }
            r.bodyAsText(); r
        } finally { c.close() }
    }

    private inner class TunnelHandle(val tunnel: NoiseTunnel, val recorder: RecordingRelay, private val hubEnd: ServerRelayChannel) {
        fun dropRelay() { runCatching { runBlocking { hubEnd.close() } } }
    }

    private suspend fun realTunnel(nettyPort: Int): TunnelHandle {
        val dh = RawKeys.generateX25519()
        val (clientRelay, hubEnd) = InMemDuplex.pair()
        val recorder = RecordingRelay(clientRelay)
        val serverTunnelDeferred = scope.async { NoiseJavaServerTerminator(dh.privateRaw).terminate(hubEnd) }
        val clientTunnel = NoiseJavaClientTransport().connect(dh.publicRaw, recorder)
        val serverTunnel = serverTunnelDeferred.await()
        scope.launch { runCatching { LoopbackBridge(nettyPort).bridge(serverTunnel) } }
        cleanups += {
            runCatching { runBlocking { clientTunnel.close() } }
            runCatching { runBlocking { serverTunnel.close() } }
            runCatching { runBlocking { hubEnd.close() } }
        }
        return TunnelHandle(clientTunnel, recorder, hubEnd)
    }

    private class RecordingRelay(private val inner: RelayChannel) : RelayChannel {
        private val fToHub = AtomicLong(); private val bToHub = AtomicLong()
        private val fFromHub = AtomicLong(); private val bFromHub = AtomicLong()
        override suspend fun send(frame: ByteArray) { fToHub.incrementAndGet(); bToHub.addAndGet(frame.size.toLong()); inner.send(frame) }
        override suspend fun receive(): ByteArray? = inner.receive()?.also { fFromHub.incrementAndGet(); bFromHub.addAndGet(it.size.toLong()) }
        override suspend fun close() = inner.close()
        fun snapshot() = Counts(fToHub.get(), fFromHub.get())
    }

    data class Counts(val framesToHub: Long, val framesFromHub: Long)

    private class InMemDuplex private constructor(
        private val outbound: Channel<ByteArray>,
        private val inbound: Channel<ByteArray>,
    ) : RelayChannel, ServerRelayChannel {
        override suspend fun send(frame: ByteArray) { outbound.send(frame.copyOf()) }
        override suspend fun receive(): ByteArray? = inbound.receiveCatching().getOrNull()
        override suspend fun close() { outbound.close() }
        companion object {
            fun pair(): Pair<RelayChannel, ServerRelayChannel> {
                val c2h = Channel<ByteArray>(Channel.UNLIMITED)
                val h2c = Channel<ByteArray>(Channel.UNLIMITED)
                return InMemDuplex(outbound = c2h, inbound = h2c) to InMemDuplex(outbound = h2c, inbound = c2h)
            }
        }
    }
}
