package com.tneff.cyppieagents.transport

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * CYP-459 (S3) — the production `tunnelHandler` for [NoiseRelayConnector]: run the RR3 gate on the terminated tunnel
 * and **only if authorized** bridge it to the existing routes. RR3 and the byte-bridge stay orthogonal: the gate
 * authenticates at the tunnel boundary (CpJwt + PoP vs live `h`), the bridge stays a dumb byte-pump (T2). Fail-closed:
 * a rejected gate or any error closes the tunnel and never bridges.
 *
 * CYP-484 (②) — this is also the **session-lifetime** boundary. On authorize the session is registered in the
 * [TunnelSessionRegistry] (so a **revocation** can immediately drop it — Decision 4), and a **passive Op-Session-TTL**
 * ([sessionTtlMs], minutes) tears the tunnel down when it elapses (re-auth required). [authorize] / [bridge] are
 * functional seams (prod = `Rr3TunnelGate::authorize` / `LoopbackBridge::bridge`) so the lifetime is unit-testable.
 */
class Rr3AuthenticatedTunnelHandler(
    private val authorize: suspend (ServerNoiseTunnel) -> Boolean,
    private val bridge: suspend (ServerNoiseTunnel) -> Unit,
    private val registry: TunnelSessionRegistry,
    private val operatorId: String,
    private val sessionTtlMs: Long,
) {
    /** The `suspend (ServerNoiseTunnel) -> Unit` seam value: `handler::handle`. */
    suspend fun handle(tunnel: ServerNoiseTunnel): Unit = coroutineScope {
        try {
            if (!authorize(tunnel)) {
                tunnel.close() // rejected → terminal, never bridge
                return@coroutineScope
            }
            // Authorized: register for revocation + arm the passive Op-Session-TTL, then bridge until the connection
            // ends, the TTL fires, or a revocation closes the tunnel — whichever is first.
            // close-handle: the tunnel's close() is suspend, so a revocation (a synchronous call) fires it on this
            // session's scope — fire-and-forget, matching "immediate teardown" (the bridge then ends on the null read).
            val sessionId = registry.register(operatorId) { launch { runCatching { tunnel.close() } } }
            val ttl = launch {
                delay(sessionTtlMs)
                runCatching { tunnel.close() } // ★ passive Op-Session-TTL teardown (Decision 4)
            }
            try {
                bridge(tunnel)
            } finally {
                ttl.cancel()
                registry.unregister(sessionId)
            }
        } catch (_: Exception) {
            runCatching { tunnel.close() } // fail-closed on any gate/bridge error
        }
    }
}
