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
import com.tneff.cyppieagents.net.hub.RemoteTunnelHubTransport
import com.tneff.cyppieagents.net.hub.TunnelSource
import com.tneff.cyppieagents.net.hub.buildRemoteHubTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseJavaClientTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.noise.RelayChannel
import com.tneff.cyppieagents.routing.installPlatform
import com.tneff.cyppieagents.routing.installTunnelGodTokenGuard
import com.tneff.cyppieagents.transport.LoopbackBridge
import com.tneff.cyppieagents.transport.NoiseJavaServerTerminator
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-539 (WS4) — the **N-tunnel E2E harness**: extends the single-flight Tier-B proof
 * ([CypM2TierBTransportTest]) to **N concurrent Noise tunnels**, the M2 Option-A datapath that fixes F-M2-1
 * (one tunnel = one duplex stream → the ~8 eager persistent WS deadlock on a single tunnel). Design + frozen
 * contracts: `docs/design/M2-A-ntunnel-workstream-split-and-contract.md` (§2, §4 C2/C3, §5).
 *
 * **Contract-first (§5.1).** On develop `7358c853` the transport is still Phase-1 single-flight and the WS2
 * internals are NOT landed (0 hits for the N-pooling `TunnelSource`, `TunnelPoolState`, `poolCap`,
 * `BACKPRESSURED`). So this harness builds against the **frozen §4 shapes with test-local fakes** — the C2
 * [FakePoolingTunnelSource] and the C3 [TunnelPoolState] fixture + [FakePoolState] emitter — kept entirely in
 * `:e2e` test scope. It does NOT define/touch any `app/shared` production type (WS2's lane); when WS2 lands the
 * real pooling source + real emitter, these fixtures swap 1:1 against the same frozen shapes.
 *
 * What is REAL here (not faked): the crypto (real Noise NK tunnels), the routes (the real booted platform), and
 * the guard (Backend's real `installTunnelGodTokenGuard`, tunnel-scoped). The only substitution is the in-memory
 * relay for the live relay WS — identical to Tier-B.
 *
 * Teeth (each non-vacuous — the keystone mutation that would make it pass falsely is named in each, and was
 * confirmed RED in a probe run):
 *  - ★ §C2/N-datapath: N tunnels carry N ops CONCURRENTLY (all 200 + real roster), each tunnel's own bytes grew
 *    (distinctness), and an unrelated witness tunnel stays at ZERO frames (no cross-leak). Converge N=2 → scale
 *    to `poolCap`. Keystone RED: share ONE tunnel across ops → the per-tunnel-grew set collapses (only one grows).
 *  - ★ §C1/security: the static operator (god) token is refused **per tunnel** on BOTH auth channels (Bearer 401,
 *    `?token=` WS no-event) on EVERY one of N tunnels; an agent token on each → 200; god on the PUBLIC connector →
 *    200 (port-scoped). Keystone RED: drop the per-tunnel guard → god accepted on some tunnel.
 *  - ★ §C2/pool-cap fail-closed: `acquire()` hands out exactly `cap` DISTINCT live tunnels then `null`; wired into
 *    the real transport, the first `cap` connections are carried (200) and the (cap+1)th fails CLOSED (RST, no
 *    local fallback — the `noLiveTunnel_failsClosed` invariant). Keystone RED: cap-bypass (a tunnel past cap) OR
 *    null-fallback (a local 200 instead of RST) → the (cap+1)th would 200.
 *  - ★ §C3/H7 observable: the per-tunnel [TunnelPoolState] stream surfaces DIALING→UP, a saturated tunnel as
 *    BACKPRESSURED, a dropped tunnel as DOWN, and the `aggregate {active, cap, anyBackpressured}` tracks it.
 *    SCOPE-HONEST: the real per-tunnel *pump* bound is CYP-535/H7 (Team-1, unlanded — the bridge pump uses
 *    UNLIMITED channels today), so this asserts the frozen C3 **observable contract**; it graduates to the real
 *    bound when H7 lands. Keystone RED: an emitter that never flips BACKPRESSURED → `anyBackpressured` stays false.
 */
class CypM2NTunnelHarnessTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cleanups = mutableListOf<() -> Unit>()

    /** WS2 owns the real single-sourced const (≈10–15, C2). Test-local until WS2 lands it; then swap. */
    private val harnessPoolCap = 12

    @AfterTest fun tearDown() {
        cleanups.asReversed().forEach { runCatching { it() } }
        scope.cancel()
    }

    // ============================================================================================================
    // C3 fixture — the FROZEN per-tunnel status shape (contract doc §4 C3). Test-local; WS2's real emitter must
    // satisfy exactly this shape. enum {DIALING,UP,BACKPRESSURED,DOWN} + {rendezvousId,state,sinceTs} + aggregate.
    // ============================================================================================================
    enum class TunnelState { DIALING, UP, BACKPRESSURED, DOWN }
    data class TunnelStatus(val rendezvousId: String, val state: TunnelState, val sinceTs: Long)
    data class PoolAggregate(val active: Int, val cap: Int, val anyBackpressured: Boolean)
    data class TunnelPoolState(val tunnels: List<TunnelStatus>, val aggregate: PoolAggregate)

    /** A minimal fake C3 emitter the harness drives over tunnel lifecycle; asserts on the frozen shape. */
    private class FakePoolState(private val cap: Int) {
        private val byId = LinkedHashMap<String, TunnelStatus>()
        private var clock = 0L
        @Synchronized fun mark(rendezvousId: String, state: TunnelState) {
            byId[rendezvousId] = TunnelStatus(rendezvousId, state, sinceTs = ++clock)
        }
        @Synchronized fun snapshot(): TunnelPoolState {
            val tunnels = byId.values.toList()
            val active = tunnels.count { it.state == TunnelState.UP || it.state == TunnelState.BACKPRESSURED }
            return TunnelPoolState(tunnels, PoolAggregate(active, cap, anyBackpressured = tunnels.any { it.state == TunnelState.BACKPRESSURED }))
        }
    }

    // ============================================================================================================
    // C2 fixture — the FROZEN N-semantics of `TunnelSource.acquire()` (contract doc §4 C2): each acquire() returns
    // a DISTINCT live tunnel over its own rendezvous-id up to [cap]; past cap → null (fail-closed, never fallback).
    // ============================================================================================================
    private class FakePoolingTunnelSource(
        private val cap: Int,
        private val mint: suspend (rendezvousId: String) -> NoiseTunnel,
    ) : TunnelSource {
        private val next = AtomicInteger(0)
        val handedOut = CopyOnWriteArrayList<NoiseTunnel>()
        val rendezvousIds = CopyOnWriteArrayList<String>()
        override suspend fun acquire(): NoiseTunnel? {
            val idx = next.getAndIncrement()
            if (idx >= cap) return null // ★ hard cap → fail-closed (the transport RSTs; never a local fallback)
            val rvId = "rv-$idx"
            val t = mint(rvId) // a fresh, DISTINCT, live tunnel over its OWN rendezvous-id
            handedOut += t; rendezvousIds += rvId
            return t
        }
    }

    // ============================================================================================================
    // Teeth
    // ============================================================================================================

    @Test
    fun nTunnels_concurrentDatapath_converge2_perTunnelDistinct_witnessZero() = runBlocking {
        runNConcurrentDatapath(n = 2)
    }

    @Test
    fun nTunnels_concurrentDatapath_scalesToPoolCap() = runBlocking {
        runNConcurrentDatapath(n = harnessPoolCap)
    }

    /** N distinct tunnels carry N ops CONCURRENTLY through the real guard/routes; each tunnel's own bytes grew; an
     *  unrelated witness tunnel stays at ZERO (the anti-vacuity differential). This is the first real N>1 datapath. */
    private suspend fun runNConcurrentDatapath(n: Int) {
        val p = startTwoConnectorPlatform()
        // One real tunnel per logical connection; one transport per tunnel (each transport is single-flight, so N
        // transports = N concurrent connections — exactly the N-tunnel datapath the pooling source will serve).
        val handles = (0 until n).map { realTunnel(p.tunnelPort) }
        val witness = realTunnel(p.tunnelPort) // deliberately handed to NO transport
        val transports = handles.map { h ->
            val q = ConcurrentLinkedQueue<NoiseTunnel>().apply { add(h.tunnel) }
            buildRemoteHubTransport(currentTunnel = { q.poll() }, sessionToken = { p.agentToken }, scope = scope)!!
                .also { t -> cleanups += { t.close() } }
        }
        val witnessBase = witness.recorder.snapshot()

        // ★ N concurrent ops, one per tunnel → all carried to the real route (200 + real roster).
        val results = transports.map { t -> scope.async { oneShotGet("${t.httpBaseUrl}/api/agents", p.agentToken) } }.awaitAll()
        results.forEachIndexed { i, r ->
            assertEquals(200, r.status.value, "tunnel #$i carried its op to the real route (200) — N=$n concurrent datapath")
            assertTrue("backend" in r.bodyAsText(), "tunnel #$i returned the real roster over Noise")
        }
        // ★ distinctness: EACH tunnel's own recorder grew (if all ops shared ONE tunnel, only that one would grow).
        handles.forEachIndexed { i, h ->
            val d = h.recorder.snapshot()
            assertTrue(d.framesToHub > 0 && d.framesFromHub > 0, "tunnel #$i carried its OWN bytes (distinct per-tunnel datapath)")
        }
        // ★ differential / anti-vacuity: the un-handed witness tunnel saw ZERO frames from all N ops (no cross-leak).
        assertEquals(witnessBase, witness.recorder.snapshot(), "N ops added ZERO frames to an unrelated witness tunnel")
    }

    @Test
    fun godToken_refusedPerTunnel_onEveryTunnel_bothChannels() = runBlocking {
        val p = startTwoConnectorPlatform()
        val n = 3
        // ★ per-tunnel: the god token is refused on EVERY one of N tunnels (Bearer + WS), an agent token passes on each.
        repeat(n) { i ->
            val godTx = realTunnel(p.tunnelPort)
            val godTransport = buildRemoteHubTransport(currentTunnel = ConcurrentLinkedQueue<NoiseTunnel>().apply { add(godTx.tunnel) }::poll, sessionToken = { p.godToken }, scope = scope)!!
            cleanups += { godTransport.close() }
            assertEquals(401, oneShotGet("${godTransport.httpBaseUrl}/api/agents", p.godToken).status.value,
                "tunnel #$i: the god token as Bearer over the tunnel is refused 401 by the real per-tunnel guard")

            // `?token=` WS channel on the SAME logical tunnel (a fresh tunnel for the fresh connection, no-mux).
            val godWsTx = realTunnel(p.tunnelPort)
            val godWsTransport = buildRemoteHubTransport(currentTunnel = ConcurrentLinkedQueue<NoiseTunnel>().apply { add(godWsTx.tunnel) }::poll, sessionToken = { p.godToken }, scope = scope)!!
            cleanups += { godWsTransport.close() }
            val wsClient = HttpClient(CIO) { install(WebSockets) }
            cleanups += { runCatching { wsClient.close() } }
            var served = false
            withTimeoutOrNull(6_000) {
                runCatching {
                    wsClient.webSocket("${godWsTransport.wsBaseUrl}/ws/events?token=${p.godToken}") {
                        for (frame in incoming) { if (frame is Frame.Text) { served = true; break } }
                    }
                }
            }
            assertTrue(!served, "tunnel #$i: the god token via ?token= over the WS path is refused (no event served, no query bypass)")

            // non-vacuity per tunnel: an AGENT token over the SAME tunnel connector → 200 (only the god token is refused).
            val agentTx = realTunnel(p.tunnelPort)
            val agentTransport = buildRemoteHubTransport(currentTunnel = ConcurrentLinkedQueue<NoiseTunnel>().apply { add(agentTx.tunnel) }::poll, sessionToken = { p.agentToken }, scope = scope)!!
            cleanups += { agentTransport.close() }
            assertEquals(200, oneShotGet("${agentTransport.httpBaseUrl}/api/agents", p.agentToken).status.value,
                "tunnel #$i: an agent token over the tunnel is accepted (200) — the guard refuses ONLY the god token")
        }
        // non-vacuity: the SAME god token over the PUBLIC connector → 200 (the guard is port-scoped, not global).
        val pub = realTunnel(p.publicPort)
        val pubTransport = buildRemoteHubTransport(currentTunnel = ConcurrentLinkedQueue<NoiseTunnel>().apply { add(pub.tunnel) }::poll, sessionToken = { p.godToken }, scope = scope)!!
        cleanups += { pubTransport.close() }
        assertEquals(200, oneShotGet("${pubTransport.httpBaseUrl}/api/agents", p.godToken).status.value,
            "the SAME god token over the PUBLIC connector is served 200 — the tunnel guard is port-scoped")
    }

    @Test
    fun poolCap_handsOutDistinctUpToCap_thenFailsClosed_endToEnd() = runBlocking {
        val p = startTwoConnectorPlatform()
        val cap = 4
        val source = FakePoolingTunnelSource(cap) { realTunnel(p.tunnelPort).tunnel }

        // ★ C2 direct semantics: exactly `cap` DISTINCT non-null tunnels, then null (fail-closed) past the cap.
        val acquired = (0 until cap).map { source.acquire() }
        acquired.forEachIndexed { i, t -> assertNotNull(t, "acquire() #$i within cap returns a live tunnel") }
        assertEquals(cap, acquired.filterNotNull().distinct().size, "the $cap acquired tunnels are all DISTINCT")
        assertEquals(cap, source.rendezvousIds.distinct().size, "each acquired tunnel has its OWN distinct rendezvous-id")
        assertNull(source.acquire(), "acquire() past the cap returns null (fail-closed, never a fallback tunnel)")
        assertNull(source.acquire(), "acquire() stays null past the cap")

        // ★ end-to-end fail-closed: wire a FRESH pool into the REAL transport. The first `cap` connections are carried
        //   (200, live); the (cap+1)th → acquire()==null → conn.reset() (RST) → the request fails CLOSED, never a
        //   local 200. (Single-flight transport → drive the connections sequentially.)
        val e2ePool = FakePoolingTunnelSource(cap) { realTunnel(p.tunnelPort).tunnel }
        val transport = RemoteTunnelHubTransport(tunnelSource = e2ePool, sessionTokenProvider = { p.agentToken }, scope = scope)
        cleanups += { transport.close() }
        repeat(cap) { i ->
            assertEquals(200, oneShotGet("${transport.httpBaseUrl}/api/agents", p.agentToken).status.value,
                "connection #$i within cap is carried over a pooled tunnel (200)")
        }
        val overflow = runCatching { oneShotGet("${transport.httpBaseUrl}/api/agents", p.agentToken).status.value }
        assertTrue(overflow.isFailure,
            "the (cap+1)th connection fails CLOSED (RST) when the pool is exhausted — noLiveTunnel_failsClosed (got ${overflow.getOrNull()})")
    }

    @Test
    fun c3_poolState_observableContract_notBehavior_perTunnelStatesAndAggregate() {
        // ⚠ OBSERVABLE-CONTRACT, NOT BEHAVIOR. This asserts the FROZEN C3 shape against a FAKE emitter — it proves
        // the assertion-machinery stands against the contract (ready to swap), NOT that backpressure actually holds.
        // A fake-driven BACKPRESSURED must NEVER be read as "backpressure proven" — the real per-tunnel pump bound
        // is H7/CYP-535 (Team-1, unlanded; the bridge pump uses UNLIMITED channels today). This tooth GRADUATES to
        // behavior-proven when H7 lands: swap the fake for the real emitter, real saturation drives BACKPRESSURED.
        val cap = harnessPoolCap
        val ps = FakePoolState(cap)

        // two tunnels DIALING → UP
        ps.mark("rv-0", TunnelState.DIALING); ps.mark("rv-1", TunnelState.DIALING)
        ps.mark("rv-0", TunnelState.UP); ps.mark("rv-1", TunnelState.UP)
        var s = ps.snapshot()
        assertEquals(2, s.aggregate.active, "both live tunnels are active")
        assertEquals(cap, s.aggregate.cap, "aggregate carries the pool cap")
        assertTrue(!s.aggregate.anyBackpressured, "no backpressure while both are UP")
        assertEquals(TunnelState.UP, s.tunnels.first { it.rendezvousId == "rv-0" }.state)

        // rv-1 saturates → BACKPRESSURED; aggregate.anyBackpressured flips true (still active — it's UP-ish, bounded)
        ps.mark("rv-1", TunnelState.BACKPRESSURED)
        s = ps.snapshot()
        assertTrue(s.aggregate.anyBackpressured, "★ a saturated tunnel surfaces as BACKPRESSURED in the aggregate (C3)")
        assertEquals(TunnelState.BACKPRESSURED, s.tunnels.first { it.rendezvousId == "rv-1" }.state)
        assertTrue(s.tunnels.first { it.rendezvousId == "rv-1" }.sinceTs > s.tunnels.first { it.rendezvousId == "rv-0" }.sinceTs,
            "the state-transition timestamp advances (sinceTs is per-transition)")

        // rv-0 drops → DOWN (no longer active); rv-1 recovers → UP (backpressure clears)
        ps.mark("rv-0", TunnelState.DOWN); ps.mark("rv-1", TunnelState.UP)
        s = ps.snapshot()
        assertEquals(1, s.aggregate.active, "a DOWN tunnel is not active")
        assertTrue(!s.aggregate.anyBackpressured, "backpressure clears when the saturated tunnel recovers")
        assertEquals(TunnelState.DOWN, s.tunnels.first { it.rendezvousId == "rv-0" }.state)
    }

    // ------------------------------------------------------------------------------------------------------------
    // Infra (self-contained, mirrors CypM2TierBTransportTest so the proven single-tunnel teeth stay untouched).
    // ------------------------------------------------------------------------------------------------------------

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
                command.getOrNull(1) == "worktree" && command.getOrNull(2) == "add" -> {
                    val target = if (command.getOrNull(3) == "-b") command.getOrNull(5) else command.getOrNull(3)
                    target?.let { File(it).mkdirs() }
                }
            }
            return CommandResult(0, "")
        }
    }

    private data class TwoConn(val publicPort: Int, val tunnelPort: Int, val agentToken: String, val godToken: String, val booted: BootedPlatform)

    private fun startTwoConnectorPlatform(): TwoConn {
        val agentToken = "tok-backend"
        val godToken = "tok-op"
        val booted = BootOrchestrator(
            PlatformConfig(
                RepoConfig("git@github.com:org/repo.git", "main"),
                agents = listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("backend", "BE", Role.WORKER)),
            ),
            Secrets(mapOf(agentToken to "backend"), operatorToken = godToken, apiKey = null),
            WorktreeManager(FakeGit(), Files.createTempDirectory("cyp-m2-ntunnel").toFile()),
            FakeSpawner(),
            scope,
        ).boot()
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

    private suspend fun oneShotGet(url: String, bearer: String? = null): HttpResponse {
        val c = HttpClient(CIO)
        return try {
            val r = c.get(url) { if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer") }
            r.bodyAsText()
            r
        } finally { c.close() }
    }

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
