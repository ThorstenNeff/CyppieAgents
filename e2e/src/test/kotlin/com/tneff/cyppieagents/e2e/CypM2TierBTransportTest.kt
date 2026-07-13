package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.crypto.RawKeys
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.net.hub.buildRemoteHubTransport
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
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-M2 Tier B (S-Tester) — the same E2E-over-tunnel proof, now driving **Dev's REAL production transport**
 * `RemoteTunnelHubTransport` via the `buildRemoteHubTransport` factory (Seam-3, `184cd92a`), NOT the test-only
 * client-leg assembly. Dev owns the loopback acceptor + `ClientLoopbackBridge`; the harness supplies the ONLY
 * non-prod part — a **real** Noise tunnel over an in-memory relay (real `NoiseJavaClientTransport` ↔ real
 * `NoiseJavaServerTerminator`, real `LoopbackBridge` to the real `installPlatform` routes).
 *
 * Dev's own `RemoteTunnelHubTransportTest` proves the transport at the UNIT level (a `FakeTunnel` with canned bytes);
 * this is the E2E complement — real crypto tunnel + real routes end-to-end, so "the load-bearing proof that
 * `RemoteTunnelHubTransport` really carries" is shown against the actual hub, not a fake.
 *
 * `currentTunnel: () -> NoiseTunnel?` is non-suspend (it reads `RemoteHubSession.tunnel` in prod), so the harness
 * pre-establishes real tunnels (the suspend NK handshake) and hands them out via a queue — one connection per tunnel
 * (single-flight, no mux — Dev's RR8 design). A fresh client per request forces a new connection ⇒ a new tunnel poll.
 *
 * Teeth (each reused from the accepted Tier-A datapath proof `d8658270`, now through Dev's class):
 *  - ★ a REST op is CARRIED over the tunnel by Dev's transport (200 + real roster + tunnel frames grew) + the LOCAL
 *    differential (an unrelated tunnel stays at ZERO frames — the counter is path-sensitive) + the route re-verifies
 *    the credential INSIDE the tunnel (no-bearer → 401).
 *  - ★ a relay drop mid-op → Dev's transport fails the in-flight request CLOSED (aborts), never a truncated-clean 200.
 *  - ☐ seam-ready: a non-enrolled ("god") token presented over the tunnel is fail-closed (rejected). Tightens to a
 *    specific 403 once Backend's tunnel-scoped listener lands (the single TIGHTEN line below).
 */
class CypM2TierBTransportTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cleanups = mutableListOf<() -> Unit>()

    @AfterTest fun tearDown() {
        cleanups.asReversed().forEach { runCatching { it() } }
        scope.cancel()
    }

    private fun platform() = e2ePlatform(
        listOf(SeedProject("cyp-m2", agents = listOf(SeedAgent("po", Role.PO), SeedAgent("backend", Role.WORKER)))),
    )

    /** One request over its own fresh connection → forces a new tunnel poll (one-conn-per-tunnel, no keep-alive reuse). */
    private suspend fun oneShotGet(url: String, bearer: String? = null): HttpResponse {
        val c = HttpClient(CIO)
        return try {
            val r = c.get(url) { if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer") }
            r.bodyAsText() // drain the body before the connection is torn down
            r
        } finally { c.close() }
    }

    @Test
    fun tierB_realTransport_carriesOp_withLocalDifferential_andInTunnelCredential() = runBlocking {
        platform().use { platform ->
            val nettyPort = platform.baseUrl.substringAfterLast(':').toInt()
            val bearer = E2ePlatform.agentToken("backend")
            val queue = ConcurrentLinkedQueue<NoiseTunnel>()
            val transport = buildRemoteHubTransport(currentTunnel = { queue.poll() }, sessionToken = { bearer }, scope = scope)!!
            cleanups += { transport.close() }
            assertTrue(transport.httpBaseUrl.startsWith("http://127.0.0.1:"), "Dev's transport exposes a loopback base, not the hub host")

            // (a) ★ carried over Dev's transport → real route 200 + real roster + the bytes traversed the tunnel.
            val h1 = realTunnel(nettyPort); queue.add(h1.tunnel)
            val base1 = h1.recorder.snapshot()
            val r = oneShotGet("${transport.httpBaseUrl}/api/agents", bearer)
            assertEquals(200, r.status.value, "Dev's RemoteTunnelHubTransport carried the op to the real route")
            assertTrue("backend" in r.bodyAsText(), "the real roster came back through Dev's transport over the tunnel")
            val d1 = h1.recorder.snapshot()
            assertTrue(d1.framesToHub > base1.framesToHub && d1.framesFromHub > base1.framesFromHub,
                "the op's bytes traversed the Noise tunnel (frames grew both directions)")

            // (b) ★ LOCAL differential (anti-vacuity): an unrelated, un-polled tunnel's recorder stays at ZERO after a
            //     LOCAL op — the counter is path-sensitive; a shortcut routing the op off-tunnel would RED (a).
            val h2 = realTunnel(nettyPort) // deliberately NOT enqueued → Dev's transport never carries over it
            val base2 = h2.recorder.snapshot()
            val local = oneShotGet("${platform.baseUrl}/api/agents", bearer)
            assertEquals(200, local.status.value, "the LOCAL control op is itself served (non-vacuous differential)")
            assertEquals(base2, h2.recorder.snapshot(), "a LOCAL op adds ZERO frames to an unrelated tunnel")

            // (c) ★ credential re-verified INSIDE the tunnel: no-bearer over Dev's transport → the real route 401s.
            val h3 = realTunnel(nettyPort); queue.add(h3.tunnel)
            val noCred = oneShotGet("${transport.httpBaseUrl}/api/agents")
            assertEquals(401, noCred.status.value, "a no-bearer request over Dev's transport is rejected by the real route")
        }
    }

    @Test
    fun tierB_realTransport_relayDropMidOp_failsClosed_neverFalseCleanSuccess() = runBlocking {
        platform().use { platform ->
            val nettyPort = platform.baseUrl.substringAfterLast(':').toInt()
            val bearer = E2ePlatform.agentToken("backend")
            val queue = ConcurrentLinkedQueue<NoiseTunnel>()
            val transport = buildRemoteHubTransport(currentTunnel = { queue.poll() }, sessionToken = { bearer }, scope = scope)!!
            cleanups += { transport.close() }

            // control: an intact tunnel serves cleanly through Dev's transport (so the failure below is the drop's doing).
            val ok = realTunnel(nettyPort); queue.add(ok.tunnel)
            assertEquals(200, oneShotGet("${transport.httpBaseUrl}/api/agents", bearer).status.value, "control: intact tunnel serves 200")

            // ★ a relay DROP (hub→client path dies) → Dev's ClientLoopbackBridge RSTs the loopback socket → the
            //   in-flight request ABORTS, never a truncated-clean 200 (fail-closed = in-flight-uncertain honesty).
            val dropped = realTunnel(nettyPort); dropped.dropRelay(); queue.add(dropped.tunnel)
            val outcome = runCatching { oneShotGet("${transport.httpBaseUrl}/api/agents", bearer).status.value }
            assertTrue(outcome.isFailure, "a relay drop mid-op fails the request CLOSED through Dev's transport (got ${outcome.getOrNull()})")
        }
    }

    @Test
    fun tierB_godTokenOverTunnel_bothAuthChannels_failClosed_seamReadyFor403() = runBlocking {
        platform().use { platform ->
            val nettyPort = platform.baseUrl.substringAfterLast(':').toInt()
            val queue = ConcurrentLinkedQueue<NoiseTunnel>()
            // A broad/unenrolled "god" token — the machine-wide authority that MUST NOT be honored over the operator
            // tunnel. Presented as the session identity Dev's transport carries.
            val godToken = "god-machine-operator-token-should-be-tunnel-rejected"
            val transport = buildRemoteHubTransport(currentTunnel = { queue.poll() }, sessionToken = { godToken }, scope = scope)!!
            cleanups += { transport.close() }

            // ★ (1) BEARER-header channel (REST): god token over the tunnel → the real route rejects it.
            val h1 = realTunnel(nettyPort); queue.add(h1.tunnel)
            val rest = oneShotGet("${transport.httpBaseUrl}/api/agents", godToken)
            assertTrue(rest.status.value in setOf(401, 403),
                "god token as Bearer over the tunnel is fail-closed (rejected), never served: ${rest.status.value}")
            // TIGHTEN (Backend tunnel-scoped interceptor): assertEquals(401, rest.status.value)

            // ★ (2) `?token=` QUERY channel (WS path): god token over the tunnel must ALSO be rejected — a Bearer-only
            //     reject would leak this WS auth path (the god-token-via-query bypass = a real hole). Fail-closed =
            //     the WS is closed with NO event/Text frame ever served over the tunnel.
            val h2 = realTunnel(nettyPort); queue.add(h2.tunnel)
            val wsClient = HttpClient(CIO) { install(WebSockets) }
            cleanups += { runCatching { wsClient.close() } }
            var served = false
            withTimeoutOrNull(6_000) {
                runCatching {
                    wsClient.webSocket("${transport.wsBaseUrl}/ws/events?token=$godToken") {
                        for (frame in incoming) { if (frame is Frame.Text) { served = true; break } } // any Text = wrongly served
                    }
                }
            }
            assertTrue(!served, "god token as ?token= on the WS path over the tunnel is fail-closed — no event served (no query bypass)")
            // TIGHTEN (Backend tunnel-scoped interceptor): assert the specific pre-upgrade 401 on the WS handshake.
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Real-tunnel factory: a fresh Noise tunnel (real crypto) over an in-memory relay, wired to the real routes.
    // (Dev's transport owns the client acceptor + bridge; the harness supplies only the tunnel + the hub leg.)
    // ------------------------------------------------------------------------------------------------------------

    private inner class TunnelHandle(
        val tunnel: NoiseTunnel,
        val recorder: RecordingRelay,
        private val hubEnd: ServerRelayChannel,
        private val serverTunnel: ServerNoiseTunnel,
    ) {
        /** Simulate a relay drop: close the hub→client direction so the client's tunnel read ends (untrusted → RST). */
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
        return TunnelHandle(clientTunnel, recorder, hubEnd, serverTunnel)
    }

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
