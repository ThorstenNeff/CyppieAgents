package com.tneff.cyppieagents.routing

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory

/**
 * CYP-31 — an EXPLICIT WebSocket Origin allowlist gate, defense-in-depth ON TOP OF [installRestrictedCors].
 *
 * **Why not just rely on CORS.** [installRestrictedCors] is the ONLY server-side WS-origin control today, and it
 * is a **NO-OP when `web.allowedOrigins` is empty** (a valid config) → WS then has ZERO origin protection (only
 * the localhost bind). And a CORS plugin is really an XHR/fetch mechanism; leaning on its 403-on-WS-upgrade
 * behaviour for a SECURITY control (cross-site WebSocket hijacking, CSWSH) is version-fragile and only spot-tested
 * (2 of the 8 ws routes). This gate is explicit, uniform across EVERY WS upgrade, and holds regardless of the
 * CORS config (empty allowlist included).
 *
 * **Permit rule (fail-closed for a present, foreign Origin):**
 *  - **no `Origin` header** → permit. A native client (Desktop/CIO, hub-CLI) never sends `Origin`; it is gated by
 *    the bearer/`?token=` auth + the localhost bind. Blocking it would break the non-browser path.
 *  - **same-origin** (`Origin` == the request's own `scheme://host:port`) → permit — a page WSing to its own
 *    origin is not CSWSH.
 *  - **`Origin` in [allowedOrigins]** → permit — the explicitly-configured cross-origin frontends.
 *  - **anything else** → `403`, the upgrade never happens.
 *
 * No new breakage: a legitimately cross-origin frontend already needs its origin in `web.allowedOrigins` for REST
 * CORS to function, so its origin is permitted here too. This only closes the empty-allowlist hole and makes the
 * control explicit + per-route testable. Install AFTER [installRestrictedCors] (both are app-wide, pre-routing).
 */
fun Application.installWsOriginGuard(allowedOrigins: List<String>) {
    val allowed = allowedOrigins.mapNotNull(::normalizeWsOrigin).toSet()
    install(
        createApplicationPlugin("WsOriginGuard") {
            val log = LoggerFactory.getLogger("boot.ws-origin")
            onCall { call ->
                val isWsUpgrade = call.request.headers[HttpHeaders.Upgrade]?.lowercase()?.contains("websocket") == true
                if (!isWsUpgrade) return@onCall
                val originHeader = call.request.headers[HttpHeaders.Origin] ?: return@onCall // native client → permit
                val origin = normalizeWsOrigin(originHeader)
                val self = normalizeWsOrigin(
                    "${call.request.origin.scheme}://${call.request.origin.serverHost}:${call.request.origin.serverPort}",
                )
                if (origin != null && (origin == self || origin in allowed)) return@onCall
                log.warn("WS upgrade refused: foreign Origin '{}' (self={}, allowlist size={})", originHeader, self, allowed.size)
                call.respond(HttpStatusCode.Forbidden)
            }
        },
    )
}

/**
 * Normalize an origin to `scheme://host:port` (lowercased host, default ports made explicit), or null if
 * malformed. `http`/`ws` default to 80, `https`/`wss` to 443. A trailing path/slash is stripped — the Origin
 * header is an origin, but be defensive. Used for BOTH the allowlist entries and the request/Origin comparison,
 * so a suffix-spoof (`localhost.evil.example`) or a port/scheme mismatch can never match.
 */
internal fun normalizeWsOrigin(raw: String): String? {
    val idx = raw.indexOf("://")
    if (idx <= 0) return null
    val scheme = raw.substring(0, idx).lowercase()
    val authority = raw.substring(idx + 3).trimEnd('/').substringBefore('/')
    val host = authority.substringBefore(':').lowercase()
    if (host.isBlank()) return null
    val port = authority.substringAfter(':', "").toIntOrNull() ?: if (scheme == "https" || scheme == "wss") 443 else 80
    return "$scheme://$host:$port"
}
