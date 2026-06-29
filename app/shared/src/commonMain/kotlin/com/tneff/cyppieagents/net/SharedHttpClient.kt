package com.tneff.cyppieagents.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets

/**
 * Keep-alive ping interval for the shared WS client (CYP-115). Idle `/ws/comm` + `/ws/lifecycle` sockets
 * get periodic ping frames so the **Darwin (iOS) WebSocket engine doesn't idle-close** them — which was
 * the root cause of the reconnect churn (the JVM/CIO engine holds idle sockets open, so the churn never
 * reproduced on Desktop; `/ws/events` stayed alive only because its steady frame stream kept it active).
 * Harmless on JVM/Web. Must be below the Darwin idle timeout; iOS-dev may tune on Darwin verification.
 */
const val WS_PING_INTERVAL_MILLIS = 15_000L

/**
 * The shared HTTP + WebSocket client for the app shell (one instance per shell). The [WS_PING_INTERVAL_MILLIS]
 * keep-alive is the CYP-115 fix: it keeps idle WS sockets active on the Darwin engine so they aren't
 * idle-closed (→ no `reconnecting()` churn, no lost-push exposure). Verified on Darwin by iOS-dev — the
 * JVM/CIO engine never churns idle sockets, so this is intentionally **not** a Darwin-green claim from the
 * JVM gate (the JVM test only asserts the keep-alive is configured).
 */
fun sharedWsHttpClient(): HttpClient = HttpClient {
    install(WebSockets) {
        pingIntervalMillis = WS_PING_INTERVAL_MILLIS
    }
}
