package com.tneff.cyppieagents.auth

import com.tneff.cyppieagents.routing.ForbiddenException
import io.ktor.http.Cookie
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.header
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** CYP-178 / RC5 — the platform's double-submit CSRF cookie (readable by JS) + the header the SPA echoes. */
const val CSRF_COOKIE = "cyppie_csrf"
const val CSRF_HEADER = "X-CSRF-Token"

private val UNSAFE_METHODS = setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete, HttpMethod.Patch)

/**
 * CYP-178 / RC5 — **platform-owned CSRF** for our business routes (Kratos protects only its own flows).
 * Defends the ONLY browser-exploitable surface: a **cookie-authenticated, state-changing** request. The
 * classic **double-submit** — a random token in the [CSRF_COOKIE] (JS-readable, so the SPA can echo it in
 * the [CSRF_HEADER]); a cross-site attacker's page auto-sends the session + CSRF cookies but, bound by the
 * same-origin policy, can neither READ the CSRF cookie to set the header nor set a custom header
 * cross-origin — so the two never match and the forged request is a **403**.
 *
 * Enforced ONLY when it can bite: an unsafe method **with the Kratos session cookie present**. A
 * bearer-token / `X-Session-Token` caller (native app, operator script) carries no cookie and is immune,
 * so it is never asked for a CSRF token. Fail-closed: a missing/blank/mismatched header → 403 `csrf_failed`,
 * before the guarded handler runs.
 */
fun ApplicationCall.enforceCsrf() {
    if (request.httpMethod !in UNSAFE_METHODS) return // safe methods (GET/HEAD/OPTIONS) don't mutate → no CSRF risk
    val sessionCookie = request.cookies[KRATOS_SESSION_COOKIE] ?: return // no cookie → not a browser-cookie call → immune
    if (sessionCookie.isBlank()) return
    val headerToken = request.header(CSRF_HEADER)
    val cookieToken = request.cookies[CSRF_COOKIE]
    if (headerToken.isNullOrBlank() || cookieToken.isNullOrBlank() || !constantTimeEquals(headerToken, cookieToken)) {
        throw ForbiddenException("CSRF token missing or invalid", code = "csrf_failed")
    }
}

/** Constant-time string compare (no early-out on the first differing byte) — no token-length/content oracle. */
private fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

/**
 * CYP-178 / RC5 — issues the double-submit [CSRF_COOKIE] when absent, so the SPA has a token to echo. It is
 * **deliberately NOT httpOnly** (JS must read it for the header) and **SameSite=Strict** (a cross-site
 * request never even sends it, so a forged POST also lacks the cookie the double-submit compares against —
 * defense-in-depth over the header check). 128-bit `SecureRandom`, base64url. The value is opaque and
 * stateless (the security is the same-origin read/set barrier, not server-side storage), so re-issuing a
 * fresh one when absent is safe. `secure` is left to the deployment/CORS story (localhost dev is plain http).
 */
val CsrfCookieIssuer = createApplicationPlugin("CsrfCookieIssuer") {
    onCall { call ->
        if (call.request.cookies[CSRF_COOKIE].isNullOrBlank()) {
            call.response.cookies.append(
                Cookie(
                    name = CSRF_COOKIE,
                    value = secureCsrfToken(),
                    path = "/",
                    httpOnly = false, // the SPA must read it to echo in X-CSRF-Token
                    extensions = mapOf("SameSite" to "Strict"),
                ),
            )
        }
    }
}

private fun secureCsrfToken(): String {
    val bytes = ByteArray(16) // 128-bit
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
