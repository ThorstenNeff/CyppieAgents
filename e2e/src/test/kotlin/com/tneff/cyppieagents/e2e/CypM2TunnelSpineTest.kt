package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.crypto.RawKeys
import com.tneff.cyppieagents.events.EventDraft
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.net.hub.BridgeConn
import com.tneff.cyppieagents.net.hub.ClientLoopbackBridge
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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-M2 (S-Tester) — the **E2E-over-tunnel** proof: a remote operator's ordinary hub operations are carried by the
 * REAL CR3 datapath (both loopback bridges + a REAL Noise_NK AEAD tunnel) to the REAL hub routes, and the bytes are
 * shown to actually traverse the tunnel (not a rerouted LOCAL path). No mocks on the transport: real
 * [NoiseJavaClientTransport] initiator ↔ real [NoiseJavaServerTerminator] responder over an in-memory relay (the
 * substitute for the live relay WS — the ONLY non-prod part), real [ClientLoopbackBridge]/[LoopbackBridge], real
 * [E2ePlatform] `installPlatform` routes.
 *
 * This is the **datapath (Tier A)** spine, faithful to Dev's Seam-1 production client-leg assembly (loopback
 * ServerSocket + `ClientLoopbackBridge.pump(tunnel,…)` + loopback base URL); Tier B swaps this test-owned client leg
 * for Dev's `RemoteHubTransport` actual (same tunnel injected via the session), reusing these assertions verbatim.
 *
 * RR3 tunnel-admission is an ORTHOGONAL upstream gate, separately proven (CYP-459 Rr3GateTest / CYP-490 + the
 * CYP-427 live E2E); this spine begins from an authorized tunnel and proves the OPERATOR-OP datapath post-grant.
 * Per the CR3 spec (§5, no mux) one connection == one tunnel, so each op opens its own tunnel.
 *
 * Teeth (each ★ reds independently under the mutation in `test/CYP-M2-...-plan.md` §9):
 *  - ★ REST op carried over the tunnel + LOCAL differential (anti-vacuity) + in-tunnel credential re-verify (401).
 *  - ★ project-switch (a real operator MUTATION) over the tunnel changes server state, read back over the tunnel.
 *  - ★ a live Comm event streams over the tunnel via the REAL `/ws/events` WebSocket route (WS, not just REST).
 *  - ★ a relay drop mid-op ABORTS the in-flight request — never a truncated-clean success (in-flight-uncertain).
 *
 * NB (Tier B): the session-state `RemoteSessionState.conn==RECONNECTING && inFlightUncertain==true` is a property of
 * Dev's `RemoteHubSession` (unit-covered by Cyp443RemoteHubSessionTest); this Tier-A tooth proves the DATAPATH-level
 * honesty consequence (a drop never yields a false clean response) that the session flag surfaces to the UI.
 */
