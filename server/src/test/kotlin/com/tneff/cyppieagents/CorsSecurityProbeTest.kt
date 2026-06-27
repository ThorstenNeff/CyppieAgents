package com.tneff.cyppieagents

import com.tneff.cyppieagents.routing.installRestrictedCors
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * QA security pass for CYP-30 CORS. Adversarial probes for the gaps the unit CorsTest doesn't cover:
 * the WS-upgrade origin path (PO check #5), preflight from a foreign origin, and matching strictness
 * (scheme / port / suffix-spoof). CORS is installed app-wide in `bootPlatform` BEFORE the WS routes,
 * so the same Origin check governs `/ws/comm` and `/ws/agent` — these probes prove that behaviorally.
 */
class CorsSecurityProbeTest {

    private val allowed = "http://localhost:8080"

    private fun ApplicationTestBuilder.app() = application {
        installRestrictedCors(listOf(allowed))
        install(WebSockets)
        routing {
            get("/api/health") { call.respondText("ok") }
            webSocket("/ws/comm") { /* no-op: we only exercise the CORS gate on the upgrade GET */ }
            webSocket("/ws/agent") { }
        }
    }

    /** PO check #5: a WS upgrade is a GET carrying Origin → an off-origin page must be CORS-blocked. */
    @Test
    fun wsCommUpgradeFromForeignOrigin_blockedByCors() = testApplication {
        app()
        val res = client.get("/ws/comm") { header(HttpHeaders.Origin, "http://evil.example") }
        assertEquals(HttpStatusCode.Forbidden, res.status, "off-origin page must not reach the /ws/comm upgrade")
    }

    @Test
    fun wsAgentUpgradeFromForeignOrigin_blockedByCors() = testApplication {
        app()
        val res = client.get("/ws/agent") { header(HttpHeaders.Origin, "http://evil.example") }
        assertEquals(HttpStatusCode.Forbidden, res.status, "off-origin page must not reach the /ws/agent upgrade")
    }

    /** The allowed origin must pass the CORS gate on the WS path (ACAO present, not a 403 block). */
    @Test
    fun wsCommFromAllowedOrigin_passesCorsGate() = testApplication {
        app()
        val res = client.get("/ws/comm") { header(HttpHeaders.Origin, allowed) }
        assertEquals(allowed, res.headers[HttpHeaders.AccessControlAllowOrigin], "allowed origin must clear CORS on /ws/comm")
        assertNotEquals(HttpStatusCode.Forbidden, res.status)
    }

    /** A preflight from a disallowed origin must NOT be granted ACAO. */
    @Test
    fun preflightFromForeignOrigin_getsNoAcao() = testApplication {
        app()
        val res = client.options("/api/health") {
            header(HttpHeaders.Origin, "http://evil.example")
            header(HttpHeaders.AccessControlRequestMethod, "GET")
        }
        assertNull(res.headers[HttpHeaders.AccessControlAllowOrigin], "foreign preflight must not be granted ACAO")
    }

    /** Strictness: a different scheme on the same host:port must NOT match an http-only allow. */
    @Test
    fun schemeMismatch_isRejected() = testApplication {
        app()
        val res = client.get("/api/health") { header(HttpHeaders.Origin, "https://localhost:8080") }
        assertEquals(HttpStatusCode.Forbidden, res.status, "https origin must not match an http-only allow")
    }

    /** Strictness: a different port must NOT match. */
    @Test
    fun portMismatch_isRejected() = testApplication {
        app()
        val res = client.get("/api/health") { header(HttpHeaders.Origin, "http://localhost:9999") }
        assertEquals(HttpStatusCode.Forbidden, res.status, "different port must not match")
    }

    /** Strictness: an attacker host that merely embeds the allowed host as a label must NOT match. */
    @Test
    fun suffixSpoofOrigin_isRejected() = testApplication {
        app()
        val res = client.get("/api/health") { header(HttpHeaders.Origin, "http://localhost.evil.example:8080") }
        assertEquals(HttpStatusCode.Forbidden, res.status, "suffix/subdomain spoof of the allowed host must not match")
    }
}
