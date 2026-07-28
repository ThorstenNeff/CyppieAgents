package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.CpJwtVerifier
import com.tneff.cyppieagents.auth.TokenPredicates
import com.tneff.cyppieagents.auth.operator.DeviceKeyAlg
import com.tneff.cyppieagents.auth.operator.EnrolledOperatorDevice
import com.tneff.cyppieagents.auth.operator.InMemoryOperatorDeviceStore
import com.tneff.cyppieagents.auth.operator.OperatorAssertionVerifier
import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.BootedPlatform
import com.tneff.cyppieagents.boot.CommandResult
import com.tneff.cyppieagents.boot.CommandRunner
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.controlplane.CpJwtMinter
import com.tneff.cyppieagents.crypto.RawKeys
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.net.hub.buildRemoteHubTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseJavaClientTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.noise.RelayChannel
import com.tneff.cyppieagents.net.hub.operator.DevicePoP
import com.tneff.cyppieagents.net.hub.operator.OperatorDeviceKeyStore
import com.tneff.cyppieagents.net.hub.operator.PerTunnelPopOutcome
import com.tneff.cyppieagents.net.hub.operator.PopResult
import com.tneff.cyppieagents.net.hub.operator.UserVerification
import com.tneff.cyppieagents.net.hub.operator.UvCachingPerTunnelPoPProvider
import com.tneff.cyppieagents.net.hub.operator.UvFailReason
import com.tneff.cyppieagents.net.hub.operator.UvOutcome
import com.tneff.cyppieagents.net.hub.operator.UvReason
import com.tneff.cyppieagents.net.hub.relay.KtorWsRelayConnector
import com.tneff.cyppieagents.operator.OperatorPoPWire
import com.tneff.cyppieagents.operator.TunnelAuthGrant
import com.tneff.cyppieagents.operator.TunnelAuthRequest
import com.tneff.cyppieagents.operator.operatorAuthChallenge
import com.tneff.cyppieagents.relay.RelayPeer
import com.tneff.cyppieagents.relay.RelayRole
import com.tneff.cyppieagents.relay.RendezvousRelay
import com.tneff.cyppieagents.relay.relayModule
import com.tneff.cyppieagents.routing.installPlatform
import com.tneff.cyppieagents.routing.installTunnelGodTokenGuard
import com.tneff.cyppieagents.transport.LoopbackBridge
import com.tneff.cyppieagents.transport.NoiseJavaServerTerminator
import com.tneff.cyppieagents.transport.Rr3AuthenticatedTunnelHandler
import com.tneff.cyppieagents.transport.Rr3Config
import com.tneff.cyppieagents.transport.Rr3TunnelGate
import com.tneff.cyppieagents.transport.ServerNoiseTunnel
import com.tneff.cyppieagents.transport.TunnelSessionRegistry
import com.tneff.cyppieagents.transport.WebSocketRelayDialer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-549 (M2 Option A) — the **live-relay loopback e2e**: the joint N-tunnel datapath (CYP-536 credential path)
 * proven over a **REAL** Ktor relay WS socket on `127.0.0.1` (`relayModule()`), NOT the in-memory `InMemDuplex` all
 * prior M2 e2e used. Loopback sockets are real sockets (real TCP/WS stack, real framing, real async I/O, real
 * rendezvous pairing) — so this closes the last unproven datapath point **without a staging deploy** (WAN/TLS/NAT
 * stay separate + deploy-gated).
 *
 * Teeth (Assist design):
 *  - **T1** a real Noise-NK tunnel pairs over the real relay → RR3 authenticates (real CpJwt + PoP over the live `h`)
 *    → `GET /api/agents` = **200 + real roster** over the real path.
 *  - **T2** the static operator (god) token over the tunnel connector → **401** (the guard holds over real frames).
 *  - **T3** **2 concurrent tunnels** on 2 distinct rendezvous-ids over the real relay, both authenticate + carry requests.
 *  - **T4** anti-replay over the real path: tunnel-K's PoP replayed onto tunnel-J → `bad_signature`; a reused nonce on
 *    the shared gate → `nonce_replayed`.
 *  - **★ T5 (keystone / anti-vacuity)** `spyRelay.frames(rzv) > 0` — the relay ACTUALLY relayed frames (the in-memory
 *    path would be 0). Without T5 the whole test can pass vacuously without ever touching the real relay.
 *
 * **CYP-549 harden (Assist re-gate):** the HTTP legs (T1/T2/T3) use the **production client path** (`buildRemoteHubTransport`
 * + a Ktor `HttpClient` GET) which assembles the full HTTP response robustly until close — replacing a hand-rolled tunnel
 * reader that raced the `Connection: close` (an empty-response flake) AND closing a fidelity gap (the test now exercises
 * the real prod read path). Belt-and-suspenders: the responder signals **bridge-readiness** (a `CompletableDeferred`
 * completed immediately before `bridge()` pumps) and the test **awaits it before the GET** — the H6 "await-a-stable-state,
 * never race timing" pattern — eliminating the send-before-bridge race at the root. The spy uses the **real `relayModule`**
 * via its `peerDecorator` seam (no hand-copied handler → no CYP-546 copy-drift).
 */
