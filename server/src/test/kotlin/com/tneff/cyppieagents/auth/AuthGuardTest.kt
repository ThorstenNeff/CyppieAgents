package com.tneff.cyppieagents.auth

import com.tneff.cyppieagents.routing.ApiException
import com.tneff.cyppieagents.routing.TokenRegistry
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-178 / P1 — RC1 guard-logic (hermetic, `FakeIdentityProvider`). The structural `authenticatedApi`
 * group enforces exactly-one fail-closed auth: operator token OR a **verified** Kratos session. Session-
 * valid-but-unverified and invalid both 401; a MEMBER human on an OPERATOR route is 403. The live
 * enumeration/timing behaviour is the deploy-coordinated real-path probe (RC2 option b).
 */
class AuthGuardTest {

    private val db = Files.createTempFile("guard-roles", ".db")
    private fun roles() = SqliteRoleStore(db, bootstrapOperatorId = "alice") // CYP-196: alice is the pinned OPERATOR

    private fun ApplicationTestBuilder.installGuarded(store: SqliteRoleStore) {
        val deps = AuthDeps(
            tokens = TokenRegistry(emptyMap(), operatorToken = "tok-op"),
            idp = FakeIdentityProvider(
                mapOf(
                    "sess-alice" to ResolvedIdentity("alice", verified = true),
                    "sess-bob-unverified" to ResolvedIdentity("bob", verified = false),
                    "sess-carol" to ResolvedIdentity("carol", verified = true),
                ),
            ),
            roles = store,
            nowMs = { 1_000L },
        )
        application {
            install(StatusPages) {
                // status-only (no ContentNegotiation in this focused test) — we assert the code, not the body.
                exception<ApiException> { call, cause -> call.respond(cause.status) }
            }
            routing { authenticatedApi(deps, AuthRole.OPERATOR) { get("/api/secret") { call.respondText("ok") } } }
        }
    }

    @Test
    fun noCredential_is401() = testApplication {
        val store = roles(); installGuarded(store)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/secret").status)
        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun operatorToken_is200_machinePrincipal() = testApplication {
        val store = roles(); installGuarded(store)
        assertEquals(HttpStatusCode.OK, client.get("/api/secret") { bearerAuth("tok-op") }.status)
        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun verifiedSession_is200_pinnedBootstrapOperator() = testApplication {
        val store = roles(); installGuarded(store) // CYP-196: alice is the PINNED OPERATOR (not first-verified)
        val r = client.get("/api/secret") { header("X-Session-Token", "sess-alice") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("ok", r.bodyAsText())
        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun unverifiedSession_is401_verifiedRequired() = testApplication {
        val store = roles(); installGuarded(store)
        // RC1: session-VALID but NOT verified → still unauthenticated.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/secret") { header("X-Session-Token", "sess-bob-unverified") }.status)
        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun invalidSession_is401() = testApplication {
        val store = roles(); installGuarded(store)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/secret") { header("X-Session-Token", "nope") }.status)
        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun memberHuman_onOperatorRoute_is403() = testApplication {
        val store = roles(); installGuarded(store)
        // alice is the pinned OPERATOR; carol (any other verified identity) is MEMBER → 403 on an OPERATOR route.
        assertEquals(HttpStatusCode.OK, client.get("/api/secret") { header("X-Session-Token", "sess-alice") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/secret") { header("X-Session-Token", "sess-carol") }.status)
        store.close(); Files.deleteIfExists(db)
    }
}
