package com.tneff.cyppieagents

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

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
        )
    }
}

/**
 * CYP-474 §4 — a minimal RFC 8252 §7.3 loopback redirect listener: a single-shot `127.0.0.1` HTTP endpoint that,
 * on the OAuth callback, fires [onReturn] (= `AuthViewModel.onGithubReturn`) and shuts down. Bound to loopback
 * only (never a public interface).
 *
 * ⚑ Backend/ops dependency (flagged): the OIDC `redirect_uri` Kratos redirects to must point at this loopback
 * port — the exact port/URI + robust listener lifecycle (reuse/close across attempts) is the real-wiring
 * follow-up, coordinated with the Kratos config. Fail-soft here: a bind failure is a no-op (the handoff copy
 * still shows honestly), never a crash.
 */
private fun armLoopbackListener(onReturn: () -> Unit) {
    runCatching {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", LOOPBACK_PORT), 0)
        server.createContext("/callback") { exchange ->
            val body = "Anmeldung abgeschlossen — zurück zur App.".encodeToByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            runCatching { onReturn() }
            server.stop(0)
        }
        server.start()
    }
}

/** The loopback redirect port (must match the Kratos-configured `redirect_uri` — see [armLoopbackListener]). */
private const val LOOPBACK_PORT = 47472
