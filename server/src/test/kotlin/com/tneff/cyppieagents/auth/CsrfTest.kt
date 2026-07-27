package com.tneff.cyppieagents.auth

import com.tneff.cyppieagents.routing.ApiException
import com.tneff.cyppieagents.routing.TokenRegistry
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseServerSetCookieHeader
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-178 / RC5 — the platform's double-submit CSRF (hermetic). Enforced ONLY where it can bite: a
 * **cookie-authenticated, state-changing** request. A cookie-session POST must carry `X-CSRF-Token` equal
 * to the `cyppie_csrf` cookie; a bearer-token caller (no cookie) is immune; safe methods are skipped. The
 * issuer sets the JS-readable SameSite=Strict cookie when absent.
 */
class CsrfTest {

    private val db = Files.createTempFile("csrf-roles", ".db")

    private fun ApplicationTestBuilder.installGuardedWithCsrf() {
        val deps = AuthDeps(
            tokens = TokenRegistry(emptyMap(), operatorToken = "tok-op", loopbackPosture = true),
            // A verified human whose session credential IS the cookie value "sess-op" → bootstraps OPERATOR.
            idp = FakeIdentityProvider(mapOf("sess-op" to ResolvedIdentity("alice", verified = true, aal2 = true))),
            roles = SqliteRoleStore(db, bootstrapOperatorId = "alice"), // CYP-196: alice is the pinned OPERATOR
            nowMs = { 1_000L },
        
            browserOperatorPostureEnabled = true,
        )
        application {
            install(StatusPages) { exception<ApiException> { call, cause -> call.respond(cause.status) } }
            routing {
                authenticatedApi(deps, AuthRole.OPERATOR) {
                    post("/api/thing") { call.respondText("ok") }
                    get("/api/thing") { call.respondText("ok") }
                }
            }
        }
    }

    private fun cookies(vararg pairs: Pair<String, String>) =
        pairs.joinToString("; ") { "${it.first}=${it.second}" }

    @Test
    fun cookieSession_post_withMatchingDoubleSubmit_is200() = testApplication {
        installGuardedWithCsrf()
        val r = client.post("/api/thing") {
            header(HttpHeaders.Cookie, cookies(KRATOS_SESSION_COOKIE to "sess-op", CSRF_COOKIE to "csrf-abc"))
            header(CSRF_HEADER, "csrf-abc")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun cookieSession_post_withMissingCsrfHeader_is403() = testApplication {
        installGuardedWithCsrf()
        val r = client.post("/api/thing") {
            header(HttpHeaders.Cookie, cookies(KRATOS_SESSION_COOKIE to "sess-op", CSRF_COOKIE to "csrf-abc"))
            // no X-CSRF-Token
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun cookieSession_post_withMismatchedCsrf_is403() = testApplication {
        installGuardedWithCsrf()
        val r = client.post("/api/thing") {
            header(HttpHeaders.Cookie, cookies(KRATOS_SESSION_COOKIE to "sess-op", CSRF_COOKIE to "csrf-abc"))
            header(CSRF_HEADER, "csrf-WRONG")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun bearerOperator_post_withoutAnyCsrf_is200_immune() = testApplication {
        installGuardedWithCsrf()
        // Bearer (no cookie) → CSRF cannot apply; a native/operator-script caller is never asked for a token.
        val r = client.post("/api/thing") { bearerAuth("tok-op") }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun cookieSession_safeGet_withoutCsrf_is200_skipped() = testApplication {
        installGuardedWithCsrf()
        // Safe method: no mutation → no CSRF requirement even with a cookie session.
        val r = client.get("/api/thing") {
            header(HttpHeaders.Cookie, cookies(KRATOS_SESSION_COOKIE to "sess-op"))
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun issuer_setsSameSiteStrictNonHttpOnlyCookie_whenAbsent() = testApplication {
        application {
            install(CsrfCookieIssuer)
            routing { get("/x") { call.respondText("ok") } }
        }
        val r: HttpResponse = client.get("/x")
        val setCookie = r.headers.getAll(HttpHeaders.SetCookie)?.joinToString("\n").orEmpty()
        assertTrue(setCookie.contains(CSRF_COOKIE), "issuer did not set the $CSRF_COOKIE cookie: $setCookie")
        assertTrue(setCookie.contains("SameSite=Strict", ignoreCase = true), "CSRF cookie is not SameSite=Strict: $setCookie")
        assertTrue(!setCookie.contains("HttpOnly", ignoreCase = true), "CSRF cookie must be JS-readable (not HttpOnly): $setCookie")
    }

    // ---- CYP-563 ④ — the EFFECT: the env-gated `secure` flag actually lands on the Set-Cookie attribute ----
    // (Cyp563CsrfSecureTest proves env→decision; these prove decision→cookie-attribute — closing the plumb
    //  install{secure=…} → pluginConfig.secure → Cookie(secure=…) that had zero teeth. Parse the real Set-Cookie
    //  `Secure` attribute — never a substring match, since the base64url cookie VALUE could coincidentally hold "secure".)

    /** The parsed `Secure` attribute of the issued [CSRF_COOKIE], for a plugin installed with [secureFlag]. */
    private fun issuedCookieIsSecure(secureFlag: Boolean): Boolean {
        var isSecure = false
        testApplication {
            application {
                install(CsrfCookieIssuer) { secure = secureFlag }
                routing { get("/x") { call.respondText("ok") } }
            }
            val header = client.get("/x").headers.getAll(HttpHeaders.SetCookie)
                ?.first { it.startsWith("$CSRF_COOKIE=") }
                ?: error("issuer did not set the $CSRF_COOKIE cookie")
            isSecure = parseServerSetCookieHeader(header).secure
        }
        return isSecure
    }

    @Test
    fun issuer_marksCookieSecure_whenConfiguredSecureTrue() {
        // MUT: delete `secure = secure` in the Cookie append (Csrf.kt) → the cookie is never Secure → this reds.
        assertTrue(issuedCookieIsSecure(secureFlag = true), "install{secure=true} → the issued CSRF cookie carries the Secure attribute")
    }

    @Test
    fun issuer_omitsSecure_byDefault_forLocalhostDevHttp() {
        assertFalse(issuedCookieIsSecure(secureFlag = false), "the default (unset) install → NO Secure attribute (localhost dev plain http)")
    }
}