class CypM2TunnelSpineTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val http = HttpClient(CIO)
    private val wsHttp = HttpClient(CIO) { install(WebSockets) }
    private val openTunnels = mutableListOf<M2Tunnel>()

    @AfterTest fun tearDown() {
        openTunnels.asReversed().forEach { runCatching { it.close() } }
        runCatching { http.close() }
        runCatching { wsHttp.close() }
        scope.cancel()
    }

    private fun platform(vararg extra: SeedProject) = e2ePlatform(
        listOf(SeedProject("cyp-m2", agents = listOf(SeedAgent("po", Role.PO), SeedAgent("backend", Role.WORKER)))) + extra,
    )

    @Test
    fun restOp_isCarriedOverTheTunnel_notLocal_andCredentialIsReVerifiedInside() = runBlocking {
        platform().use { platform ->
            val agentBearer = E2ePlatform.agentToken("backend")

            // (a) ★ the op over the TUNNEL → real route 200 AND the request+response bytes traversed the AEAD tunnel.
            // Keep-alive (NOT Connection: close): the Content-Length-framed response completes without a tunnel close,
            // so the truncation-guard RST never races (and discards) the happy-path body. Each op uses a fresh tunnel.
            val t1 = openTunnel(platform)
            val base1 = t1.recorder.snapshot()
            val viaTunnel = http.get("${t1.clientBaseUrl}/api/agents") { header(HttpHeaders.Authorization, "Bearer $agentBearer") }
            val bodyT = viaTunnel.bodyAsText()
            assertEquals(200, viaTunnel.status.value, "the operator op is served by the REAL route over the tunnel")
            assertTrue("backend" in bodyT, "the REAL roster comes back through the tunnel (non-vacuous body)")
            val d1 = t1.recorder.snapshot()
            assertTrue(d1.framesToHub > base1.framesToHub, "the op's REQUEST bytes traversed the tunnel (client→hub frames grew)")
            assertTrue(d1.framesFromHub > base1.framesFromHub, "the op's RESPONSE bytes traversed the tunnel (hub→client frames grew)")

            // (b) ★ differential vs LOCAL (anti-vacuity): the SAME op driven directly at the hub port leaves a fresh
            //     tunnel's recorder at ZERO delta — the frame-counter is PATH-sensitive, not always-on. A shortcut that
            //     silently routed the "tunnel" op locally would show zero tunnel frames in (a) and RED this test.
            val t2 = openTunnel(platform)
            val base2 = t2.recorder.snapshot()
            val local = http.get("${platform.baseUrl}/api/agents") { header(HttpHeaders.Authorization, "Bearer $agentBearer") }
            assertEquals(200, local.status.value, "the LOCAL control op is itself served (non-vacuous differential)")
            local.bodyAsText()
            assertEquals(base2, t2.recorder.snapshot(), "a LOCAL op adds ZERO tunnel frames to an unrelated tunnel")

            // (c) ★ T6 credential re-verified INSIDE the tunnel (RR5 carve-out): no bearer over the SAME tunnel path
            //     → the real route 401s. The loopback origin grants ZERO implicit trust; RR3 is never laundered in.
            val t3 = openTunnel(platform)
            val noCred = http.get("${t3.clientBaseUrl}/api/agents")
            assertEquals(401, noCred.status.value, "a no-bearer request over the tunnel is rejected by the real route (zero loopback trust)")
        }
    }

    @Test
    fun projectSwitch_overTunnel_isAppliedAndReadBack() = runBlocking {
        platform(SeedProject("proj-b", agents = listOf(SeedAgent("po2", Role.PO)))).use { platform ->
            val op = E2ePlatform.OPERATOR_TOKEN

            // ★ a real operator MUTATION over the tunnel: POST /api/projects/switch flips the server-side active pointer.
            val ts = openTunnel(platform)
            val switch = http.post("${ts.clientBaseUrl}/api/projects/switch") {
                header(HttpHeaders.Authorization, "Bearer $op"); contentType(ContentType.Application.Json)
                setBody("""{"projectId":"proj-b"}""")
            }
            assertEquals(200, switch.status.value, "the switch mutation is served over the tunnel")
            val ds = ts.recorder.snapshot()
            assertTrue(ds.framesToHub > 0 && ds.framesFromHub > 0, "the switch POST + response traversed the tunnel")

            // read the active pointer back over a FRESH tunnel — the mutation is durable server state, seen over the tunnel.
            val tr = openTunnel(platform)
            val base = tr.recorder.snapshot()
            val view = http.get("${tr.clientBaseUrl}/api/projects") { header(HttpHeaders.Authorization, "Bearer $op") }
            val body = view.bodyAsText()
            assertEquals(200, view.status.value)
            assertTrue("\"activeProjectId\":\"proj-b\"" in body.replace(" ", ""),
                "the switch applied over the tunnel is reflected in the active pointer read over the tunnel: $body")
            val d = tr.recorder.snapshot()
            assertTrue(d.framesToHub > base.framesToHub && d.framesFromHub > base.framesFromHub, "the read-back traversed the tunnel")
        }
    }

    @Test
    fun commEvent_streamsOverTunnel_viaRealEventsWebSocket() = runBlocking {
        platform().use { platform ->
            val op = E2ePlatform.OPERATOR_TOKEN
            val needle = "m2-ws-probe-7f3a"
            val tws = openTunnel(platform)
            val wsUrl = "${tws.clientBaseUrl.replace("http://", "ws://")}/ws/events?token=$op"

            // ★ a live Comm event streams over the tunnel via the REAL /ws/events WS route (proves WS, not just REST,
            //   traverses the tunnel). Seed the event AFTER the subscription is live; observe it come back through the tunnel.
            var seen = false
            withTimeoutOrNull(10_000) {
                wsHttp.webSocket(urlString = wsUrl) {
                    val seeder = launch {
                        delay(500) // let the live subscription establish (server announces CaughtUp first)
                        platform.booted.eventSink.append(
                            EventDraft(agentId = needle, projectId = "cyp-m2", type = EventType.LOG_DROPPED, severity = Severity.WARN, sourceTs = 0L),
                        )
                    }
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text && needle in frame.readText()) { seen = true; break }
                        }
                    } finally {
                        seeder.cancel()
                    }
                }
            }
            assertTrue(seen, "the seeded Comm event was delivered over the tunnel via the REAL /ws/events WebSocket route")
            val d = tws.recorder.snapshot()
            assertTrue(d.framesToHub > 0 && d.framesFromHub > 0, "the WS handshake + event frames traversed the tunnel")
        }
    }

    @Test
    fun relayDropMidOp_abortsInFlight_neverFalseCleanSuccess() = runBlocking {
        platform().use { platform ->
            val agentBearer = E2ePlatform.agentToken("backend")

            // Control: the SAME op over an intact tunnel is a clean 200 (so a failure below is caused by the DROP,
            // not by the harness always failing) — the non-vacuity partner of this tooth.
            val ok = openTunnel(platform)
            val okResp = http.get("${ok.clientBaseUrl}/api/agents") { header(HttpHeaders.Authorization, "Bearer $agentBearer") }
            okResp.bodyAsText()
            assertEquals(200, okResp.status.value, "control: an intact tunnel serves the op cleanly")

            // ★ a relay DROP (the hub→client path dies) mid-op → the in-flight request has NO response → the
            //   ClientLoopbackBridge truncation guard fires → the client op ABORTS (throws), never a truncated-clean 200.
            val dropped = openTunnel(platform)
            dropped.dropRelay() // kill the response path, as a relay would on a mid-action drop
            val outcome = runCatching {
                val r = http.get("${dropped.clientBaseUrl}/api/agents") { header(HttpHeaders.Authorization, "Bearer $agentBearer") }
                r.bodyAsText(); r.status.value
            }
            assertTrue(
                outcome.isFailure,
                "a relay drop mid-op ABORTS the in-flight request (in-flight-uncertain), never a false clean success: got ${outcome.getOrNull()}",
            )
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // The reusable spine (mirrors Dev's Seam-1 production client leg; only the relay is an in-memory substitute).
    // ------------------------------------------------------------------------------------------------------------

    /** One wired op-tunnel: a loopback [clientBaseUrl] whose bytes flow over a REAL Noise tunnel to the hub routes. */
    private inner class M2Tunnel(
        val clientBaseUrl: String,
        val recorder: RecordingRelay,
        private val serverSocket: ServerSocket,
        private val clientTunnel: NoiseTunnel,
        private val serverTunnel: ServerNoiseTunnel,
        private val hubEnd: ServerRelayChannel,
    ) : AutoCloseable {
        /** Simulate a relay drop: close the hub→client direction so the client's tunnel read ends (untrusted → RST). */
        fun dropRelay() { runCatching { runBlocking { hubEnd.close() } } }
        override fun close() {
            runCatching { serverSocket.close() }
            runCatching { runBlocking { clientTunnel.close() } }
            runCatching { runBlocking { serverTunnel.close() } }
            runCatching { runBlocking { hubEnd.close() } }
        }
    }

    /** Build a real client-leg + real Noise tunnel + a hub-side terminator bridged to [platform]'s real routes. */
    private suspend fun openTunnel(platform: E2ePlatform): M2Tunnel {
        val nettyPort = platform.baseUrl.substringAfterLast(':').toInt()
        val dh = RawKeys.generateX25519()
        val (clientRelay, hubEnd) = InMemDuplex.pair()
        val recorder = RecordingRelay(clientRelay)

        // Real hub responder over the relay → ServerNoiseTunnel; the initiator completes the NK handshake against it.
        val serverTunnelDeferred = scope.async { NoiseJavaServerTerminator(dh.privateRaw).terminate(hubEnd) }
        val clientTunnel = NoiseJavaClientTransport().connect(dh.publicRaw, recorder)
        val serverTunnel = serverTunnelDeferred.await()

        // Hub leg: pump the decrypted tunnel bytes to the REAL routes on the netty loopback listener.
        scope.launch { runCatching { LoopbackBridge(nettyPort).bridge(serverTunnel) } }

        // Client leg (== Dev's Seam-1 prod assembly): a loopback ServerSocket the workspace HttpClient dials; the
        // accepted connection is pumped ⇄ the client tunnel by the REAL ClientLoopbackBridge.
        val serverSocket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        scope.launch {
            runCatching {
                val sock = withContext(Dispatchers.IO) { serverSocket.accept() }
                ClientLoopbackBridge().pump(clientTunnel, SocketBridgeConn(sock))
            } // teardown closes the ServerSocket → accept throws; swallow (not a test failure)
        }
        return M2Tunnel("http://127.0.0.1:${serverSocket.localPort}", recorder, serverSocket, clientTunnel, serverTunnel, hubEnd)
            .also { openTunnels += it }
    }

    /** A recorded [RelayChannel] decorator: counts frames/bytes each direction so a test can prove op bytes flowed
     *  over the tunnel. Ciphertext is opaque — we COUNT, we never peek; correctness is proven by the op's own result. */
    private class RecordingRelay(private val inner: RelayChannel) : RelayChannel {
        private val fToHub = AtomicLong(); private val bToHub = AtomicLong()
        private val fFromHub = AtomicLong(); private val bFromHub = AtomicLong()
        override suspend fun send(frame: ByteArray) { fToHub.incrementAndGet(); bToHub.addAndGet(frame.size.toLong()); inner.send(frame) }
        override suspend fun receive(): ByteArray? =
            inner.receive()?.also { fFromHub.incrementAndGet(); bFromHub.addAndGet(it.size.toLong()) }
        override suspend fun close() = inner.close()
        fun snapshot() = Counts(fToHub.get(), bToHub.get(), fFromHub.get(), bFromHub.get())
    }

    data class Counts(val framesToHub: Long, val bytesToHub: Long, val framesFromHub: Long, val bytesFromHub: Long)

    /** In-memory duplex: a client [RelayChannel] end and a hub [ServerRelayChannel] end sharing two queues (ordered,
     *  reliable — the properties the real relay WS gives; closing a side → the peer's `receive()==null`). */
    private class InMemDuplex private constructor(
        private val outbound: Channel<ByteArray>,
        private val inbound: Channel<ByteArray>,
    ) : RelayChannel, ServerRelayChannel {
        override suspend fun send(frame: ByteArray) { outbound.send(frame.copyOf()) }
        override suspend fun receive(): ByteArray? = inbound.receiveCatching().getOrNull()
        override suspend fun close() { outbound.close() }
        companion object {
            /** (clientEnd: RelayChannel, hubEnd: ServerRelayChannel). */
            fun pair(): Pair<RelayChannel, ServerRelayChannel> {
                val c2h = Channel<ByteArray>(Channel.UNLIMITED)
                val h2c = Channel<ByteArray>(Channel.UNLIMITED)
                return InMemDuplex(outbound = c2h, inbound = h2c) to InMemDuplex(outbound = h2c, inbound = c2h)
            }
        }
    }

    /** [BridgeConn] over a real accepted loopback [Socket]; SO_LINGER 0 ⇒ [reset] emits a RST (the truncation guard). */
    private class SocketBridgeConn(private val socket: Socket) : BridgeConn {
        init { socket.setSoLinger(true, 0); socket.tcpNoDelay = true }
        private val input = socket.getInputStream()
        private val output = socket.getOutputStream()
        override suspend fun read(buf: ByteArray): Int = withContext(Dispatchers.IO) { input.read(buf) }
        override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) { output.write(bytes); output.flush() }
        override fun reset() { runCatching { if (!socket.isClosed) socket.close() } }
    }
}
