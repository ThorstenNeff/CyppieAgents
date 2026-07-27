package com.tneff.cyppieagents

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.crypto.MasterKeySource
import com.tneff.cyppieagents.crypto.SecretCipherFactory
import com.tneff.cyppieagents.db.DsnDescriptor
import com.tneff.cyppieagents.db.DsnRegistry
import com.tneff.cyppieagents.db.DsnTierOrigin
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.routing.ApiException
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.adminMetricsRoutes
import com.tneff.cyppieagents.tier.DbMetricsProvider
import com.tneff.cyppieagents.tier.DsnRegistryMetricsProvider
import com.tneff.cyppieagents.tier.PoolStat
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
 * CYP-220 Phase 5 — `GET /api/admin/db/metrics`: operator-gated (fail-closed) + **content-free / no secrets**
 * (dsnIds + masked host + numbers only; never a DSN password or full host).
 */
class AdminMetricsRoutesTest {

    private fun ApplicationTestBuilder.app(provider: DbMetricsProvider) {
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) {
                exception<ApiException> { call, cause -> call.respond(cause.status, ApiErrorBody(com.tneff.cyppieagents.model.ApiError(cause.code, cause.message))) }
            }
            routing { adminMetricsRoutes(provider, AuthDeps(TokenRegistry(mapOf("tok-fe" to "frontend"), "tok-op", loopbackPosture = true))) }
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ClientContentNegotiation) { json(CommJson) } }

    /** A provider over a DsnRegistry that holds a real DSN password — to prove the snapshot never leaks it. */
    private fun secretBearingProvider(): DbMetricsProvider {
        val cipher = SecretCipherFactory.single(1, MasterKeySource.Box(SecretCipherFactory.newBoxKeyset()))
        val dsns = DsnRegistry(null, cipher).apply {
            put(DsnDescriptor("pg1", "prod", "prod-db.aiven.example", 5432, "app", "appuser", tierOrigin = DsnTierOrigin.AIVEN_MANAGED, createdBy = "op", createdAt = 1L), "SUPER-SECRET-DSN-PW")
        }
        return DsnRegistryMetricsProvider(dsns, poolStatsOf = { PoolStat(2, 5) }, dbSizeOf = { 12_345L })
    }

    @Test fun metrics_noToken_401() = testApplication {
        app(secretBearingProvider())
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/admin/db/metrics").status)
    }

    @Test fun metrics_agentToken_403_operatorRequired() = testApplication {
        app(secretBearingProvider())
        val r = jsonClient().get("/api/admin/db/metrics") { bearerAuth("tok-fe") }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals("operator_required", r.body<ApiErrorBody>().error.code)
    }

    @Test fun metrics_operator_returnsSnapshot_withoutSecrets() = testApplication {
        app(secretBearingProvider())
        val r = jsonClient().get("/api/admin/db/metrics") { bearerAuth("tok-op") }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertFalse(body.contains("SUPER-SECRET-DSN-PW"), "the DSN password must NEVER appear in metrics")
        assertFalse(body.contains("prod-db.aiven.example"), "the full host is masked")
        assertTrue(body.contains("pg1"), "the dsnId (non-secret reference) is present")
        assertTrue(body.contains("AIVEN_MANAGED"))
        assertTrue(body.contains("pr***mple"), "masked host present") // maskHost("prod-db.aiven.example")
    }
}
