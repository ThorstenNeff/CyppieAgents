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
import com.tneff.cyppieagents.net.hub.pool.BackpressureSignal
import com.tneff.cyppieagents.net.hub.pool.PoolTunnelDialer
import com.tneff.cyppieagents.net.hub.pool.PooledTunnelSource
import com.tneff.cyppieagents.net.hub.pool.TUNNEL_POOL_CAP
import com.tneff.cyppieagents.net.hub.pool.TunnelState
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
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-539 (WS4) — the **N-tunnel E2E harness**, GRADUATED onto the real CYP-537 pool (develop-landing base
 * `b1035fb9`). Extends the single-flight Tier-B proof ([CypM2TierBTransportTest]) to **N concurrent Noise tunnels**,
 * the M2 Option-A datapath that fixes F-M2-1 (one tunnel = one duplex stream → the ~8 eager persistent WS deadlock
 * on a single tunnel). Design + frozen contracts: `docs/design/M2-A-ntunnel-workstream-split-and-contract.md`.
 *
 * **Graduated fake→real.** The C3 observability now asserts on the **real** `net.hub.pool.TunnelPoolState` emitted by
 * a **real** [PooledTunnelSource] (no test-local fixture), and the pool-cap/fail-closed tooth drives the real
 * transport with `acquireTunnel = pool::acquire` (the real pool client, per the WS2 rename `currentTunnel→acquireTunnel`).
 * What is REAL: the crypto (real Noise NK tunnels), the routes (the real booted platform), the guard (Backend's real
 * `installTunnelGodTokenGuard`), and the pool (real `PooledTunnelSource` cap + C3 state machine). The only substitution
 * is the in-memory relay for the live relay WS (identical to Tier-B), and — for the pool-cap/C3 teeth — a lightweight
 * [PoolTunnelDialer] that mints those real tunnels to the guarded tunnel connector (the CP/relay dial is Backend's
 * `NoisePoolTunnelDialer`, exercised in its own joint auth proof `Cyp536JointNTunnelAuthE2eTest`).
 *
 * **Division of labour (no duplication):** the operator-auth joint properties (1-UV-for-N, cross-tunnel anti-replay,
 * bounded-reuse) are Backend's `Cyp536JointNTunnelAuthE2eTest` over the real `Rr3TunnelGate` — NOT re-proven here.
 * This harness owns the datapath + per-tunnel guard + pool-cap fail-closed + C3 observability.
 *
 * Teeth (each non-vacuous — the keystone mutation that would make it pass falsely is named in each, confirmed RED
 * in a probe run):
 *  - ★ N-datapath: N distinct tunnels carry N ops CONCURRENTLY (all 200 + real roster), each tunnel's own bytes grew
 *    (distinctness), an unrelated witness tunnel stays at ZERO frames (no cross-leak). Converge N=2 → scale to
 *    `TUNNEL_POOL_CAP`. Keystone RED: share ONE tunnel across ops → the per-tunnel-grew set collapses.
 *  - ★ god-token refused PER tunnel across ALL N (WS6 vector, k>0) on BOTH channels (Bearer 401, `?token=` WS
 *    no-event); agent token → 200 on each; god on PUBLIC connector → 200 (port-scoped). **F②-1 positive control:** an
 *    AGENT token over `/ws/events?token=` IS served an event → the WS path is functional, so the god no-event is the
 *    GUARD refusing, not a broken WS. Keystone RED: drop the per-tunnel guard → god accepted on the tunnels.
 *  - ★ pool-cap fail-closed (real [PooledTunnelSource]): `acquire()` hands out exactly `cap` DISTINCT live tunnels
 *    then `null`. A connection over a headroom pool is carried (200 — the positive control that the routes are
 *    healthy); then a PRE-EXHAUSTED pool (cap tunnels held open, no slot free) wired into the real transport makes the
 *    accept-loop's `acquire()==null` → conn.reset() (RST) → the connection fails CLOSED (an IOException class, never a
 *    local 200). (Sequential connections can't exhaust the pool: the bridge's clean-EOF `tunnel.close()` frees the slot
 *    — correct reuse — so exhaustion is driven by concurrent HOLD.) The honest keystone is CAP-DISCRIMINATION
 *    (`assertNotNull`×cap + `assertNull` past cap), not a null-vs-local claim — the transport has no local-serve path
 *    (a null just RSTs). Keystone RED: cap-bypass → the exhausted pool would hand a tunnel → the connection 200s.
 *  - ★ C3 pool-state (real emitter, behavior-driven): the real [PooledTunnelSource.state] surfaces DIALING→UP on
 *    acquire, BACKPRESSURED via the real [BackpressureSignal] seam, DOWN on close, and `aggregate{active,cap,
 *    anyBackpressured}` tracks it. (The PUMP auto-firing the backpressure signal under real saturation is H7/CYP-535's
 *    own test; here the real pool state machine is driven via the real seam.) Keystone RED: assert `anyBackpressured`
 *    without firing the seam → false.
 */
