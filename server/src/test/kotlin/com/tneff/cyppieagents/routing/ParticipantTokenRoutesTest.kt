package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.FakeIdentityProvider
import com.tneff.cyppieagents.auth.authenticatedApi
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import com.tneff.cyppieagents.auth.InMemoryRoleStore
import com.tneff.cyppieagents.auth.ParticipantTokenStore
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-234b-3 (#8) — the operator-gated mint/revoke admin path: mint discloses the raw token ONCE, the list is
 * secret-free (subject + expiry, never the token/hash), revoke immediately denies at the shared store, and the
 * whole path is operator-gated (fail-closed for a non-operator).
 */
class ParticipantTokenRoutesTest {

    private val store = ParticipantTokenStore { 1_000L }
    private val reg = TokenRegistry(mapOf("tok-agent" to "backend"), "tok-op")
    private val deps = AuthDeps(
        tokens = reg,
        idp = FakeIdentityProvider(emptyMap()),
        roles = InMemoryRoleStore(),
        nowMs = { 1_000L },
        participantTokens = store,
    )

    private fun app(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(StatusPages) { exception<ApiException> { call, cause -> call.respondText(text = "", status = cause.status) } }
            routing {
                participantTokenRoutes(deps)
                // end-to-end surface: a canRead-scoped read + the MEMBER-tier gate (like GET /api/events).
                // (server routing `get` qualified — the client `get` is imported for the test's requests.)
                get("/read") { call.respondText(call.requireCommReader(deps, reg)) }
                authenticatedApi(deps, AuthRole.MEMBER) { get("/member") { call.respondText("member-ok") } }
            }
        }
        block(client)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.op() = header("Authorization", "Bearer tok-op")

    @Test
    fun mint_discloses_theTokenOnce_andItResolves_thenList_isSecretFree_thenRevoke_denies() = app { client ->
        // mint (operator)
        val mint = client.post("/api/participant-tokens") { op(); contentType(ContentType.Application.Json); setBody("""{"subject":"byo-1"}""") }
        assertEquals(HttpStatusCode.Created, mint.status)
        val body = mint.bodyAsText()
        assertTrue(body.contains("\"token\"") && body.contains("byo-1"), "mint discloses the raw token + subject once")
        val raw = Json.parseToJsonElement(body).let { (it as kotlinx.serialization.json.JsonObject)["token"]!!.let { t -> (t as kotlinx.serialization.json.JsonPrimitive).content } }
        assertEquals("byo-1", store.subjectFor(raw), "the minted token is immediately resolvable at the SHARED store")

        // list is SECRET-FREE — the subject appears, the raw token never does
        val list = client.get("/api/participant-tokens") { op() }.bodyAsText()
        assertTrue(list.contains("byo-1"), "the list shows the subject")
        assertFalse(list.contains(raw), "the list must NEVER contain the raw token")
        assertFalse(list.contains("token"), "the summary carries no token field at all")

        // revoke by subject → immediate deny at the shared store
        val del = client.delete("/api/participant-tokens?subject=byo-1") { op() }
        assertEquals(HttpStatusCode.OK, del.status)
        assertEquals(null, store.subjectFor(raw), "revoked → the token no longer resolves")
    }

    @Test
    fun mintedToken_endToEnd_readsViaCanRead_but401sAtMemberGate() = app { client ->
        // #8 mint + ①-fix TOGETHER, end-to-end: mint a REAL token via the operator admin path…
        val mint = client.post("/api/participant-tokens") { op(); contentType(ContentType.Application.Json); setBody("""{"subject":"byo-e2e"}""") }
        assertEquals(HttpStatusCode.Created, mint.status)
        val raw = Json.parseToJsonElement(mint.bodyAsText()).let { (it as kotlinx.serialization.json.JsonObject)["token"]!!.let { t -> (t as kotlinx.serialization.json.JsonPrimitive).content } }
        // …it resolves as a read-SUBJECT through the canRead resolver…
        val read = client.get("/read") { header("Authorization", "Bearer $raw") }
        assertEquals(HttpStatusCode.OK, read.status)
        assertEquals("byo-e2e", read.bodyAsText(), "the freshly minted token is a first-class read-subject")
        // …but is DENIED at the MEMBER-tier gate (the ①-fix: it never satisfies MEMBER / reads /api/events).
        assertEquals(HttpStatusCode.Unauthorized, client.get("/member") { header("Authorization", "Bearer $raw") }.status, "a minted participant token must 401 at the MEMBER gate (①-fix + mint, end-to-end)")
    }

    @Test
    fun theAdminPath_isOperatorGated_failClosed() = app { client ->
        // no credential
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/participant-tokens").status)
        // a mere agent token is NOT operator → denied
        val asAgent = client.get("/api/participant-tokens") { header("Authorization", "Bearer tok-agent") }
        assertTrue(asAgent.status == HttpStatusCode.Unauthorized || asAgent.status == HttpStatusCode.Forbidden, "an agent token must not reach the operator mint/revoke path (was ${asAgent.status})")
    }
}
