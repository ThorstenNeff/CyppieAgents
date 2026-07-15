package com.tneff.cyppieagents.net

import com.tneff.cyppieagents.net.hub.pool.REST_DEDICATED_CONNS
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.EndpointConfig
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
 *
 * **CYP-610 — this is now the WS leg only.** It is NOT connection-capped: each of the ≤[com.tneff.cyppieagents.net.hub.pool.WS_RESERVED_SLOTS]
 * long-lived WS opens its own connection (one Noise tunnel per socket, no mux). REST uses the separate
 * [pinnedCioRestHttpClient] so it can be capped to ONE shared socket and can never take a WS tunnel slot.
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
 * CYP-610 — the **REST leg** of the split loopback client (the 6-agent-remote root fix). Identical to
 * [pinnedCioWsHttpClient] (CIO-pinned, ∞ keep-alive, `X-Session-Token`) EXCEPT it caps `maxConnectionsPerRoute` to
 * [REST_DEDICATED_CONNS]: the workspace's dozen+ REST repos then **share ONE warm keep-alive socket** (requests
 * pipelined/serialized over it — CIO reuses the one connection), so REST holds **≤ [REST_DEDICATED_CONNS] Noise tunnel(s)**
 * and can NEVER starve the [WS_RESERVED_SLOTS] persistent WS out of the 15-usable-id pool (the 1-up/6-churn root).
 * Backend's model: "one tunnel = one SOCKET, not one REQUEST" — short/sequential REST shares the socket via HTTP/1.1.
 * No `WebSockets` plugin — this leg never upgrades. ∞ keep-alive is now safe *because* the cap bounds it to one socket.
 */
fun pinnedCioRestHttpClient(sessionToken: () -> String? = { null }): HttpClient = HttpClient(CIO) {
    engine { endpoint { applyRestLoopbackCap() } }
    install(DefaultRequest) { sessionToken()?.let { header("X-Session-Token", it) } }
}

/**
 * CYP-610 — the REST loopback endpoint config, extracted so a unit tooth can assert the cap **deterministically**
 * (no socket race): [maxConnectionsPerRoute] = [REST_DEDICATED_CONNS] funnels all REST onto that many shared keep-alive
 * sockets (CIO default is 100 = effectively uncapped), and the ∞ [keepAliveTime] keeps that socket warm. Removing the
 * cap line reverts [maxConnectionsPerRoute] to the CIO default ⇒ the tooth reddens.
 */
internal fun EndpointConfig.applyRestLoopbackCap() {
    keepAliveTime = LOOPBACK_KEEP_ALIVE_MS
    maxConnectionsPerRoute = REST_DEDICATED_CONNS
}

/**
 * Effectively-∞ idle keep-alive (ms) for the loopback (`127.0.0.1`) remote-datapath client: a warm connection is
 * reused across requests so its Noise tunnel is not dial-per-request churned. Staleness (a warm connection whose
 * tunnel died underneath) is healed by the reconnect layer, not by an idle-expiry.
 */
const val LOOPBACK_KEEP_ALIVE_MS: Long = Long.MAX_VALUE
