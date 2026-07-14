package com.tneff.cyppieagents.net.hub

import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.sharedWsHttpClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 * **Concurrency (single-flight, by design):** a `NoiseTunnel` is ONE ordered duplex stream = ONE connection (no mux, RR8).
 * The accept-loop carries one connection per tunnel via the [TunnelSource] seam. Phase-1 (this thru-cut) sources the ONE
 * authenticated session tunnel → proves the datapath end-to-end for a single connection. The concurrent multi-agent
 * workspace (many live WS) is the **N-tunnel follow-on**: [TunnelSource] dials a fresh tunnel per connection (Backend
 * gate: the Noise-Terminator must accept N per operator) — it plugs in here without touching the transport or the handoff.
 *
 * **Ownership:** the transport owns the [acceptor] + (unless injected) the [httpClient]; it does NOT own the tunnels
 * (those are [com.tneff.cyppieagents.net.hub.remote.RemoteHubSession]'s — Seam-3 lifecycle, incl. relay-drop re-bind).
 */
class RemoteTunnelHubTransport(
    private val tunnelSource: TunnelSource,
    private val sessionTokenProvider: () -> String?,
    scope: CoroutineScope,
    private val acceptor: LoopbackAcceptor = RealLoopbackAcceptor(),
    private val bridge: ClientLoopbackBridge = ClientLoopbackBridge(),
    injectedClient: HttpClient? = null,
) : HubTransport {

    // The workspace dials THIS loopback (never the hub's real host:port) — every byte then rides the Noise tunnel.
    override val httpBaseUrl: String = "http://127.0.0.1:${acceptor.port}"
    override val wsBaseUrl: String = "ws://127.0.0.1:${acceptor.port}"

    private val ownsClient: Boolean = injectedClient == null
    override val httpClient: HttpClient = injectedClient ?: sharedWsHttpClient(sessionTokenProvider)

    override fun sessionToken(): String? = sessionTokenProvider()

    /** The accept-loop: one connection at a time (single-flight, no mux) over a tunnel from [tunnelSource]. */
    internal val acceptJob: Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            val conn = acceptor.accept() ?: break // acceptor closed → stop
            val tunnel = tunnelSource.acquire()
            if (tunnel == null) {
                conn.reset() // no live tunnel ⇒ fail-closed (RST), never a plaintext/local fallback
                continue
            }
            bridge.pump(tunnel, conn) // carries this connection until either side ends (RR8, no mux)
        }
    }

    override fun close() {
        acceptor.close() // unblocks a pending accept() → the loop ends
        acceptJob.cancel()
        if (ownsClient) httpClient.close()
        // The tunnel is RemoteHubSession-owned (Seam-3) — never closed here.
    }
}

/**
 * The source of the [NoiseTunnel] that carries the next accepted loopback connection. Phase-1 returns the single
 * authenticated session tunnel (the thru-cut, one connection); the N-tunnel follow-on dials a fresh tunnel per
 * connection (no-mux, RR8). `null` ⇒ no live tunnel ⇒ the transport fails the connection closed (RST).
 */
fun interface TunnelSource {
    suspend fun acquire(): NoiseTunnel?
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
