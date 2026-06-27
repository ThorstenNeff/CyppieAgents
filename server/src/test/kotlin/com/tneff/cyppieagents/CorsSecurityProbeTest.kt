package com.tneff.cyppieagents

import com.tneff.cyppieagents.routing.installRestrictedCors
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * QA security pass for CYP-30 CORS — reviewer re-cert conditions.
 *
 * The WS-origin protection hangs on plugin order (CORS installed app-wide in `bootPlatform` BEFORE
 * the WS routes). These probes prove it with a REAL WebSocket handshake (not a plain GET on the
 * `/ws/…` path): a foreign Origin must be rejected at the upgrade on BOTH `/ws/comm` and `/ws/agent`,
 * the allowed Origin must connect, and a MISSING Origin (native clients, e.g. Desktop/CIO) must be
 * allowed — else the non-browser path breaks. This test must remain: it turns red if a refactor
 * silently drops the CORS-before-WS ordering.
 */
class CorsSecurityProbeTest {

    private val allowed = "http://localhost:8080"

    private fun ApplicationTestBuilder.app() = application {
        installRestrictedCors(listOf(allowed))
        install(WebSockets)
        routing {
            get("/api/health") { call.respondText("ok") }
            webSocket("/ws/comm") { /* no-op: we only exercise the CORS gate on the upgrade handshake */ }
            webSocket("/ws/agent") { }
        }
    }

    private fun ApplicationTestBuilder.wsClient() = createClient { install(ClientWebSockets) }

    /** Attempts a real WS handshake; returns true iff the upgrade succeeded (block ran). */
    private suspend fun ApplicationTestBuilder.wsConnects(path: String, origin: String?): Boolean =
        runCatching {
            wsClient().webSocket(path, request = { if (origin != null) header(HttpHeaders.Origin, origin) }) {
                // handshake succeeded — nothing to do; session closes when the block returns
            }
        }.isSuccess

    // ---- WS upgrade, real handshake: foreign Origin rejected on BOTH routes ----

    @Test
    fun wsCommHandshake_foreignOrigin_rejected() = testApplication {
        app()
        assertFalse(wsConnects("/ws/comm", "http://evil.example"), "foreign origin must not complete the /ws/comm upgrade")
    }

    @Test
    fun wsAgentHandshake_foreignOrigin_rejected() = testApplication {
        app()
        assertFalse(wsConnects("/ws/agent", "http://evil.example"), "foreign origin must not complete the /ws/agent upgrade")
    }

    /** Suffix-spoof of the allowed host must not open a socket either. */
    @Test
    fun wsCommHandshake_suffixSpoofOrigin_rejected() = testApplication {
        app()
        assertFalse(wsConnects("/ws/comm", "http://localhost.evil.example:8080"), "suffix-spoof origin must not complete the upgrade")
    }

    // ---- WS upgrade: allowed Origin connects ----

    @Test
    fun wsCommHandshake_allowedOrigin_connects() = testApplication {
        app()
        assertTrue(wsConnects("/ws/comm", allowed), "allowed origin must complete the /ws/comm upgrade")
    }

    @Test
    fun wsAgentHandshake_allowedOrigin_connects() = testApplication {
        app()
        assertTrue(wsConnects("/ws/agent", allowed), "allowed origin must complete the /ws/agent upgrade")
    }

    // ---- WS upgrade: MISSING Origin (native client, e.g. Desktop/CIO) must be allowed ----

    @Test
    fun wsCommHandshake_noOrigin_connects() = testApplication {
        app()
        assertTrue(wsConnects("/ws/comm", null), "native client (no Origin) must still connect — non-browser path must not break")
    }

    @Test
    fun wsAgentHandshake_noOrigin_connects() = testApplication {
        app()
        assertTrue(wsConnects("/ws/agent", null), "native client (no Origin) must still connect on /ws/agent")
    }

    // ---- Plain CORS strictness (HTTP) ----

    @Test
    fun preflightFromForeignOrigin_getsNoAcao() = testApplication {
        app()
        val res = client.options("/api/health") {
            header(HttpHeaders.Origin, "http://evil.example")
            header(HttpHeaders.AccessControlRequestMethod, "GET")
        }
        assertNull(res.headers[HttpHeaders.AccessControlAllowOrigin], "foreign preflight must not be granted ACAO")
    }

    @Test
    fun schemeMismatch_isRejected() = testApplication {
        app()
        val res = client.get("/api/health") { header(HttpHeaders.Origin, "https://localhost:8080") }
        assertEquals(HttpStatusCode.Forbidden, res.status, "https origin must not match an http-only allow")
    }

    @Test
    fun portMismatch_isRejected() = testApplication {
        app()
        val res = client.get("/api/health") { header(HttpHeaders.Origin, "http://localhost:9999") }
        assertEquals(HttpStatusCode.Forbidden, res.status, "different port must not match")
    }

    @Test
    fun suffixSpoofOrigin_httpIsRejected() = testApplication {
        app()
        val res = client.get("/api/health") { header(HttpHeaders.Origin, "http://localhost.evil.example:8080") }
        assertEquals(HttpStatusCode.Forbidden, res.status, "suffix/subdomain spoof of the allowed host must not match")
    }
}
