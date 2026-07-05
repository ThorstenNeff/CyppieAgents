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
 * CYP-243 — web bootstrap ordering seam. Runs [installSameOriginCredentials] SYNCHRONOUSLY, THEN [startUi]. Called
 * from the web entry point (`main.kt`) **before** `ComposeViewport`, so `window.fetch` is patched before ANY Ktor
 * client is created — including the **pre-shell auth-probe client** (`buildLiveAuthRepository` → `GET /api/auth/me`
 * from `AuthViewModel.init`), which today fires UNWRAPPED (install lived only in [sharedWsHttpClient], reached at
 * shell-mount). The shell's own [installSameOriginCredentials] call stays as an idempotent (CYP-238 `__cyppieCredsPatched`
 * guard) belt-and-suspenders. Pure ordering: install first, then start — the one non-vacuous, testable invariant of
 * an otherwise untestable `main()` (see `WebBootstrapTest`). No-op wrapper on native (install is a no-op there).
 */
fun installCredentialsThenStart(startUi: () -> Unit) {
    installSameOriginCredentials()
    startUi()
}

/**
 * CYP-229/231 — a ONE-TIME, idempotent wrapper over the browser `window.fetch` that hardens **same-origin** requests
 * (cross-origin fetches are untouched — the same-origin guard is the security boundary). Ktor's JS/Wasm engine uses
 * `fetch` and exposes no config for either concern:
 *  - **CYP-229:** force `credentials:'include'` so the `ory_kratos_session` cookie rides the `/api` reads (Ktor's
 *    fetch OMITS credentials by default → a session-operator's whole read surface 401'd → empty agent list).
 *  - **CYP-231:** echo the JS-readable double-submit CSRF cookie (`cyppie_csrf`) in the `X-CSRF-Token` header on
 *    unsafe methods (POST/PUT/DELETE/PATCH) → else a cookie-authed write is a 403 `csrf_failed`. Safe methods +
 *    cross-origin never get the header (no token leak).
 * No server/CORS change (same-origin). No-op on native targets (they carry the session via `X-Session-Token`).
 */
expect fun installSameOriginCredentials()
