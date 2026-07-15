package com.tneff.cyppieagents.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tunnel-warmth incident (REST half) — the loopback datapath client must PIN CIO explicitly. A bare `HttpClient{}`
 * (CIO + OkHttp both on the jvm classpath, no explicit engine) silently no-ops BOTH `endpoint.keepAliveTime`
 * (CIO-only → idle loopback conns closed → tunnel churn) AND `WebSockets{pingIntervalMillis}` (ignored on OkHttp →
 * no tunnel-WS keepalive). Pinning CIO makes both real.
 */
class LoopbackHttpClientTest {

    @Test
    fun pinnedClient_usesCioEngine_soKeepAliveAndPingApply() {
        val client = pinnedCioWsHttpClient()
        try {
            // Reddening mutation: revert the transport/relay client to the bare `sharedWsHttpClient` (no explicit
            // engine) ⇒ the auto-selected engine is OkHttp (or non-deterministic) ⇒ engine is NOT CIOEngine ⇒ RED.
            assertEquals(
                "CIOEngine",
                client.engine::class.simpleName,
                "the loopback datapath client MUST pin CIO explicitly — else endpoint.keepAliveTime + WebSockets{pingInterval} silently no-op",
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun keepAliveConstant_isLongEnoughToKeepTunnelsWarm() {
        // The warm-connection primitive: idle loopback connections are not idle-expired, so their Noise tunnel is
        // reused across requests instead of dial-per-request churned. Reddening mutation: set the constant back to a
        // short/default value (e.g. the CIO ~5s default) ⇒ RED (the REST tunnel churn returns).
        assertTrue(
            LOOPBACK_KEEP_ALIVE_MS >= 60_000L,
            "loopback keep-alive must span the REST cadence so tunnels stay warm (was $LOOPBACK_KEEP_ALIVE_MS ms)",
        )
    }
}
