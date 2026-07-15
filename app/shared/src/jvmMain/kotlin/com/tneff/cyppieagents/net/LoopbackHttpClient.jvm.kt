package com.tneff.cyppieagents.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header

/**
 * Tunnel-warmth incident fix (REST half). The remote datapath dials `127.0.0.1` loopback for ALL workspace REST +
 * WS through ONE client. [sharedWsHttpClient] is a bare `HttpClient{}` with **no explicit engine**, and BOTH
 * `ktor-client-cio` and `ktor-client-okhttp` are on the jvm classpath → the engine is auto-selected (order-dependent)
 * and its engine-specific config **silently no-ops**:
 *  - CIO's `endpoint.keepAliveTime` (idle-connection keep-alive) does not apply unless the engine IS CIO — so idle
 *    loopback REST connections were closed after the default ~5s keep-alive → their Noise tunnels torn → a
 *    dial-per-request churn (the deploy-confirmed REST ~250ms-lifetime batch);
 *  - `WebSockets { pingIntervalMillis }` is **not applied on the OkHttp engine** (Ktor: OkHttp needs the ping in its
 *    own engine block) → the relay/tunnel WS got no keepalive ping.
 *
 * This client **pins CIO explicitly** so BOTH configs take effect: [LOOPBACK_KEEP_ALIVE_MS] keeps idle loopback
 * connections warm (HTTP/1.1 keep-alive then reuses their tunnel across requests — no churn), and the 15s WS ping is
 * real (correct keepalive hygiene on the relay/tunnel WS). Loopback-only (`127.0.0.1`) → an effectively-∞ keep-alive
 * is safe; a stale warm connection (its tunnel died underneath) heals via the reconnect layer ([reconnecting] /
 * `AgentWsClient`), which resets its backoff on a proven-healthy connection so the re-dial is prompt.
 */
fun pinnedCioWsHttpClient(sessionToken: () -> String? = { null }): HttpClient = HttpClient(CIO) {
    engine {
        endpoint {
            // Keep idle loopback connections warm so their Noise tunnel is reused across requests, not dial-per-request
            // churned. Loopback-only ⇒ ∞ is safe; staleness is healed by the reconnect layer.
            keepAliveTime = LOOPBACK_KEEP_ALIVE_MS
        }
    }
    // Real on CIO (a no-op on OkHttp): keepalive ping on the relay/tunnel WS. 15s < any reasonable proxy idle window.
    install(WebSockets) { pingIntervalMillis = WS_PING_INTERVAL_MILLIS }
    // CYP-188/229: attach the session credential (X-Session-Token) on every request incl. the WS upgrade (native).
    install(DefaultRequest) { sessionToken()?.let { header("X-Session-Token", it) } }
}

/**
 * Effectively-∞ idle keep-alive (ms) for the loopback (`127.0.0.1`) remote-datapath client: a warm connection is
 * reused across requests so its Noise tunnel is not dial-per-request churned. Staleness (a warm connection whose
 * tunnel died underneath) is healed by the reconnect layer, not by an idle-expiry.
 */
const val LOOPBACK_KEEP_ALIVE_MS: Long = Long.MAX_VALUE
