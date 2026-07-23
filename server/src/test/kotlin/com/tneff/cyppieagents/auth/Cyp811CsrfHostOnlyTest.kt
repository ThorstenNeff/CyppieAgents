package com.tneff.cyppieagents.auth

import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * CYP-811 — the CSRF cookie the operator browser flow relies on is HOST-ONLY (no `Domain=` attribute), so it is NEVER
 * shared across sibling hostnames/subdomains (§9.6 per-hub-origins). A `Domain=` would broaden the cookie's scope and
 * re-open the cross-origin cookie sharing that the per-hub loopback-IP separation ([LoopbackHubLock]) closes. Mutation:
 * add a `domain = …` to the `Cookie(...)` in `CsrfCookieIssuer` → the Set-Cookie carries `Domain=` → this reds.
 */
class Cyp811CsrfHostOnlyTest {

    @Test
    fun csrfCookie_isHostOnly_noDomainAttribute() = testApplication {
        application {
            install(CsrfCookieIssuer)
            routing { get("/x") { call.respondText("ok") } }
        }
        val resp: HttpResponse = client.get("/x")
        val setCookies = resp.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        val csrf = setCookies.firstOrNull { it.contains(CSRF_COOKIE) }
        assertNotNull(csrf, "CsrfCookieIssuer must emit the CSRF cookie on a cookieless request — got: $setCookies")
        assertFalse(
            csrf.contains("Domain=", ignoreCase = true),
            "the CSRF cookie MUST be host-only (no Domain=) so it never crosses sibling origins (§9.6) — got: $csrf",
        )
    }
}
