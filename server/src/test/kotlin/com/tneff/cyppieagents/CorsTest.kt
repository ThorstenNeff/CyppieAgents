package com.tneff.cyppieagents

import com.tneff.cyppieagents.routing.installRestrictedCors
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** CYP-30: CORS restricted to the configured frontend origin(s) — never anyHost. */
class CorsTest {

    private fun ApplicationTestBuilder.appWithCors(origins: List<String>) = application {
        installRestrictedCors(origins)
        routing { get("/api/health") { call.respondText("ok") } }
    }

    private val allowed = "http://localhost:8080"

    @Test
    fun allowedOriginGetsAcaoHeader() = testApplication {
        appWithCors(listOf(allowed))
        val res = client.get("/api/health") { header(HttpHeaders.Origin, allowed) }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(allowed, res.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun preflightFromAllowedOriginSucceeds() = testApplication {
        appWithCors(listOf(allowed))
        val res = client.options("/api/health") {
            header(HttpHeaders.Origin, allowed)
            header(HttpHeaders.AccessControlRequestMethod, "GET")
        }
        assertEquals(allowed, res.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun disallowedOriginIsRejected() = testApplication {
        appWithCors(listOf(allowed))
        // A different origin must NOT be granted access (no anyHost).
        val res = client.get("/api/health") { header(HttpHeaders.Origin, "http://evil.example") }
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }

    @Test
    fun emptyOriginsIsFailClosed() = testApplication {
        appWithCors(emptyList()) // CORS not installed at all
        val res = client.get("/api/health") { header(HttpHeaders.Origin, allowed) }
        assertEquals(HttpStatusCode.OK, res.status) // same-origin/server still serves
        assertNull(res.headers[HttpHeaders.AccessControlAllowOrigin]) // but no cross-origin grant
    }
}
