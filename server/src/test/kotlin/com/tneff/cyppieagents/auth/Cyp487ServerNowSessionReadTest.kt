package com.tneff.cyppieagents.auth

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.ServerNow
import com.tneff.cyppieagents.routing.ApiException
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.serverNowRoutes
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-487 — `GET /api/server-now` must be READ-TIER (session-aware), not token-only. The tokenless SPA (Kratos
 * session cookie, no bearer) got 401 under the old `requireParticipant` — the CYP-320-class cutover landmine, and
 * `/api/server-now` is a browser-session read (CYP-346 client-born timestamping). The fix is `requireCommReader`.
 *
 * Load-bearing tooth: a verified human SESSION → 200 (not 401). Mutation (revert to `requireParticipant`) reds
 * `sessionReader_getsServerNow_200`. The bearer path is preserved; no credential is fail-closed 401.
 */
class Cyp487ServerNowSessionReadTest {

    private val opToken = "tok-op"

    private fun deps(store: SqliteRoleStore) = AuthDeps(
        tokens = TokenRegistry(emptyMap(), operatorToken = opToken, loopbackPosture = true),
        idp = FakeIdentityProvider(mapOf("sess-alice" to ResolvedIdentity("alice-op", verified = true))),
        roles = store, nowMs = { 1_000L },
    )

    private fun ApplicationTestBuilder.app(store: SqliteRoleStore) {
        val d = deps(store)
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) { exception<ApiException> { call, cause -> call.respond(cause.status) } }
            routing { serverNowRoutes(now = { 1_234L }, registry = d.tokens, deps = d) }
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ClientContentNegotiation) { json(CommJson) } }

    @Test
    fun sessionReader_getsServerNow_200_notThe401Bug() = testApplication {
        val db = Files.createTempFile("cyp487", ".db"); val store = SqliteRoleStore(db)
        app(store)
        val res = jsonClient().get("/api/server-now") { header("X-Session-Token", "sess-alice") }
        assertEquals(HttpStatusCode.OK, res.status, "a verified human SESSION must reach server-now (the token-only requireParticipant 401'd it — CYP-487)")
        assertEquals(1_234L, res.body<ServerNow>().serverNowMs, "the injected server clock is returned")
        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun bearerOperator_still200() = testApplication {
        val db = Files.createTempFile("cyp487-bearer", ".db"); val store = SqliteRoleStore(db)
        app(store)
        assertEquals(
            HttpStatusCode.OK,
            jsonClient().get("/api/server-now") { header("Authorization", "Bearer $opToken") }.status,
            "the bearer read is preserved",
        )
        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun noCredential_is401() = testApplication {
        val db = Files.createTempFile("cyp487-anon", ".db"); val store = SqliteRoleStore(db)
        app(store)
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/server-now").status, "no credential → 401 fail-closed")
        store.close(); Files.deleteIfExists(db)
    }
}