class Cyp536LiveRelayLoopbackE2eTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cleanups = mutableListOf<() -> Unit>()
    @AfterTest fun tearDown() { cleanups.asReversed().forEach { runCatching { it() } }; scope.cancel() }

    // ── T5: the REAL relayModule (via its peerDecorator seam) with a frame-counting RelayPeer decorator ──
    private class CountingRelayPeer(private val inner: RelayPeer, private val counter: ConcurrentHashMap<String, AtomicInteger>) : RelayPeer {
        override val rendezvousId: String get() = inner.rendezvousId
        override val role: RelayRole get() = inner.role
        override suspend fun receive(): ByteArray? =
            inner.receive()?.also { counter.getOrPut(rendezvousId) { AtomicInteger() }.incrementAndGet() }
        override suspend fun send(frame: ByteArray) = inner.send(frame)
        override suspend fun close() = inner.close()
    }

    private inner class SpyRelay {
        private val relay = RendezvousRelay()
        private val counter = ConcurrentHashMap<String, AtomicInteger>()
        fun frames(rendezvousId: String): Int = counter[rendezvousId]?.get() ?: 0
        /** Installs the REAL relayModule; the peerDecorator seam wraps each peer to count relayed frames (no copy). */
        fun install(app: Application) = app.relayModule(relay, peerDecorator = { CountingRelayPeer(it, counter) })
    }

    private fun startRelay(): Pair<SpyRelay, String> {
        val spy = SpyRelay()
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { spy.install(this) }.start(wait = false)
        cleanups += { server.stop(0, 0) }
        val port = runBlocking { server.engine.resolvedConnectors() }.first().port
        return spy to "ws://127.0.0.1:$port/relay"
    }

    // ── server + client crypto fixtures (mirrored from Cyp536JointNTunnelAuthE2eTest) ──
    private val hubId = "hub_live"
    private val operatorId = "op-live"
    private val issuer = "cp-issuer"
    private val kid = "kid1"
    private val gateNowMs = 1_782_517_200_000L
    private val cp = RawKeys.generateEd25519()
    private val device = RawKeys.generateEd25519()
    private val minter = CpJwtMinter(cp.privateRaw, kid, issuer)
    private val deviceStore = InMemoryOperatorDeviceStore().apply {
        save(EnrolledOperatorDevice("dev-live", DeviceKeyAlg.ED25519, device.publicRaw))
    }
    private fun rr3Config() = Rr3Config(
        hubId = hubId, pinnedOperatorId = operatorId, expectedIssuer = issuer,
        cpPublicKey = { k -> if (k == kid) cp.publicRaw else null }, expectedRpId = "hub.example",
    )
    private fun newGate() = Rr3TunnelGate(CpJwtVerifier(), OperatorAssertionVerifier(), deviceStore, rr3Config(), now = { gateNowMs })
    private fun cpJwtFor(h: ByteArray) =
        minter.mint(hubId, operatorId, TokenPredicates.expectedChannelBinding(h, hubId), gateNowMs, ttlMs = 300_000)

    private class CountingUv(private val outcome: UvOutcome) : UserVerification {
        val prompts = AtomicInteger()
        override suspend fun verify(reason: UvReason): UvOutcome { prompts.incrementAndGet(); return outcome }
    }
    private class TestDeviceKeyStore(private val priv: ByteArray, private val pub: ByteArray, private val uv: UserVerification) : OperatorDeviceKeyStore {
        override fun isEnrolled() = true
        override fun devicePublicKey() = pub
        override suspend fun sign(challenge: ByteArray): PopResult = when (val o = uv.verify(UvReason.OPERATOR_AUTH)) {
            UvOutcome.Verified -> PopResult.Signed(DevicePoP.Raw(RawKeys.ed25519Sign(priv, challenge)))
            is UvOutcome.Denied -> PopResult.UvFailed(o.reason)
            UvOutcome.Unavailable -> PopResult.UvFailed(UvFailReason.LOCKED_OUT)
        }
    }
    private fun provider(rawUv: CountingUv) = UvCachingPerTunnelPoPProvider.wrap(
        rawUv = rawUv, reuseWindowMs = 300_000L, nowMs = { 1_000L },
        storeFor = { uv -> TestDeviceKeyStore(device.privateRaw, device.publicRaw, uv) },
    )

    // ── a live Noise tunnel over the REAL relay (client leg role=client, hub leg role=hub, paired by relayModule) ──
    private class RealTunnelPair(val client: NoiseTunnel, val server: ServerNoiseTunnel)

    private suspend fun liveTunnel(relayUrl: String, rzv: String): RealTunnelPair {
        val dh = RawKeys.generateX25519()
        val hubHttp = HttpClient(CIO) { install(ClientWebSockets) }; cleanups += { hubHttp.close() }
        val clientHttp = HttpClient(CIO) { install(ClientWebSockets) }; cleanups += { clientHttp.close() }
        val hubLeg = WebSocketRelayDialer(hubHttp) { rzv }.dial(relayUrl)          // ServerRelayChannel (role=hub)
        val serverDeferred = scope.async { NoiseJavaServerTerminator(dh.privateRaw).terminate(hubLeg) }
        val clientLeg: RelayChannel = KtorWsRelayConnector(clientHttp).open(relayUrl, rzv) // role=client
        val client = NoiseJavaClientTransport().connect(dh.publicRaw, clientLeg)
        val server = serverDeferred.await()
        cleanups += { runCatching { runBlocking { client.close(); server.close() } } }
        return RealTunnelPair(client, server)
    }

    // ── RR3 auth wire (WS2 assembles the challenge + request, WS3 pure-signs) ──
    private suspend fun buildAuthRequest(provider: UvCachingPerTunnelPoPProvider, h: ByteArray, nonce: ByteArray): ByteArray {
        val sig = when (val pop = provider.popFor("rvid", operatorAuthChallenge(h, hubId, nonce))) {
            is PerTunnelPopOutcome.Ready -> pop.pop
            else -> error("client produced a non-Ready PoP: $pop")
        }
        return CommJson.encodeToString(TunnelAuthRequest(cpJwtFor(h), OperatorPoPWire.Raw(sig), nonce)).encodeToByteArray()
    }
    private fun nonce(vararg b: Byte) = b
    private suspend fun readGrant(client: NoiseTunnel): TunnelAuthGrant? =
        client.receive()?.let { CommJson.decodeFromString<TunnelAuthGrant>(it.decodeToString()) }
    /** RR3-authenticate over the real relay: client sends the request, reads the grant. Returns granted. */
    private suspend fun authenticate(provider: UvCachingPerTunnelPoPProvider, pair: RealTunnelPair, nonce: ByteArray): Boolean {
        pair.client.send(buildAuthRequest(provider, pair.client.handshakeHash, nonce))
        return readGrant(pair.client)?.granted == true
    }

    // ── the REAL hub platform on two connectors ([0]=public, [1]=tunnel with the god-token guard) ──
    private class FakeProcess : AgentProcess {
        override val stdoutLines = kotlinx.coroutines.flow.emptyFlow<String>()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }
    private class FakeSpawner : ProcessSpawner {
        override fun spawn(command: List<String>, cwd: File, env: Map<String, String>): AgentProcess = FakeProcess()
    }
    private class FakeGit : CommandRunner {
        override fun run(command: List<String>, cwd: File): CommandResult {
            when {
                command.getOrNull(1) == "clone" -> File(command.last(), ".git").mkdirs()
                command.getOrNull(1) == "rev-parse" -> return CommandResult(1, "")
                command.getOrNull(1) == "worktree" && command.getOrNull(2) == "add" ->
                    (if (command.getOrNull(3) == "-b") command.getOrNull(5) else command.getOrNull(3))?.let { File(it).mkdirs() }
            }
            return CommandResult(0, "")
        }
    }
    private data class Platform(val tunnelPort: Int, val agentToken: String, val godToken: String, val booted: BootedPlatform)

    private fun startPlatform(): Platform {
        val agentToken = "tok-backend"; val godToken = "tok-op"
        val booted = BootOrchestrator(
            PlatformConfig(RepoConfig("git@github.com:org/repo.git", "main"),
                agents = listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("backend", "BE", Role.WORKER))),
            Secrets(mapOf(agentToken to "backend"), operatorToken = godToken, apiKey = null),
            WorktreeManager(FakeGit(), Files.createTempDirectory("cyp549-live").toFile()), FakeSpawner(), scope,
        ).boot()
        val tunnelPortHolder = AtomicInteger(-1)
        val server = embeddedServer(Netty, serverConfig {
            module {
                installPlatform(booted)
                installTunnelGodTokenGuard({ tunnelPortHolder.get() }, booted.tokenRegistry::isOperator)
            }
        }) {
            connector { port = 0; host = "127.0.0.1" } // [0] public
            connector { port = 0; host = "127.0.0.1" } // [1] tunnel-scoped (guarded)
        }.start(wait = false)
        cleanups += { server.stop(0, 0) }
        val conns = runBlocking { server.engine.resolvedConnectors() }
        val tunnelPort = conns[1].port
        tunnelPortHolder.set(tunnelPort)
        return Platform(tunnelPort, agentToken, godToken, booted)
    }

    /** The production hub responder: RR3-gate the tunnel, then bridge its bytes to the tunnel connector. Returns a
     *  bridge-readiness signal completed immediately before the bridge starts pumping (Assist fix (a) — awaited by the
     *  test before any HTTP GET, so the request can never race a not-yet-pumping bridge). */
    private fun launchResponder(gate: Rr3TunnelGate, platform: Platform, server: ServerNoiseTunnel): CompletableDeferred<Unit> {
        val bridgeReady = CompletableDeferred<Unit>()
        val realBridge = LoopbackBridge(platform.tunnelPort)
        val handler = Rr3AuthenticatedTunnelHandler(
            authorize = gate::authorizeIdentified, // CYP-882a: bind the session to the authenticated per-tunnel operatorId
            bridge = { t -> bridgeReady.complete(Unit); realBridge.bridge(t) },
            registry = TunnelSessionRegistry(), sessionTtlMs = 600_000L,
        )
        scope.launch { runCatching { handler.handle(server) } }
        return bridgeReady
    }

    /** One HTTP GET over the RR3-authed real-relay tunnel via the PRODUCTION client transport (robust response
     *  assembly until close). Awaits bridge-readiness first so the request never races the responder's bridge start. */
    private suspend fun getOverAuthedTunnel(client: NoiseTunnel, bridgeReady: CompletableDeferred<Unit>, token: String): HttpResponse {
        withTimeout(20_000) { bridgeReady.await() } // ★ (a) await bridge-readiness before the GET (no send-before-bridge race)
        val queue = ConcurrentLinkedQueue<NoiseTunnel>().apply { add(client) }
        val transport = buildRemoteHubTransport(acquireTunnel = { queue.poll() }, sessionToken = { token }, scope = scope)!!
        cleanups += { transport.close() }
        val http = HttpClient(CIO); cleanups += { http.close() }
        val resp = http.get("${transport.httpBaseUrl}/api/agents") { header(HttpHeaders.Authorization, "Bearer $token") }
        resp.bodyAsText() // buffer the body (suspend) so it can be re-read by the caller after return
        return resp
    }

    @Test
    fun t1_realTunnelOverRealRelay_rr3Auth_thenGet200Roster_t5FramesRelayed() = runBlocking {
        val (spy, relayUrl) = startRelay()
        val platform = startPlatform()
        val rzv = "rzv-t1"
        val pair = liveTunnel(relayUrl, rzv)
        val ready = launchResponder(newGate(), platform, pair.server)

        assertTrue(withTimeout(20_000) { authenticate(provider(CountingUv(UvOutcome.Verified)), pair, nonce(1, 1)) },
            "T1: RR3 authenticates over the real relay (real CpJwt + PoP over the live h)")
        val resp = getOverAuthedTunnel(pair.client, ready, platform.agentToken)
        assertEquals(200, resp.status.value, "T1: GET /api/agents = 200 over the real relay path")
        assertTrue("backend" in resp.bodyAsText(), "T1: the real roster came back through the real relay tunnel")
        // ★ T5 (keystone): the real relay actually forwarded frames — an in-memory shortcut would be 0.
        assertTrue(spy.frames(rzv) > 0, "★ T5: frames really traversed the REAL relay for $rzv (was ${spy.frames(rzv)})")
    }

    @Test
    fun t2_godTokenOverRealRelayTunnel_rejected401() = runBlocking {
        val (_, relayUrl) = startRelay()
        val platform = startPlatform()
        val pair = liveTunnel(relayUrl, "rzv-t2")
        val ready = launchResponder(newGate(), platform, pair.server)
        assertTrue(withTimeout(20_000) { authenticate(provider(CountingUv(UvOutcome.Verified)), pair, nonce(2)) }, "authed")

        // ★ the static operator (god) token as the workspace bearer over the real-relay tunnel → the guard 401s it.
        val resp = getOverAuthedTunnel(pair.client, ready, platform.godToken)
        assertEquals(401, resp.status.value, "T2: the god token over the real-relay tunnel connector is refused 401")
    }

    @Test
    fun t3_twoConcurrentTunnels_twoRendezvousIds_overRealRelay() = runBlocking {
        val (spy, relayUrl) = startRelay()
        val platform = startPlatform()
        val gate = newGate() // ONE gate/ledger across both tunnels
        val prov = provider(CountingUv(UvOutcome.Verified))
        val a = liveTunnel(relayUrl, "rzv-a"); val readyA = launchResponder(gate, platform, a.server)
        val b = liveTunnel(relayUrl, "rzv-b"); val readyB = launchResponder(gate, platform, b.server)

        assertTrue(withTimeout(20_000) { authenticate(prov, a, nonce(3, 1)) }, "tunnel A authenticates over rzv-a")
        assertTrue(withTimeout(20_000) { authenticate(prov, b, nonce(3, 2)) }, "tunnel B authenticates over rzv-b")
        assertEquals(200, getOverAuthedTunnel(a.client, readyA, platform.agentToken).status.value, "A carries a request over rzv-a")
        assertEquals(200, getOverAuthedTunnel(b.client, readyB, platform.agentToken).status.value, "B carries a request over rzv-b")
        assertTrue(spy.frames("rzv-a") > 0 && spy.frames("rzv-b") > 0, "T5×2: both distinct rendezvous-ids relayed frames")
    }

    @Test
    fun t4_antiReplay_overRealRelay_badSignature_and_nonceReplayed() = runBlocking {
        val (spy, relayUrl) = startRelay()
        val prov = provider(CountingUv(UvOutcome.Verified))
        val gate = newGate() // ONE gate ⇒ one nonce ledger

        // control: tunnel K authenticates with its own PoP over its own live h.
        val k = liveTunnel(relayUrl, "rzv-k")
        val serverK = scope.async { gate.authorize(k.server) }
        val nonceK = nonce(4, 4, 1)
        k.client.send(buildAuthRequest(prov, k.client.handshakeHash, nonceK))
        assertTrue(withTimeout(20_000) { serverK.await() }, "control: tunnel K's own PoP authorizes over the real relay")

        // ★ (a) cross-tunnel h replay: tunnel-K's request bytes replayed onto tunnel-J (a different live h_J) → bad_signature.
        val reqK = buildAuthRequest(prov, k.client.handshakeHash, nonce(4, 4, 9))
        val j = liveTunnel(relayUrl, "rzv-j")
        assertTrue(j.server.handshakeHash.toList() != k.server.handshakeHash.toList(), "precondition: J's live h differs from K's")
        val serverJ = scope.async { newGate().authorize(j.server) }
        j.client.send(reqK)
        assertFalse(withTimeout(20_000) { serverJ.await() }, "★ T4a: a PoP built for K is refused on J (bad_signature over the real relay)")

        // ★ (b) nonce replay on the shared gate → nonce_replayed.
        val freshGate = newGate()
        val a1 = liveTunnel(relayUrl, "rzv-r1")
        val sA = scope.async { freshGate.authorize(a1.server) }
        a1.client.send(buildAuthRequest(prov, a1.client.handshakeHash, nonceK))
        assertTrue(withTimeout(20_000) { sA.await() }, "first use of the nonce authorizes")
        val a2 = liveTunnel(relayUrl, "rzv-r2")
        val sB = scope.async { freshGate.authorize(a2.server) }
        a2.client.send(buildAuthRequest(prov, a2.client.handshakeHash, nonceK))
        assertFalse(withTimeout(20_000) { sB.await() }, "★ T4b: the reused nonce on the shared gate is refused (nonce_replayed)")

        assertTrue(spy.frames("rzv-k") > 0, "T5: the anti-replay exchange really traversed the real relay")
    }
}
