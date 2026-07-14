package com.tneff.cyppieagents.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory

/**
 * CYP-427 (Phase-2 / M2) — the **tunnel-scoped God-token reject**. The remote operator reaches the hub over the
 * Noise tunnel; [com.tneff.cyppieagents.transport.LoopbackBridge] pumps the decrypted bytes into a **dedicated
 * loopback connector** (the "tunnel connector", bound on `127.0.0.1:[tunnelPort]`) that ONLY the bridge dials.
 *
 * This gate structurally **refuses the static machine operator token ("God token") on that connector**: the static
 * token grants *unscoped* MachineOperator authority AND bypasses the Op-Session-TTL / revocation (CYP-484), so it
 * must never authenticate over the tunnel — even with a single operator (the CP-scoped Kratos operator session the
 * remote operator uses instead is narrower AND revocable; the static token is neither). On the **public** connector
 * the God token still works (the local operator UI). Agent tokens (read-tier) and Kratos operator sessions are
 * untouched — [isGodToken] (= `TokenRegistry::isOperator`) is true ONLY for the one static string, and a Kratos
 * session authenticates on a different axis (`X-Session-Token` / cookie → Human operator, `isOperator` == false).
 *
 * **Why the connector port, not an origin/marker:** the discriminator is the connector's own **local port**
 * (`call.request.local.localPort`) — a server-side fact the remote client cannot influence (it never chooses which
 * connector accepted its bytes). So the dumb byte-pump stays dumb: no untrusted client marker, no origin sniffing,
 * no per-route rethread. It covers **both** axes the auth layer honors — the `Authorization` bearer AND the
 * `?token=` WS/query fallback — because a God token on either would otherwise authenticate.
 *
 * Runs on `ApplicationCallPipeline.Plugins` (before routing / any route-scoped auth); fail-closed with 401 + finish().
 */
fun Application.installTunnelGodTokenGuard(tunnelPort: Int, isGodToken: (String?) -> Boolean) {
    val log = LoggerFactory.getLogger("boot.tunnel-god-token")
    intercept(ApplicationCallPipeline.Plugins) {
        if (call.request.local.localPort != tunnelPort) return@intercept // public connector — unchanged
        val presented = listOfNotNull(call.bearerToken(), call.request.queryParameters["token"])
        if (presented.any { isGodToken(it) }) {
            log.warn(
                "CYP-427: static operator (God) token refused on the tunnel connector (port {}) — a remote operator " +
                    "must present a CP-scoped operator session, not the static token",
                tunnelPort,
            )
            call.respond(HttpStatusCode.Unauthorized)
            finish() // definitively stop the pipeline — routing / route-scoped auth never runs for this call
        }
    }
}
