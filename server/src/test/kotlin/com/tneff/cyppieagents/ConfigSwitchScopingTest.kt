package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.ProjectConfigStore
import com.tneff.cyppieagents.boot.ProjectRegistry
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.ApiKeyRequest
import com.tneff.cyppieagents.model.ApiKeyView
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.ApiException
import com.tneff.cyppieagents.routing.configRoutes
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-103 (S13 defect) — the `/api/config` endpoints must FOLLOW the active-project switch, like `/api/events`
 * (CYP-102). Root cause was a by-value `activeProjectId: String` bound to the boot project; after
 * `POST /api/projects/switch` the operator kept reading/writing the BOOT project's repo + API key at
 * rest (credential mis-scoping, Doc 05 D3). The fix binds `configRoutes` to the LIVE registry resolver.
 *
 * Driven through the resolver exactly as production wires it (`registry::activeProjectId`): a switch to
 * B must move config reads/writes to B, leaving A untouched. Reverting the binding to a by-value
 * snapshot (the old bug) reddens this test.
 */
class ConfigSwitchScopingTest {

    private val tokens = TokenRegistry(mapOf("tok-fe" to "frontend"), operatorToken = "tok-op")

    private fun store() = ProjectConfigStore(
        file = null,
        fallbackRepo = RepoConfig("git@github.com:org/repo.git", "main"),
        secrets = Secrets(mapOf("tok-fe" to "frontend"), operatorToken = "tok-op", apiKey = null),
    )

    private fun registry() = ProjectRegistry(file = null, seedProjectId = "alpha", seedProjectName = "Alpha")
        .apply { create(CreateProjectRequest("beta", "Beta")) }

    private fun ApplicationTestBuilder.app(store: ProjectConfigStore, reg: ProjectRegistry) {
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) {
                exception<ApiException> { call, cause ->
                    call.respond(cause.status, ApiErrorBody(com.tneff.cyppieagents.model.ApiError(cause.code, cause.message)))
                }
            }
            // The LIVE resolver, exactly as PlatformWiring binds it (CYP-103 fix).
            routing { configRoutes(store, tokens, reg::activeProjectId) }
        }
    }

    private fun ApplicationTestBuilder.jsonClient() =
        createClient { install(ClientContentNegotiation) { json(CommJson) } }

    @Test
    fun configFollowsProjectSwitch_writesLandOnActive_otherUntouched() = testApplication {
        val store = store(); val reg = registry(); app(store, reg)
        val c = jsonClient()

        // active = alpha → set alpha's key
        c.put("/api/config/apikey") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(ApiKeyRequest("alpha-secret-AAAA"))
        }
        assertEquals("***AAAA", c.get("/api/config/apikey") { bearerAuth("tok-op") }.body<ApiKeyView>().masked)

        // switch to beta — the operator action that the by-value binding ignored
        reg.setActive("beta")

        // GET now resolves BETA (which has no key yet) — proves config followed the switch, not alpha
        val betaView = c.get("/api/config/apikey") { bearerAuth("tok-op") }.body<ApiKeyView>()
        assertFalse(betaView.set, "after switch, /api/config/apikey shows the ACTIVE (beta) project, not alpha")

        // PUT after the switch lands on BETA
        c.put("/api/config/apikey") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(ApiKeyRequest("beta-secret-BBBB"))
        }
        // beta got the new key; alpha's key at rest is UNTOUCHED (no cross-project clobber)
        assertEquals("***BBBB", store.apiKeyView("beta").masked, "PUT after switch lands on beta")
        assertEquals("***AAAA", store.apiKeyView("alpha").masked, "alpha's key at rest is untouched by the beta write")
    }

    @Test
    fun repoConfigFollowsSwitch() = testApplication {
        val store = store(); val reg = registry(); app(store, reg)
        val c = jsonClient()
        // alpha gets an override repo
        c.put("/api/config/repo") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json)
            setBody(com.tneff.cyppieagents.model.RepoConfigRequest("git@github.com:org/alpha.git", "main"))
        }
        reg.setActive("beta")
        // beta has no override → falls back to the boot repo, NOT alpha's override
        val betaRepo = c.get("/api/config/repo") { bearerAuth("tok-op") }.body<com.tneff.cyppieagents.model.RepoConfigView>()
        assertTrue(betaRepo.url?.endsWith("repo.git") == true, "after switch, repo resolves beta (boot fallback), not alpha's override: ${betaRepo.url}")
    }
}
