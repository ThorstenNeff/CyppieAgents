package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.contract.ContractGenerator
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-234a-3 — the hosted-docs gate: single-source (served == generated, no artifact), Bearer-only hosted spec
 * (PO-Assistant exposure-audit), served-spec-valid, smoke, and fail-closed auth.
 */
class DocsRoutesTest {

    private val reg = TokenRegistry(mapOf("tok-fe" to "frontend"), "tok-op")
    private fun io.ktor.client.request.HttpRequestBuilder.op() = header("Authorization", "Bearer tok-op")

    private fun app(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application {
            // The real platform installs StatusPages (ApiException→status); mirror the minimal mapping so a
            // fail-closed UnauthorizedException surfaces as 401 (not a bare 500) in this focused harness.
            install(io.ktor.server.plugins.statuspages.StatusPages) {
                exception<ApiException> { call, cause -> call.respond(cause.status) }
            }
            routing { docsRoutes(AuthDeps(reg), reg) }
        }
        block(client)
    }

    // ---- Bearer-only (unit, no server) ----

    @Test
    fun hostedOpenApi_isBearerOnly_butTheRawContractKeepsBoth() {
        val hostedSchemes = (ContractGenerator.hostedOpenApi()["components"] as JsonObject)["securitySchemes"] as JsonObject
        assertTrue("bearerAuth" in hostedSchemes, "hosted keeps bearer")
        assertFalse("sessionCookie" in hostedSchemes, "hosted OMITS the ory_kratos_session cookie scheme (no IdP fingerprinting)")
        // no per-op security references the cookie scheme either
        val paths = ContractGenerator.hostedOpenApi()["paths"] as JsonObject
        for ((_, item) in paths) for ((_, op) in item as JsonObject) {
            val sec = (op as JsonObject)["security"] as? JsonArray ?: continue
            assertTrue(sec.none { "sessionCookie" in (it as JsonObject) }, "no hosted op may reference sessionCookie")
        }
        // the RAW /api contract still documents BOTH paths (unchanged)
        val rawSchemes = (ContractGenerator.openApi()["components"] as JsonObject)["securitySchemes"] as JsonObject
        assertTrue("bearerAuth" in rawSchemes && "sessionCookie" in rawSchemes, "the /api contract keeps both auth paths")
    }

    // ---- single-source: served == generated (no checked-in artifact can drift) ----

    @Test
    fun servedSpecs_areByteIdentical_toTheGenerators() = app { client ->
        assertEquals(docsJson(ContractGenerator.hostedOpenApi()), client.get("/docs/openapi.json") { op() }.bodyAsText(), "openapi.json must be the generator output verbatim")
        assertEquals(docsJson(ContractGenerator.asyncApi()), client.get("/docs/asyncapi.json") { op() }.bodyAsText(), "asyncapi.json must be the generator output verbatim")
    }

    // ---- served-spec-valid: parses + has the expected structure ----

    @Test
    fun servedOpenApi_isValid_withPathsAndComponents() = app { client ->
        val body = client.get("/docs/openapi.json") { op() }.bodyAsText()
        val json = kotlinx.serialization.json.Json.parseToJsonElement(body) as JsonObject
        assertTrue((json["paths"] as JsonObject).isNotEmpty(), "served openapi has paths")
        assertTrue(((json["components"] as JsonObject)["schemas"] as JsonObject).containsKey("ApiErrorBody"), "served openapi has the error envelope component")
    }

    // ---- smoke ----

    @Test
    fun docsPage_rendersMaritimeShell_withTheAdditiveMount() = app { client ->
        val resp = client.get("/docs") { op() }
        assertEquals(HttpStatusCode.OK, resp.status)
        val html = resp.bodyAsText()
        assertTrue(html.contains("mar-header"), "the maritime chrome is served")
        assertTrue(html.contains("id=\"rest-render\"") && html.contains("id=\"ws-render\""), "the render slots are present")
        assertTrue(html.contains("Redoc.init('/docs/openapi.json'"), "the additive REST render mount is injected")
        assertTrue(html.contains("/docs/asyncapi.json"), "the additive WS render mount is injected")
    }

    // ---- fail-closed auth (the ratified authenticated-default) ----

    @Test
    fun everyDocsRoute_failsClosed_withoutCredential() = app { client ->
        for (path in listOf("/docs", "/docs/rest", "/docs/ws", "/docs/openapi.json", "/docs/asyncapi.json")) {
            assertEquals(HttpStatusCode.Unauthorized, client.get(path).status, "$path must fail closed with no credential")
        }
    }
}
