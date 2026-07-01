package com.tneff.cyppieagents.auth

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AuthMe
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.authMeRoutes
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-182 / P3 — `GET /api/auth/me` reports the four auth states content-free, and never 401s (it is the
 * public whoami the client uses instead of inferring state from guarded-route status codes).
 */
class AuthMeRoutesTest {

    private fun ApplicationTestBuilder.installMe(): SqliteRoleStore {
        val store = SqliteRoleStore(Files.createTempFile("me-roles", ".db"))
        val deps = AuthDeps(
            tokens = TokenRegistry(emptyMap(), operatorToken = "tok-op"),
            idp = FakeIdentityProvider(
                mapOf(
                    "sess-verified" to ResolvedIdentity("alice", verified = true),
                    "sess-unverified" to ResolvedIdentity("bob", verified = false),
                ),
            ),
            roles = store,
            nowMs = { 1L },
        )
        application {
            install(ContentNegotiation) { json(CommJson) }
            routing { authMeRoutes(deps) }
        }
        return store
    }

    private suspend fun ApplicationTestBuilder.me(block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): Pair<HttpStatusCode, AuthMe> {
        val r = client.get("/api/auth/me", block)
        return r.status to CommJson.decodeFromString(AuthMe.serializer(), r.bodyAsText())
    }

    @Test
    fun noCredential_is200_authenticatedFalse() = testApplication {
        val store = installMe()
        val (status, me) = me()
        assertEquals(HttpStatusCode.OK, status) // NEVER 401 — it reports state, it does not gate
        assertEquals(AuthMe(authenticated = false, role = null, verified = false), me)
        store.close()
    }

    @Test
    fun operatorToken_isAuthenticatedOperatorVerified() = testApplication {
        val store = installMe()
        val (_, me) = me { bearerAuth("tok-op") }
        assertEquals(AuthMe(authenticated = true, role = "OPERATOR", verified = true), me)
        store.close()
    }

    @Test
    fun verifiedSession_isAuthenticatedWithRoleAndVerified() = testApplication {
        val store = installMe()
        val (_, me) = me { header("X-Session-Token", "sess-verified") }
        assertEquals(true, me.authenticated)
        assertEquals(true, me.verified)
        assertEquals("OPERATOR", me.role) // the first verified identity bootstraps OPERATOR (Middleway)
        store.close()
    }

    @Test
    fun unverifiedSession_isAuthenticatedButUnverifiedNoRole() = testApplication {
        val store = installMe()
        val (status, me) = me { header("X-Session-Token", "sess-unverified") }
        assertEquals(HttpStatusCode.OK, status)
        // Logged-in but not verified → the client shows "verify your email"; guarded routes still 401.
        assertEquals(AuthMe(authenticated = true, role = null, verified = false), me)
        store.close()
    }
}
