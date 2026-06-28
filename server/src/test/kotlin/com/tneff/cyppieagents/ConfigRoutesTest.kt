package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.ProjectConfigStore
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.ApiKeyRequest
import com.tneff.cyppieagents.model.ApiKeyView
import com.tneff.cyppieagents.model.RepoConfigRequest
import com.tneff.cyppieagents.model.RepoConfigView
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.ApiException
import com.tneff.cyppieagents.routing.configRoutes
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HTTP-level contract for the project-settings config endpoints (S15 / CYP-96). Reviewer merge-gate:
 * operator-only writes (fail-closed), GET is participant + masked-only (the plaintext key never
 * leaves the server), validation 400s.
 */
class ConfigRoutesTest {

    private val secret = "sk-ant-supersecret-99ZX"
    private val masked = "***99ZX"

    private fun store() = ProjectConfigStore(
        file = null, // in-memory
        fallbackRepo = RepoConfig("git@github.com:org/repo.git", "main"),
        secrets = Secrets(mapOf("tok-fe" to "frontend"), operatorToken = "tok-op", apiKey = null),
    )

    private fun io.ktor.server.testing.ApplicationTestBuilder.app(store: ProjectConfigStore) {
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) {
                exception<ApiException> { call, cause -> call.respond(cause.status, ApiErrorBody(com.tneff.cyppieagents.model.ApiError(cause.code, cause.message))) }
            }
            routing { configRoutes(store, TokenRegistry(mapOf("tok-fe" to "frontend"), "tok-op")) { "default" } }
        }
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.jsonClient() =
        createClient { install(ClientContentNegotiation) { json(CommJson) } }

    // ---- operator gating (fail-closed) ----

    @Test
    fun putApiKey_noToken_401() = testApplication {
        app(store()); val c = jsonClient()
        val r = c.put("/api/config/apikey") { contentType(ContentType.Application.Json); setBody(ApiKeyRequest(secret)) }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun putApiKey_participant_403_operatorRequired() = testApplication {
        app(store()); val c = jsonClient()
        val r = c.put("/api/config/apikey") { bearerAuth("tok-fe"); contentType(ContentType.Application.Json); setBody(ApiKeyRequest(secret)) }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals("operator_required", r.body<ApiErrorBody>().error.code)
    }

    @Test
    fun putRepo_participant_403() = testApplication {
        app(store()); val c = jsonClient()
        val r = c.put("/api/config/repo") { bearerAuth("tok-fe"); contentType(ContentType.Application.Json); setBody(RepoConfigRequest("git@github.com:o/r.git", "dev")) }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals("operator_required", r.body<ApiErrorBody>().error.code)
    }

    // ---- needle-absence: the plaintext key never leaves the server ----

    @Test
    fun apiKey_roundTrip_neverReturnsPlaintext() = testApplication {
        val s = store()
        app(s); val c = jsonClient()

        // PUT (operator) stores it; response is masked-only.
        val putResp = c.put("/api/config/apikey") { bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(ApiKeyRequest(secret)) }
        assertEquals(HttpStatusCode.OK, putResp.status)
        val putText = putResp.bodyAsText()
        assertFalse(putText.contains("supersecret"), "PUT response must not echo the plaintext key")
        assertEquals(ApiKeyView(set = true, masked = masked), putResp.body<ApiKeyView>())

        // GET (participant) sees only { set, masked } — never the plaintext.
        val getResp = c.get("/api/config/apikey") { bearerAuth("tok-fe") }
        assertEquals(HttpStatusCode.OK, getResp.status)
        val getText = getResp.bodyAsText()
        assertFalse(getText.contains("supersecret"), "GET response must never contain the plaintext key")
        assertFalse(getText.contains("99ZX") && getText.contains("sk-ant"), "GET carries no full key")
        assertEquals(ApiKeyView(set = true, masked = masked), getResp.body<ApiKeyView>())

        // …but the store DID persist the real key (it is resolvable for the spawn ENV).
        assertEquals(secret, s.resolvedApiKey("default"))
    }

    @Test
    fun getApiKey_unset_setFalse() = testApplication {
        app(store()); val c = jsonClient()
        val v = c.get("/api/config/apikey") { bearerAuth("tok-fe") }.body<ApiKeyView>()
        assertEquals(false, v.set)
        assertNull(v.masked)
    }

    @Test
    fun getApiKey_noToken_401() = testApplication {
        app(store()); val c = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, c.get("/api/config/apikey").status)
    }

    // ---- validation ----

    @Test
    fun putApiKey_blank_400_invalidApiKey() = testApplication {
        app(store()); val c = jsonClient()
        val r = c.put("/api/config/apikey") { bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(ApiKeyRequest("   ")) }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals("invalid_api_key", r.body<ApiErrorBody>().error.code)
    }

    @Test
    fun putRepo_invalidUrl_400_invalidRepoUrl() = testApplication {
        app(store()); val c = jsonClient()
        val r = c.put("/api/config/repo") { bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(RepoConfigRequest("notaurl", "main")) }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals("invalid_repo_url", r.body<ApiErrorBody>().error.code)
    }

    // ---- repo happy path ----

    @Test
    fun repo_putThenGet_operatorAndParticipant() = testApplication {
        val s = store()
        app(s); val c = jsonClient()
        val put = c.put("/api/config/repo") { bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(RepoConfigRequest("https://github.com/o/r.git", "dev")) }
        assertEquals(RepoConfigView(configured = true, url = "https://github.com/o/r.git", branch = "dev"), put.body<RepoConfigView>())
        // participant can read it back (URL is not secret)
        val get = c.get("/api/config/repo") { bearerAuth("tok-fe") }.body<RepoConfigView>()
        assertEquals(RepoConfigView(configured = true, url = "https://github.com/o/r.git", branch = "dev"), get)
    }

    @Test
    fun getRepo_fallsBackToBootConfig_whenNoOverride() = testApplication {
        app(store()); val c = jsonClient()
        val v = c.get("/api/config/repo") { bearerAuth("tok-fe") }.body<RepoConfigView>()
        assertTrue(v.configured)
        assertEquals("git@github.com:org/repo.git", v.url)
    }
}
