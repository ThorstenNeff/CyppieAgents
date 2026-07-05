package com.tneff.cyppieagents.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header

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
/**
 * CYP-229 — install the browser same-origin fetch-credentials wrapper (see [installSameOriginCredentials]) so the
 * `ory_kratos_session` cookie actually rides on the `/api` reads. No-op on native targets.
 */
fun sharedWsHttpClient(sessionToken: () -> String? = { null }): HttpClient {
    installSameOriginCredentials()
    return HttpClient {
        install(WebSockets) {
            pingIntervalMillis = WS_PING_INTERVAL_MILLIS
        }
        // CYP-188/229 — session-credential seam. A signed-in user with NO operator token must still authenticate
        // every data read AND WS handshake, else it sends an empty `Bearer ` → the server 401s the whole app.
        // NATIVE (JVM/iOS/Android): attach the Kratos session as the `X-Session-Token` header (read live from the
        // auth session store via [sessionToken]) on every request, incl. the WS upgrade. BROWSER (Wasm/JS):
        // [sessionToken] is null — the browser session's credential is the same-origin `ory_kratos_session` cookie.
        // **CYP-229 fix:** Ktor's JS/Wasm `fetch` OMITS credentials and exposes no config to change it, so the
        // cookie did NOT ride (→ a session-operator's whole read surface 401'd → empty agent list → no windows).
        // [installSameOriginCredentials] forces `credentials:'include'` for same-origin fetches → the cookie rides;
        // still no header on browser, so the request never carries BOTH the header and the cookie (whoami-500).
        install(DefaultRequest) {
            sessionToken()?.let { header("X-Session-Token", it) }
        }
    }
}

/**
 * CYP-229 — ensure browser requests carry the same-origin session cookie. Ktor's JS/Wasm engine uses `fetch`,
 * which OMITS credentials unless the RequestInit sets them, and Ktor exposes no credentials config. This installs a
 * ONE-TIME, idempotent wrapper over `window.fetch` that forces `credentials:'include'` for **same-origin** requests
 * only (the app + API are same-origin via `ShellConfig.forOrigin`); cross-origin fetches are untouched (no CORS
 * change / no server change). No-op on native targets (they carry the session via `X-Session-Token` / their own jar).
 */
expect fun installSameOriginCredentials()
