package com.tneff.cyppieagents.net

import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.websocket.WebSockets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-115 regression (as far as JVM-sensible): the shared WS client configures the keep-alive ping. This is
 * NOT a Darwin-green claim — the JVM/CIO engine never churns idle sockets, so the actual ~5min idle-hold
 * verification is iOS-dev's on Darwin. Here we only prove the keep-alive is wired (mutation: drop
 * `pingIntervalMillis` in [sharedWsHttpClient] → the plugin default returns → this goes RED).
 */
class SharedHttpClientTest {

    @Test
    fun sharedClient_configuresKeepAlivePing() {
        val client = sharedWsHttpClient()
        try {
            assertEquals(
                WS_PING_INTERVAL_MILLIS,
                client.plugin(WebSockets).pingIntervalMillis,
                "the shared WS client must set the CYP-115 keep-alive ping",
            )
            // Sane: a positive interval below a safe Darwin idle bound (iOS-dev may tune within this).
            assertTrue(WS_PING_INTERVAL_MILLIS in 1L..30_000L)
        } finally {
            client.close()
        }
    }
}