class CypM2NTunnelHarnessTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cleanups = mutableListOf<() -> Unit>()

    @AfterTest fun tearDown() {
        cleanups.asReversed().forEach { runCatching { it() } }
        scope.cancel()
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
        runNConcurrentDatapath(n = TUNNEL_POOL_CAP)
    }

    /** N distinct tunnels carry N ops CONCURRENTLY through the real guard/routes; each tunnel's own bytes grew; an
     *  unrelated witness tunnel stays at ZERO (the anti-vacuity differential). This is the first real N>1 datapath. */
    private suspend fun runNConcurrentDatapath(n: Int) {
        val p = startTwoConnectorPlatform()
        // One real tunnel per logical connection; one transport per tunnel (each transport is single-flight, so N
        // transports = N concurrent connections — exactly the N-tunnel datapath the pool serves in prod).
        val handles = (0 until n).map { realTunnel(p.tunnelPort) }
        val witness = realTunnel(p.tunnelPort) // deliberately handed to NO transport
        val transports = handles.map { h ->
            val q = ConcurrentLinkedQueue<NoiseTunnel>().apply { add(h.tunnel) }
            buildRemoteHubTransport(acquireTunnel = { q.poll() }, sessionToken = { p.agentToken }, scope = scope)!!
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
            val godQ = ConcurrentLinkedQueue<NoiseTunnel>().apply { add(godTx.tunnel) }
            val godTransport = buildRemoteHubTransport(acquireTunnel = { godQ.poll() }, sessionToken = { p.godToken }, scope = scope)!!
            cleanups += { godTransport.close() }
            assertEquals(401, oneShotGet("${godTransport.httpBaseUrl}/api/agents", p.godToken).status.value,
                "tunnel #$i: the god token as Bearer over the tunnel is refused 401 by the real per-tunnel guard")

            // `?token=` WS channel on the SAME logical tunnel (a fresh tunnel for the fresh connection, no-mux).
            val godWsTx = realTunnel(p.tunnelPort)
            val godWsQ = ConcurrentLinkedQueue<NoiseTunnel>().apply { add(godWsTx.tunnel) }
            val godWsTransport = buildRemoteHubTransport(acquireTunnel = { godWsQ.poll() }, sessionToken = { p.godToken }, scope = scope)!!
            cleanups += { godWsTransport.close() }
            assertTrue(!wsEventServed(godWsTransport.wsBaseUrl, p.godToken),
                "tunnel #$i: the god token via ?token= over the WS path is refused (no event served, no query bypass)")

            // non-vacuity per tunnel: an AGENT token over the SAME tunnel connector → 200 (only the god token is refused).
            val agentTx = realTunnel(p.tunnelPort)
            val agentQ = ConcurrentLinkedQueue<NoiseTunnel>().apply { add(agentTx.tunnel) }
            val agentTransport = buildRemoteHubTransport(acquireTunnel = { agentQ.poll() }, sessionToken = { p.agentToken }, scope = scope)!!
            cleanups += { agentTransport.close() }
            assertEquals(200, oneShotGet("${agentTransport.httpBaseUrl}/api/agents", p.agentToken).status.value,
                "tunnel #$i: an agent token over the tunnel is accepted (200) — the guard refuses ONLY the god token")
        }

        // ★ F②-1 POSITIVE CONTROL (anti-vacuity on the WS auth axis): an AGENT token over `/ws/events?token=` IS
        //   served an event — so the WS path is functional and carries events, and the god no-event above is the
        //   GUARD refusing, not a broken/dead WS (which would also yield no-event, indistinguishable without this).
        val posTx = realTunnel(p.tunnelPort)
        val posQ = ConcurrentLinkedQueue<NoiseTunnel>().apply { add(posTx.tunnel) }
        val posTransport = buildRemoteHubTransport(acquireTunnel = { posQ.poll() }, sessionToken = { p.agentToken }, scope = scope)!!
        cleanups += { posTransport.close() }
        assertTrue(wsEventServed(posTransport.wsBaseUrl, p.agentToken),
            "F②-1 positive control: an AGENT token over /ws/events?token= IS served an event — the WS path works, so the god no-event is the guard")

        // non-vacuity: the SAME god token over the PUBLIC connector → 200 (the guard is port-scoped, not global).
        val pub = realTunnel(p.publicPort)
        val pubQ = ConcurrentLinkedQueue<NoiseTunnel>().apply { add(pub.tunnel) }
        val pubTransport = buildRemoteHubTransport(acquireTunnel = { pubQ.poll() }, sessionToken = { p.godToken }, scope = scope)!!
        cleanups += { pubTransport.close() }
        assertEquals(200, oneShotGet("${pubTransport.httpBaseUrl}/api/agents", p.godToken).status.value,
            "the SAME god token over the PUBLIC connector is served 200 — the tunnel guard is port-scoped")
    }

    @Test
    fun poolCap_realPool_handsOutDistinctUpToCap_thenFailsClosed_endToEnd() = runBlocking {
        val p = startTwoConnectorPlatform()
        val cap = 4

        // ★ C2 direct semantics on the REAL PooledTunnelSource: exactly `cap` DISTINCT non-null tunnels, then null.
        val source = realPool(p.tunnelPort, cap)
        val acquired = (0 until cap).map { source.acquire() }
        acquired.forEachIndexed { i, t -> assertNotNull(t, "real pool acquire() #$i within cap returns a live tunnel") }
        assertEquals(cap, acquired.filterNotNull().distinct().size, "the $cap acquired tunnels are all DISTINCT")
        assertNull(source.acquire(), "real pool acquire() past the cap returns null (fail-closed, never a fallback tunnel)")
        assertNull(source.acquire(), "real pool acquire() stays null past the cap")
        // and the real C3 state reflects the cap-full pool.
        assertEquals(cap, source.state.value.aggregate.active, "the real pool reports `cap` active tunnels")
        assertEquals(cap, source.state.value.aggregate.cap, "the real pool aggregate carries its cap")

        // ★ POSITIVE CONTROL (transport+real-pool wiring carries an op): a fresh pool with headroom → one connection is
        //   carried over a pooled tunnel (200). Note single-flight + the bridge's clean-EOF `tunnel.close()` FREES the
        //   pool slot when a connection ends, so sequential connections REUSE slots (correct pool behaviour) and cannot
        //   themselves exhaust the pool — exhaustion is a CONCURRENT-hold property, driven explicitly below.
        val livePool = realPool(p.tunnelPort, cap)
        val liveTransport = buildRemoteHubTransport(acquireTunnel = livePool::acquire, sessionToken = { p.agentToken }, scope = scope)!!
        cleanups += { liveTransport.close() }
        assertEquals(200, oneShotGet("${liveTransport.httpBaseUrl}/api/agents", p.agentToken).status.value,
            "positive control: a connection over a pooled tunnel is carried 200 — the routes/platform are healthy")

        // ★ end-to-end fail-closed: PRE-EXHAUST a fresh pool by acquiring + HOLDING `cap` tunnels (slots stay occupied,
        //   never closed) so acquire() is at null; wire THAT exhausted pool into the real transport → the accept-loop's
        //   `acquire()==null` → conn.reset() (RST) → the connection fails CLOSED (an IOException class), never a local 200.
        val exhaustedPool = realPool(p.tunnelPort, cap)
        val held = (0 until cap).map { assertNotNull(exhaustedPool.acquire(), "pre-exhaust hold #$it") }
        assertEquals(cap, held.distinct().size, "held $cap distinct tunnels")
        assertNull(exhaustedPool.acquire(), "the pool is now exhausted (cap held, no slot free)")
        val transport = buildRemoteHubTransport(acquireTunnel = exhaustedPool::acquire, sessionToken = { p.agentToken }, scope = scope)!!
        cleanups += { transport.close() }
        val overflow = runCatching { oneShotGet("${transport.httpBaseUrl}/api/agents", p.agentToken).status.value }
        assertTrue(overflow.isFailure,
            "a connection against an EXHAUSTED pool fails CLOSED (acquire null → RST), never a local 200 (got ${overflow.getOrNull()})")
        // sharpen (WS6 F②-1-adjacent): the failure is a connection-reset/EOF CLASS (the RST fail-closed signal), not a
        // timeout-hang or a masked success. Robust across CIO's exception surface (type OR cause OR message).
        val ex = overflow.exceptionOrNull()
        val exMsg = ((ex?.message ?: "") + " " + (ex?.cause?.message ?: "")).lowercase()
        assertTrue(
            ex is IOException || ex?.cause is IOException || listOf("reset", "closed", "eof", "refused", "end of").any { it in exMsg },
            "the fail-closed is a connection-reset/EOF class (RST), got $ex",
        )
    }

    @Test
    fun c3_realPoolState_perTunnel_dialingUp_backpressured_down_andAggregate() = runBlocking {
        val p = startTwoConnectorPlatform()
        val pool = realPool(p.tunnelPort, cap = TUNNEL_POOL_CAP)

        // acquire two real tunnels → the REAL pool state surfaces them UP (DIALING→UP happened inside acquire).
        val a = assertNotNull(pool.acquire(), "first pooled tunnel")
        val b = assertNotNull(pool.acquire(), "second pooled tunnel")
        assertTrue(a !== b, "two DISTINCT pooled tunnels")
        var s = pool.state.value
        assertEquals(2, s.aggregate.active, "both live tunnels are active in the real pool state")
        assertEquals(TUNNEL_POOL_CAP, s.aggregate.cap, "aggregate carries the real TUNNEL_POOL_CAP")
        assertTrue(!s.aggregate.anyBackpressured, "no backpressure while both are UP")
        assertTrue(s.tunnels.all { it.state == TunnelState.UP }, "both tunnels report UP")

        // ★ BACKPRESSURED via the REAL BackpressureSignal seam (the acquire() result IS a BackpressureSignal): the real
        //   pool state machine flips that tunnel BACKPRESSURED + aggregate.anyBackpressured. (Whether the PUMP fires
        //   this under real saturation is H7/CYP-535's own test — here the real pool emitter is driven via the real seam.)
        val aBackpressure = a as BackpressureSignal
        aBackpressure.onBackpressured(true)
        s = pool.state.value
        assertTrue(s.aggregate.anyBackpressured, "★ the real pool surfaces BACKPRESSURED in the aggregate (C3, behavior-driven)")
        assertEquals(1, s.tunnels.count { it.state == TunnelState.BACKPRESSURED }, "exactly the saturated tunnel is BACKPRESSURED")
        assertEquals(2, s.aggregate.active, "a BACKPRESSURED tunnel is still active (live-but-slow, not down)")

        // recover → UP (backpressure clears)
        aBackpressure.onBackpressured(false)
        assertTrue(!pool.state.value.aggregate.anyBackpressured, "backpressure clears when the tunnel recovers")

        // ★ DOWN on close: closing a pooled tunnel frees its slot in the real pool state.
        b.close()
        s = pool.state.value
        assertEquals(1, s.aggregate.active, "a closed (DOWN) tunnel is no longer active")
        assertTrue(s.tunnels.any { it.state == TunnelState.DOWN }, "the closed tunnel surfaces DOWN")
    }

    // ------------------------------------------------------------------------------------------------------------
    // Infra (self-contained, mirrors CypM2TierBTransportTest so the proven single-tunnel teeth stay untouched).
    // ------------------------------------------------------------------------------------------------------------

    /** A real [PooledTunnelSource] whose dialer mints [cap] REAL Noise tunnels to the guarded tunnel connector. The
     *  CP/relay dial is Backend's `NoisePoolTunnelDialer` (its own joint proof); here the pool's cap + C3 state are real. */
    private fun realPool(tunnelPort: Int, cap: Int): PooledTunnelSource {
        val ids = (0 until cap).map { "rv-$it" }
        val clock = AtomicLong(0)
        val dialer = object : PoolTunnelDialer {
            override suspend fun rendezvousSet(): List<String> = ids
            override suspend fun dial(rendezvousId: String): NoiseTunnel = realTunnel(tunnelPort).tunnel
        }
        return PooledTunnelSource(dialer = dialer, cap = cap, nowMs = { clock.incrementAndGet() })
            .also { pool -> cleanups += { runCatching { runBlocking { pool.close() } } } }
    }

    /** Subscribe `/ws/events?token=` over [wsBase]; true iff a text event frame is served within the window. */
    private suspend fun wsEventServed(wsBase: String, token: String): Boolean {
        val wsClient = HttpClient(CIO) { install(WebSockets) }
        cleanups += { runCatching { wsClient.close() } }
        var served = false
        withTimeoutOrNull(6_000) {
            runCatching {
                wsClient.webSocket("${wsBase.trimEnd('/')}/ws/events?token=$token") {
                    for (frame in incoming) { if (frame is Frame.Text) { served = true; break } }
                }
            }
        }
        return served
    }

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
