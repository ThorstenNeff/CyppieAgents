package com.tneff.cyppieagents.e2e

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
import com.tneff.cyppieagents.crypto.RawKeys
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.net.hub.buildRemoteHubTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseJavaClientTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.noise.RelayChannel
import com.tneff.cyppieagents.routing.installPlatform
import com.tneff.cyppieagents.routing.installTunnelGodTokenGuard
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
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
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
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-M2 Tier B (S-Tester) — the E2E-over-tunnel proof driving **Dev's REAL production transport**
 * `RemoteTunnelHubTransport` (via `buildRemoteHubTransport`, Seam-3 `965e845a`) against the REAL hub over a REAL Noise
 * tunnel — now landed on the **tunnel-scoped connector** so **Backend's real `installTunnelGodTokenGuard`** (`41353f91`,
 * CYP-534 port-supplier form) is in the path. The ONLY non-prod part is the in-memory relay substituting the live relay WS.
 *
 * Two Netty connectors on one booted platform (Backend's `Cyp427TunnelScopedListenerTest` pattern): a **public** port
 * and a guarded **tunnel** port. The harness routes every over-tunnel op's hub-side `LoopbackBridge` at the **tunnel**
 * port — where tunnel traffic really lands in prod — so the god-token guard actually sees it. Dev's own transport test
 * proves the transport at the unit level (FakeTunnel); this is the real-crypto + real-routes + real-guard E2E complement.
 *
 * `currentTunnel: () -> NoiseTunnel?` is non-suspend (reads `RemoteHubSession.tunnel` in prod) → the harness
 * pre-establishes real tunnels (the suspend NK handshake) and hands them out via a queue, one connection per tunnel
 * (single-flight, no mux — Dev's RR8). A fresh client per request forces a new connection ⇒ a new tunnel poll.
 *
 * Teeth (each non-vacuous — both keystone mutations confirmed RED in a probe run):
 *  - ★ a REST op is CARRIED over the tunnel by Dev's transport onto the tunnel connector (200 + real roster + tunnel
 *    frames grew) + LOCAL differential (an unrelated tunnel stays at ZERO frames) + no-bearer → 401.
 *  - ★ a relay drop mid-op → Dev's transport fails the in-flight request CLOSED, never a truncated-clean 200.
 *  - ★ the static operator (**god**) token over the tunnel is refused on BOTH auth channels by Backend's real guard:
 *    Bearer (REST) → 401, `?token=` (WS `/ws/events`) → no event served (pre-upgrade 401). Non-vacuous counters: an
 *    AGENT token over the tunnel → 200 (only the god token is refused), and the SAME god token over the PUBLIC
 *    connector → 200 (the guard is port-scoped, not global).
 */
class CypM2TierBTransportTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cleanups = mutableListOf<() -> Unit>()

    @AfterTest fun tearDown() {
        cleanups.asReversed().forEach { runCatching { it() } }
        scope.cancel()
    }

    private class FakeProcess : AgentProcess {
        override val stdoutLines = kotlinx.coroutines.flow.emptyFlow<String>()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }
    private class FakeSpawner : ProcessSpawner {
        override fun spawn(command: List<String>, cwd: File, env: Map<String, String>): AgentProcess = FakeProcess()
    }
    /** Faked git: exit 0, creates clone/worktree dirs; rev-parse "absent" so add uses -b. */
    private class FakeGit : CommandRunner {
        override fun run(command: List<String>, cwd: File): CommandResult {
            when {
                command.getOrNull(1) == "clone" -> File(command.last(), ".git").mkdirs()
                command.getOrNull(1) == "rev-parse" -> return CommandResult(1, "")
                command.getOrNull(1) == "worktree" && command.getOrNull(2) == "add" -> {
                    val target = if (command.getOrNull(3) == "-b") command.getOrNull(5) else command.getOrNull(3)
                    target?.let { File(it).mkdirs() }
                }
            }
            return CommandResult(0, "")
        }
    }

    private data class TwoConn(val publicPort: Int, val tunnelPort: Int, val agentToken: String, val godToken: String, val booted: BootedPlatform)

    /** Boot the REAL platform once, exposed on TWO loopback connectors; the tunnel connector carries the god-token guard. */
    private fun startTwoConnectorPlatform(): TwoConn {
        val agentToken = "tok-backend"
        val godToken = "tok-op" // the static MachineOperator token — the "god token" the guard refuses over the tunnel
        val booted = BootOrchestrator(
            PlatformConfig(
                RepoConfig("git@github.com:org/repo.git", "main"),
                agents = listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("backend", "BE", Role.WORKER)),
            ),
            Secrets(mapOf(agentToken to "backend"), operatorToken = godToken, apiKey = null),
            WorktreeManager(FakeGit(), Files.createTempDirectory("cyp-m2-tierb").toFile()),
            FakeSpawner(),
            scope,
        ).boot()
        // CYP-534: BOTH connectors ephemeral (port=0); the guard reads the tunnel port via a supplier RESOLVED AFTER
        // the bind — no ServerSocket(0)-close→re-bind TOCTOU. resolvedConnectors() preserves declaration order:
        // [0]=public, [1]=tunnel. Requests only arrive after the holder is set (mirrors Backend's Cyp427 test).
        val tunnelPortHolder = AtomicInteger(-1)
        val server = embeddedServer(
            Netty,
            serverConfig {
                module {
                    installPlatform(booted)
                    installTunnelGodTokenGuard({ tunnelPortHolder.get() }, booted.tokenRegistry::isOperator)
                }
            },
        ) {
            connector { port = 0; host = "127.0.0.1" } // [0] public
            connector { port = 0; host = "127.0.0.1" } // [1] tunnel-scoped (guarded)
        }.start(wait = false)
        cleanups += { server.stop(0, 0) }
        val conns = runBlocking { server.engine.resolvedConnectors() }
        val publicPort = conns[0].port
        val tunnelPort = conns[1].port
        tunnelPortHolder.set(tunnelPort)
        return TwoConn(publicPort, tunnelPort, agentToken, godToken, booted)
    }

    /** One request over its own fresh connection → forces a new tunnel poll (one-conn-per-tunnel, no keep-alive reuse). */
    private suspend fun oneShotGet(url: String, bearer: String? = null): HttpResponse {
        val c = HttpClient(CIO)
        return try {
            val r = c.get(url) { if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer") }
            r.bodyAsText()
            r
        } finally { c.close() }
    }

    @Test
    fun tierB_realTransport_carriesOp_withLocalDifferential_andInTunnelCredential() = runBlocking {
        val p = startTwoConnectorPlatform()
        val queue = ConcurrentLinkedQueue<NoiseTunnel>()
        val transport = buildRemoteHubTransport(currentTunnel = { queue.poll() }, sessionToken = { p.agentToken }, scope = scope)!!
        cleanups += { transport.close() }
        assertTrue(transport.httpBaseUrl.startsWith("http://127.0.0.1:"), "Dev's transport exposes a loopback base, not the hub host")

        // (a) ★ carried over Dev's transport onto the TUNNEL connector → real route 200 + real roster + bytes over the tunnel.
        val h1 = realTunnel(p.tunnelPort); queue.add(h1.tunnel)
        val base1 = h1.recorder.snapshot()
        val r = oneShotGet("${transport.httpBaseUrl}/api/agents", p.agentToken)
        assertEquals(200, r.status.value, "Dev's RemoteTunnelHubTransport carried the op to the real route (tunnel connector)")
        assertTrue("backend" in r.bodyAsText(), "the real roster came back through Dev's transport over the tunnel")
        val d1 = h1.recorder.snapshot()
        assertTrue(d1.framesToHub > base1.framesToHub && d1.framesFromHub > base1.framesFromHub, "the op's bytes traversed the Noise tunnel")

        // (b) ★ LOCAL differential (anti-vacuity): an un-polled tunnel's recorder stays at ZERO after a LOCAL op.
        val h2 = realTunnel(p.tunnelPort) // deliberately NOT enqueued
        val base2 = h2.recorder.snapshot()
        assertEquals(200, oneShotGet("http://127.0.0.1:${p.publicPort}/api/agents", p.agentToken).status.value, "LOCAL control served")
        assertEquals(base2, h2.recorder.snapshot(), "a LOCAL op adds ZERO frames to an unrelated tunnel")

        // (c) ★ credential re-verified INSIDE the tunnel: no-bearer over Dev's transport → 401.
        val h3 = realTunnel(p.tunnelPort); queue.add(h3.tunnel)
        assertEquals(401, oneShotGet("${transport.httpBaseUrl}/api/agents").status.value, "no-bearer over the tunnel is 401")
    }

    @Test
    fun tierB_realTransport_relayDropMidOp_failsClosed_neverFalseCleanSuccess() = runBlocking {
        val p = startTwoConnectorPlatform()
        val queue = ConcurrentLinkedQueue<NoiseTunnel>()
        val transport = buildRemoteHubTransport(currentTunnel = { queue.poll() }, sessionToken = { p.agentToken }, scope = scope)!!
        cleanups += { transport.close() }

        val ok = realTunnel(p.tunnelPort); queue.add(ok.tunnel)
        assertEquals(200, oneShotGet("${transport.httpBaseUrl}/api/agents", p.agentToken).status.value, "control: intact tunnel serves 200")

        // ★ a relay DROP mid-op → Dev's ClientLoopbackBridge RSTs → the in-flight request ABORTS, never a truncated-clean 200.
        val dropped = realTunnel(p.tunnelPort); dropped.dropRelay(); queue.add(dropped.tunnel)
        val outcome = runCatching { oneShotGet("${transport.httpBaseUrl}/api/agents", p.agentToken).status.value }
        assertTrue(outcome.isFailure, "a relay drop mid-op fails the request CLOSED through Dev's transport (got ${outcome.getOrNull()})")
    }

    @Test
    fun tierB_godTokenOverTunnel_bothAuthChannels_refusedByRealGuard() = runBlocking {
        val p = startTwoConnectorPlatform()
        val queue = ConcurrentLinkedQueue<NoiseTunnel>()
        val transport = buildRemoteHubTransport(currentTunnel = { queue.poll() }, sessionToken = { p.godToken }, scope = scope)!!
        cleanups += { transport.close() }

        // ★ (1) BEARER channel: the static god token over the tunnel → Backend's guard refuses it 401.
        val h1 = realTunnel(p.tunnelPort); queue.add(h1.tunnel)
        assertEquals(401, oneShotGet("${transport.httpBaseUrl}/api/agents", p.godToken).status.value,
            "the static operator (god) token as a Bearer over the tunnel is refused 401 by the real guard")

        // ★ (2) `?token=` QUERY channel (WS): the god token over the WS path → refused (pre-upgrade 401, no event served)
        //     — a bearer-only guard would leak this WS auth axis (the god-token-via-query bypass).
        val h2 = realTunnel(p.tunnelPort); queue.add(h2.tunnel)
        val wsClient = HttpClient(CIO) { install(WebSockets) }
        cleanups += { runCatching { wsClient.close() } }
        var served = false
        withTimeoutOrNull(6_000) {
            runCatching {
                wsClient.webSocket("${transport.wsBaseUrl}/ws/events?token=${p.godToken}") {
                    for (frame in incoming) { if (frame is Frame.Text) { served = true; break } }
                }
            }
        }
        assertTrue(!served, "the god token via ?token= over the WS path is refused (pre-upgrade 401) — no event served, no query bypass")

        // ★ (3) non-vacuity counter A: an AGENT (read-tier) token over the SAME tunnel connector → 200 (only the god token is refused).
        val h3 = realTunnel(p.tunnelPort); queue.add(h3.tunnel)
        assertEquals(200, oneShotGet("${transport.httpBaseUrl}/api/agents", p.agentToken).status.value,
            "an agent token over the tunnel is accepted (200) — the guard refuses ONLY the god token")

        // ★ (4) non-vacuity counter B: the SAME god token over the PUBLIC connector → 200 (the guard is port-scoped, not global).
        val h4 = realTunnel(p.publicPort); queue.add(h4.tunnel)
        assertEquals(200, oneShotGet("${transport.httpBaseUrl}/api/agents", p.godToken).status.value,
            "the SAME god token over the PUBLIC connector is served 200 — the tunnel guard is port-scoped")
    }

    // ------------------------------------------------------------------------------------------------------------
    // Real-tunnel factory: a fresh Noise tunnel (real crypto) over an in-memory relay, wired to [port] (tunnel or public).
    // (Dev's transport owns the client acceptor + bridge; the harness supplies only the tunnel + the hub leg.)
    // ------------------------------------------------------------------------------------------------------------

    private inner class TunnelHandle(
        val tunnel: NoiseTunnel,
        val recorder: RecordingRelay,
        private val hubEnd: ServerRelayChannel,
    ) {
        fun dropRelay() { runCatching { runBlocking { hubEnd.close() } } }
    }

    private suspend fun realTunnel(port: Int): TunnelHandle {
        val dh = RawKeys.generateX25519()
        val (clientRelay, hubEnd) = InMemDuplex.pair()
        val recorder = RecordingRelay(clientRelay)
        val serverTunnelDeferred = scope.async { NoiseJavaServerTerminator(dh.privateRaw).terminate(hubEnd) }
        val clientTunnel = NoiseJavaClientTransport().connect(dh.publicRaw, recorder)
        val serverTunnel = serverTunnelDeferred.await()
        scope.launch { runCatching { LoopbackBridge(port).bridge(serverTunnel) } }
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
