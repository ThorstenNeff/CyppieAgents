package com.tneff.cyppieagents.routing

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS
import org.slf4j.LoggerFactory

/**
 * Restricted CORS for the web (Wasm/JS) client (Spec 02 §14, CYP-30): allows ONLY the explicitly
 * configured frontend origins — NEVER `anyHost` in production. The same Origin check also covers the
 * `/ws/agent` and `/ws/comm` upgrade requests (a WS upgrade is an HTTP GET carrying `Origin`), so a
 * browser served from an allowed origin can connect while off-origin pages are blocked. Pairs with
 * the localhost bind + `?token=` fallback (which is why the origin restriction matters).
 *
 * If [allowedOrigins] is empty, CORS is NOT installed → the browser blocks every cross-origin
 * request (fail-closed default). Each entry is a full origin URL, e.g. "http://localhost:8080".
 */
fun Application.installRestrictedCors(allowedOrigins: List<String>) {
    if (allowedOrigins.isEmpty()) {
        LoggerFactory.getLogger("boot.cors")
            .warn("no web.allowedOrigins configured — cross-origin web access stays disabled (fail-closed)")
        return
    }
    install(CORS) {
        for (origin in allowedOrigins) {
            val (scheme, host) = parseOrigin(origin)
            allowHost(host, schemes = listOf(scheme))
        }
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Options)
        // Deliberately NO anyHost(); bearer/?token auth means no cookies, so allowCredentials stays false.
    }
}

/** "http://localhost:8080" → ("http", "localhost:8080"). */
private fun parseOrigin(origin: String): Pair<String, String> {
    val idx = origin.indexOf("://")
    require(idx > 0) { "web.allowedOrigins entry must be a full origin URL (scheme://host[:port]): '$origin'" }
    val scheme = origin.substring(0, idx)
    val host = origin.substring(idx + 3).trimEnd('/')
    require(host.isNotBlank()) { "invalid origin '$origin'" }
    return scheme to host
}
