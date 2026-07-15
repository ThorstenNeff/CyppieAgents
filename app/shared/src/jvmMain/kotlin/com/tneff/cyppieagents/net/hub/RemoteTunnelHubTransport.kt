package com.tneff.cyppieagents.net.hub

import com.tneff.cyppieagents.net.Backoff
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.pool.TunnelLane
import com.tneff.cyppieagents.net.logWsTeardown
import com.tneff.cyppieagents.net.pinnedCioRestHttpClient
import com.tneff.cyppieagents.net.pinnedCioWsHttpClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * M2 Seam-1 (CYP-457 Path-A, Desktop/JVM) — the **real** tunnel-backed [HubTransport]: the load-bearing naht that makes
 * the (already mode-blind, CYP-411) workspace operate over the Noise tunnel instead of the LAN. It exposes a **loopback**
 * `http(s)/ws` base the UNMODIFIED workspace Ktor client dials; a [LoopbackAcceptor] accepts that local connection and a
 * [ClientLoopbackBridge] pumps the socket bytes ↔ the [NoiseTunnel] (the CR3-① datapath). No new engine, no mux — the hub
 * HTTP/1.1(+WS) runs over the tunnel like HTTP over TLS (`NoiseTunnel` KDoc, RR8).
 *
 * **Concurrency (per-connection, CYP-556):** a `NoiseTunnel` is ONE ordered duplex stream = ONE connection (no mux, RR8).
 * The accept-loop handles **each** accepted connection in its **own coroutine** — `accept()` is the only serial step;
 * the per-connection `acquire()`+`pump()` run concurrently, so N live workspace WS are served over N distinct tunnels
 * from the pooling [TunnelSource] **without the accept-loop serializing them**. This is the F-M2-1 fix: an *inline* pump
 * would block the loop on the first connection until it ends, serializing every other WS behind it (the workspace hang).
 * The pool ([TunnelSource]) dials a fresh tunnel per connection (Backend gate: the Noise-Terminator accepts N per operator).
 *
 * **Ownership:** the transport owns the [acceptor] + (unless injected) the [httpClient]; it does NOT own the tunnels
 * (those are [com.tneff.cyppieagents.net.hub.remote.RemoteHubSession]'s — Seam-3 lifecycle, incl. relay-drop re-bind).
 */
class RemoteTunnelHubTransport(
    private val tunnelSource: TunnelSource,
    private val sessionTokenProvider: () -> String?,
    scope: CoroutineScope,
    // CYP-616: TWO loopback acceptors so the accept-loop knows the lane BY PORT (no byte-sniff). REST (lifecycle) dials
    // [httpBaseUrl]→[restAcceptor]→CONTROL lane (reserved break-glass slot); WS dials [wsBaseUrl]→[wsAcceptor]→DATA lane.
    private val wsAcceptor: LoopbackAcceptor = RealLoopbackAcceptor(),
    private val restAcceptor: LoopbackAcceptor = RealLoopbackAcceptor(),
    private val bridge: ClientLoopbackBridge = ClientLoopbackBridge(),
    // CYP-619: the backoff schedule for the DATA-lane HOLD+retry on a null acquire (default = the standard WS backoff);
    // injectable so a test drives it fast/deterministically.
    private val dataRetryBackoff: Backoff = Backoff(),
    injectedClient: HttpClient? = null,
) : HubTransport {

    // The workspace dials THIS loopback (never the hub's real host:port) — every byte then rides the Noise tunnel. CYP-616:
    // REST and WS dial DISTINCT loopback ports so the transport routes the acquire lane by which acceptor accepted.
    override val httpBaseUrl: String = "http://127.0.0.1:${restAcceptor.port}"
    override val wsBaseUrl: String = "ws://127.0.0.1:${wsAcceptor.port}"

    private val ownsClient: Boolean = injectedClient == null
    // Tunnel-warmth fix: the loopback datapath client PINS CIO explicitly so `endpoint.keepAliveTime` (idle-conn
    // warmth → tunnel reuse, no REST churn) AND `WebSockets{pingInterval}` actually take effect — a bare
    // `sharedWsHttpClient` (no engine, CIO+OkHttp both on classpath) silently no-ops both (the churn root).
    // CYP-610: split into a connection-CAPPED REST client (≤REST_DEDICATED_CONNS loopback sockets → ≤1 REST tunnel) and
    // an UNBOUNDED WS client (one socket per long-lived WS → its own tunnel), so REST can never starve the 14 WS out of
    // the 15-usable-id pool (the 6-agent 1-up/6-churn root). A test-injected client stands in for BOTH (cap untested there).
    override val httpClient: HttpClient = injectedClient ?: pinnedCioRestHttpClient(sessionTokenProvider)
    override val wsHttpClient: HttpClient = injectedClient ?: pinnedCioWsHttpClient(sessionTokenProvider)

    override fun sessionToken(): String? = sessionTokenProvider()

    /**
     * The accept-loop. **`accept()` is the only serial step**; each accepted connection is handled in its **own child
     * coroutine**, so N live workspace WS are served concurrently over N distinct tunnels (CYP-556 — an *inline* pump
     * would block the loop on the first WS forever, serializing/hanging every other WS: F-M2-1).
     *
     * Concurrency decisions (CYP-556, per the Assist concurrency lens — deliberate + documented):
     *  - **① α — `acquire()` runs INSIDE the per-connection `launch`** (concurrent), so it uses the pool's
     *    purpose-built **off-lock dial** ([com.tneff.cyppieagents.net.hub.pool.PooledTunnelSource]) for true N-tunnel
     *    concurrency + parallel startup. (β — serial `acquire()`, launch only the pump — has a smaller race surface
     *    but under-uses the pool's concurrent dial and serializes startup.) α's concurrent-acquire race is made safe
     *    by the pool's `reserveSlot` mutex (never over-issues past cap) + its `closed`-under-`liveLock` recheck (no
     *    close-mid-dial leak) — both covered by the pool's race-interleaving teeth.
     *  - **② [supervisorScope] + per-connection `try/finally`** — a failure in ONE pump cancels only ITS connection,
     *    never the accept-loop or sibling pumps; the `finally` closes the (pooled) tunnel on ANY exit so an exception
     *    frees its slot (idempotent). The children are children of THIS job → [close]'s `cancel()` tears them all down
     *    together (no leak, no bytes after close — H4); they are NOT `scope.launch` (which would outlive `close()`).
     */
    // CYP-616: ONE accept-loop per acceptor, each tagging its accepted connections with a fixed [lane] (the port IS the
    // lane). Same CYP-556 per-connection structure (accept serial, acquire+pump per-connection concurrent) — the ONLY
    // change is `acquire(lane)`. WS→DATA (gated below the reserve), REST→CONTROL (may take the reserved break-glass slot).
    private fun launchAcceptLoop(scope: CoroutineScope, acceptor: LoopbackAcceptor, lane: TunnelLane): Job =
        scope.launch(Dispatchers.IO) {
            supervisorScope { // ②: an exception in one pump child never cancels the loop or its sibling pumps
                while (isActive) {
                    val conn = acceptor.accept() ?: break // acceptor closed → stop (the ONLY serial step)
                    launch { // ①/②: per-connection — acquire + pump run concurrently; the loop immediately accepts the next
                        // CYP-619: on a DATA-lane `acquire=null` DO NOT hard-RST the loopback. The hard reset TEARS the
                        // conn → the workspace Ktor client re-dials → acquire=null again → RST = the storm cascade the
                        // dogfood logged (`held=21` overlap → 21× churn/agent). Instead HOLD the conn (the workspace just
                        // waits, like connection latency) and backoff-retry until a DATA slot frees. Capacity is proven
                        // sufficient (CYP-619 check: DATA_steady = 8 singleton-WS + N agents = 16 at N=8, < the 21 DATA
                        // budget), so the overlap resolves and a slot WILL free. CONTROL single-tries: its CYP-616 reserved
                        // slot means a null there = the WHOLE pool (incl. reserve) is full = genuine exhaustion → honest
                        // fail-closed reset (and CONTROL is rare/short — no cascade). Bounded by the coroutine: [close] /
                        // hub-switch cancels this child ⇒ the loop exits with `tunnel == null` ⇒ the conn is reset (clean).
                        var tunnel: NoiseTunnel? = null
                        var attempt = 0
                        while (isActive) {
                            tunnel = try {
                                tunnelSource.acquire(lane)
                            } catch (t: Throwable) {
                                // CYP-561 NOTE-1: if acquire() RE-THROWS (e.g. a dial exception the pool re-raises), the
                                // accepted loopback socket would otherwise leak until GC — reset it, then re-propagate
                                // (a cancellation still cancels; the reset is fail-closed cleanup either way).
                                runCatching { conn.reset() }
                                throw t
                            }
                            if (tunnel != null || lane != TunnelLane.DATA) break // got a tunnel, OR CONTROL (single-try)
                            // DATA + no slot: hold the conn, back off, and retry — the anti-cascade of CYP-619.
                            attempt += 1
                            com.tneff.cyppieagents.net.logWsPool("transport", "DATA acquire=null → HOLD+backoff (attempt=$attempt; no RST-tear, waiting for a DATA slot — CYP-619)")
                            delay(dataRetryBackoff.delayFor(attempt))
                        }
                        val live = tunnel // capture the var into a val so the closures below smart-cast to non-null
                        if (live == null) {
                            // Reached for a CONTROL null (whole pool incl. reserve full = genuine exhaustion) OR a DATA loop
                            // exited via cancellation (transport close / hub-switch). Fail-closed RST — never a plaintext fallback.
                            com.tneff.cyppieagents.net.logWsPool("transport", "acquire(lane=$lane)=null → RST loopback conn (no tunnel; see ws-pool:reserve for cause)")
                            conn.reset() // no live tunnel ⇒ fail-closed (RST), never a plaintext/local fallback
                        } else {
                            try {
                                bridge.pump(live, conn) // carries this connection until either side ends (RR8, no mux)
                            } finally {
                                runCatching { live.close() } // slot-release on ANY exit (idempotent; a pooled tunnel frees its slot)
                            }
                        }
                    }
                }
            }
        }

    internal val wsAcceptJob: Job = launchAcceptLoop(scope, wsAcceptor, TunnelLane.DATA)
    internal val restAcceptJob: Job = launchAcceptLoop(scope, restAcceptor, TunnelLane.CONTROL)

    override fun close() {
        // Tunnel-warmth incident instrumentation: this close() cancels BOTH accept-loops → ALL pooled tunnels are torn
        // synchronously. Log WHO called it (the caller stack) so an instrumented re-test pins the batch-teardown trigger.
        // No secrets — stack frames only, no tokens/handshake material.
        logWsTeardown("transport", "close() → accept-loops cancelled, all pooled tunnels torn; caller=\n" + callerHint())
        wsAcceptor.close(); restAcceptor.close() // unblock pending accept()s → the loops end
        wsAcceptJob.cancel(); restAcceptJob.cancel()
        if (ownsClient) { httpClient.close(); wsHttpClient.close() } // CYP-610: both owned legs (distinct when not injected)
        // The tunnel is RemoteHubSession-owned (Seam-3) — never closed here.
    }

    /** The immediate caller chain (a few frames), for the teardown instrumentation — pins who triggered close(). */
    private fun callerHint(): String =
        Throwable().stackTrace.drop(1).take(6).joinToString("\n") { "  at $it" }
}

/**
 * The source of the [NoiseTunnel] that carries the next accepted loopback connection. Phase-1 returns the single
 * authenticated session tunnel (the thru-cut, one connection); the N-tunnel follow-on dials a fresh tunnel per
 * connection (no-mux, RR8). `null` ⇒ no live tunnel ⇒ the transport fails the connection closed (RST).
 */
fun interface TunnelSource {
    /** CYP-616: [lane] selects the pool lane — [TunnelLane.CONTROL] (lifecycle-REST via the restAcceptor) draws from the
     *  reserved break-glass headroom; [TunnelLane.DATA] (WS via the wsAcceptor) is gated below it. */
    suspend fun acquire(lane: TunnelLane): NoiseTunnel?
}

/** Accepts loopback connections for [RemoteTunnelHubTransport]. A seam so tests drive the accept-loop without a real socket. */
interface LoopbackAcceptor {
    /** The bound loopback port ([httpBaseUrl]/[wsBaseUrl] point here). */
    val port: Int
    /** Blocks for the next accepted loopback connection, or `null` when [close]d. */
    suspend fun accept(): BridgeConn?
    fun close()
}

/** Production [LoopbackAcceptor] over a real `127.0.0.1` [ServerSocket] (ephemeral port); each accept → a [RealBridgeConn]. */
class RealLoopbackAcceptor : LoopbackAcceptor {
    private val server: ServerSocket = ServerSocket().apply {
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) // loopback-only, ephemeral — never a public iface
    }
    override val port: Int = server.localPort
    override suspend fun accept(): BridgeConn? = withContext(Dispatchers.IO) {
        runCatching { RealBridgeConn(server.accept() as Socket) }.getOrNull() // null when the socket is closed
    }
    override fun close() { runCatching { server.close() } }
}
