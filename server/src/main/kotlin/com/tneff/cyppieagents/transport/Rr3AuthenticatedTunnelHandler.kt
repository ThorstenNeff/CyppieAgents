package com.tneff.cyppieagents.transport

/**
 * CYP-459 (S3) — the production `tunnelHandler` for [NoiseRelayConnector]: run the [Rr3TunnelGate] on the terminated
 * tunnel and **only if authorized** hand it to the [LoopbackBridge]. This is the composite that keeps RR3 and the
 * byte-bridge orthogonal: the gate authenticates at the tunnel boundary (CpJwt + PoP vs live `h`), the bridge stays a
 * dumb byte-pump (T2). Fail-closed: any exception, or a rejected gate, closes the tunnel and never bridges.
 */
class Rr3AuthenticatedTunnelHandler(
    private val gate: Rr3TunnelGate,
    private val bridge: LoopbackBridge,
) {
    /** The `suspend (ServerNoiseTunnel) -> Unit` seam value: `handler::handle`. */
    suspend fun handle(tunnel: ServerNoiseTunnel) {
        try {
            if (gate.authorize(tunnel)) {
                bridge.bridge(tunnel) // authorized → present L2 to the existing routes (which re-verify their own cred)
            } else {
                tunnel.close() // rejected → terminal, never bridge
            }
        } catch (_: Exception) {
            runCatching { tunnel.close() } // fail-closed on any gate/bridge error
        }
    }
}
