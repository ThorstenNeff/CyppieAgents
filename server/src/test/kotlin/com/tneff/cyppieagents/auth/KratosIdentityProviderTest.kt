package com.tneff.cyppieagents.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-178 / P1 — [KratosIdentityProvider] whoami parsing + **RC1 fail-closed**, and the **real-path
 * dual-header regression** (the bug that FakeIdentityProvider never exercised).
 *
 * The fake `/sessions/whoami` models Kratos v1.3.0's matrix: a request carrying **BOTH** `X-Session-Token`
 * and the `ory_kratos_session` cookie is **poisoned → 500** (native+cookie) / rejected — so a provider that
 * blindly sends both fails on every real session. Testing BOTH the native (HEADER) and browser (COOKIE)
 * paths against this server proves the provider sends **only the one** header its source dictates: if it sent
 * both, these would 500 → null → red. (Live enum/timing is the deploy probe, not this hermetic test.)
 */
class KratosIdentityProviderTest {

    private val server = embeddedServer(Netty, port = 0) {
        install(WebSockets)
        routing {
            get("/sessions/whoami") {
                val token = call.request.header("X-Session-Token")
                val cookie = call.request.cookies["ory_kratos_session"]
                // BOTH present → poisoned (the real-path bug). A correct provider never triggers this.
                if (token != null && cookie != null) {
                    call.respondText("both-headers-poisoned", status = HttpStatusCode.InternalServerError); return@get
                }
                val body = when (token ?: cookie) {
                    "verified" -> """{"active":true,"identity":{"id":"alice","verifiable_addresses":[{"verified":true}]}}"""
                    "unverified" -> """{"active":true,"identity":{"id":"bob","verifiable_addresses":[{"verified":false}]}}"""
                    "inactive" -> """{"active":false,"identity":{"id":"x"}}"""
                    "malformed" -> """not json at all"""
                    else -> null
                }
                if (body == null) call.respondText("", status = HttpStatusCode.Unauthorized)
                else call.respondText(body)
            }
        }
    }.start(wait = false)
    private val port = runBlocking { server.engine.resolvedConnectors().first().port }
    private val idp = KratosIdentityProvider("http://localhost:$port/sessions/whoami")

    private fun native(v: String) = SessionCredential(v, SessionCredential.Source.HEADER)
    private fun browser(v: String) = SessionCredential(v, SessionCredential.Source.COOKIE)

    @AfterTest fun tearDown() = server.stop(100, 100)

    // ⭐ regression: each path succeeds ONLY because the provider sends a single header (the server 500s on both).
    @Test fun nativeToken_headerOnly_mapsToVerifiedIdentity() = runBlocking {
        assertEquals(ResolvedIdentity("alice", verified = true), idp.resolve(native("verified")))
    }

    @Test fun browserCookie_cookieOnly_mapsToVerifiedIdentity() = runBlocking {
        assertEquals(ResolvedIdentity("alice", verified = true), idp.resolve(browser("verified")))
    }

    @Test fun activeUnverified_mapsToUnverified() = runBlocking {
        assertEquals(ResolvedIdentity("bob", verified = false), idp.resolve(native("unverified")))
        assertEquals(ResolvedIdentity("bob", verified = false), idp.resolve(browser("unverified")))
    }

    @Test fun inactiveSession_isNull() = runBlocking { assertNull(idp.resolve(native("inactive"))) }

    @Test fun unauthorized401_isNull_failClosed() = runBlocking { assertNull(idp.resolve(native("whatever-unknown"))) }

    @Test fun malformedBody_isNull_failClosed() = runBlocking { assertNull(idp.resolve(native("malformed"))) }

    @Test fun blankOrNullCredential_isNull_noCall() = runBlocking {
        assertNull(idp.resolve(null)); assertNull(idp.resolve(native("  ")))
    }
}
