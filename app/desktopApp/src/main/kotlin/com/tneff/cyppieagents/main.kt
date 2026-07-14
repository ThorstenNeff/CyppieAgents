package com.tneff.cyppieagents

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.sun.net.httpserver.HttpServer
import com.tneff.cyppieagents.auth.OIDC_LOOPBACK_PORT
import com.tneff.cyppieagents.auth.parseLoopbackCode
import com.tneff.cyppieagents.auth.parseLoopbackParam
import java.net.InetSocketAddress
import java.security.SecureRandom

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "KMPCyppieAgents",
    ) {
        // CYP-185: open the GitHub OIDC redirect URL in the system browser — the platform-actual for §6
        // (commonMain only holds the state). CYP-575: the URL is ALWAYS logged to stdout and falls back to
        // `xdg-open` when AWT `Desktop` is unavailable, so an unsupported/throwing desktop is never a silent
        // dead end (the old `runCatching` swallowed it and the human could not sign in). See openExternalUrlWithFallback.
        App(
            onOpenExternalUrl = { url -> openExternalUrlWithFallback(url) },
            // CYP-474 §4: Desktop-native OIDC via RFC 8252 loopback (system browser + localhost redirect) — H3,
            // no embedded webview. The handoff states + copy live in commonMain; this host arms the return listener.
            nativeOidcLoopback = true,
            onAwaitLoopbackReturn = { onReturn -> armLoopbackListener(onReturn) },
            // CYP-576 P1: CSPRNG `state` nonce for the native OIDC handoff (Backend security-rec) — the VM holds it and
            // the loopback callback must echo it, so a foreign/forged callback is rejected.
            oidcStateProvider = { secureStateNonce() },
        )
    }
}

/** CYP-576 P1 — a 128-bit hex `state` nonce from `SecureRandom` (CSPRNG). Unguessable-per-attempt so a takeover would
 *  need to guess it AND break the Kratos pairing AND guess the app-private init_code (which never leaves the process). */
private val secureRng = SecureRandom()
private fun secureStateNonce(): String {
    val bytes = ByteArray(16)
    secureRng.nextBytes(bytes)
    return bytes.joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }
}

/**
 * CYP-474 §4 — a minimal RFC 8252 §7.3 loopback redirect listener: a single-shot `127.0.0.1` HTTP endpoint that,
 * on the OAuth callback, fires [onReturn] (= `AuthViewModel.onGithubReturn`) and shuts down. Bound to loopback
 * only (never a public interface).
 *
 * CYP-576: Kratos redirects the browser here as the API-flow `return_to` with `?code=<return_to_code>`; we extract
 * that code ([parseLoopbackCode]) and hand it to [onReturn] (it is redeemed with the init code at
 * `/sessions/token-exchange`). Port/URL are the single-source-of-truth [OIDC_LOOPBACK_PORT] — the API-flow
 * `return_to` uses the same constant, so the listener and the redirect target can never drift. Fail-soft: a bind
 * failure is a no-op (the handoff copy still shows honestly), never a crash.
 */
private fun armLoopbackListener(onReturn: (String?, String?) -> Unit) {
    // CYP-576 P1 (BUG-A/CYP-578): SURFACE a bind failure instead of swallowing it into an eternal "Continuing…" hang.
    // On any bind/start error, signal (null, null) → the VM rejects it (state mismatch) → retry-able Error. The VM's
    // handoff watchdog is the catch-all for the other hang causes (abandoned tab).
    val bound = runCatching {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", OIDC_LOOPBACK_PORT), 0)
        server.createContext("/callback") { exchange ->
            val query = exchange.requestURI?.rawQuery
            val code = parseLoopbackCode(query)              // CYP-576: the return_to_code (was discarded)
            val state = parseLoopbackParam(query, "state")   // CYP-576 P1: the nonce the VM must match
            val body = "Anmeldung abgeschlossen — zurück zur App.".encodeToByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            runCatching { onReturn(code, state) }
            server.stop(0)
        }
        server.start()
    }.isSuccess
    if (!bound) onReturn(null, null) // bind failed → break the hang now (do not silently no-op)
}
