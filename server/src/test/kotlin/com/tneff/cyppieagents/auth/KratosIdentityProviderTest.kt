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
 * CYP-178 / P1 — [KratosIdentityProvider] whoami parsing + **RC1 fail-closed**. A fake `/sessions/whoami`
 * server replies per the forwarded token; the provider maps active+verified correctly and returns null on
 * every failure mode (inactive, 401, malformed, unknown) — the guard treats null as unauthenticated. (The
 * live enumeration/timing behaviour is the deploy-coordinated probe, not this hermetic test.)
 */
class KratosIdentityProviderTest {

    private val server = embeddedServer(Netty, port = 0) {
        install(WebSockets)
        routing {
            get("/sessions/whoami") {
                val body = when (call.request.header("X-Session-Token")) {
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

    @AfterTest fun tearDown() = server.stop(100, 100)

    @Test fun activeVerified_mapsToVerifiedIdentity() = runBlocking {
        assertEquals(ResolvedIdentity("alice", verified = true), idp.resolve("verified"))
    }

    @Test fun activeUnverified_mapsToUnverified() = runBlocking {
        assertEquals(ResolvedIdentity("bob", verified = false), idp.resolve("unverified"))
    }

    @Test fun inactiveSession_isNull() = runBlocking { assertNull(idp.resolve("inactive")) }

    @Test fun unauthorized401_isNull_failClosed() = runBlocking { assertNull(idp.resolve("whatever-unknown")) }

    @Test fun malformedBody_isNull_failClosed() = runBlocking { assertNull(idp.resolve("malformed")) }

    @Test fun blankOrNullCredential_isNull_noCall() = runBlocking {
        assertNull(idp.resolve(null)); assertNull(idp.resolve("  "))
    }
}
