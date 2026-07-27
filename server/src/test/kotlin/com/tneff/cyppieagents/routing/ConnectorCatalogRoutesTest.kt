package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.boot.ConnectorRouter
import com.tneff.cyppieagents.model.ApiError
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.ConnectorsView
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
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

/**
 * CYP-462 — `GET /api/connectors`: the connector catalog is a PARTICIPANT read (member-reachable; unauth → 401),
 * lists EVERY [ConnectorKind] with its DECLARED capabilities, and is SINGLE-SOURCED from
 * [ConnectorRouter.capabilitiesForKind] (the same mapping the spawn/opt-in path uses → no drift). The single-source
 * assertion is the load-bearing tooth: a future parallel hardcode of the caps would diverge from
 * `capabilitiesForKind` and red this.
 */
class ConnectorCatalogRoutesTest {

    private val reg = TokenRegistry(mapOf("tok-be" to "backend"), operatorToken = "tok-op", loopbackPosture = true)

    private fun ApplicationTestBuilder.app() {
        val deps = AuthDeps(reg)
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) {
                exception<ApiException> { call, cause -> call.respond(cause.status, ApiErrorBody(ApiError(cause.code, cause.message))) }
            }
            routing { connectorCatalogRoutes(reg, deps) }
        }
    }

    private fun ApplicationTestBuilder.httpClient() = createClient { install(ClientContentNegotiation) { json(CommJson) } }

    @Test
    fun unauthenticated_is401() = testApplication {
        app()
        assertEquals(HttpStatusCode.Unauthorized, httpClient().get("/api/connectors").status, "PARTICIPANT-gated: an unauthenticated read is 401")
    }

    @Test
    fun listsEveryKind_withDeclaredCaps_singleSourced() = testApplication {
        app()
        val res = httpClient().get("/api/connectors") { bearerAuth("tok-be") } // an agent token is a valid participant
        assertEquals(HttpStatusCode.OK, res.status)
        val view: ConnectorsView = res.body()

        // covers EVERY kind, exactly once
        assertEquals(ConnectorKind.entries.toSet(), view.connectors.map { it.kind }.toSet(), "the catalog lists every ConnectorKind")
        assertEquals(ConnectorKind.entries.size, view.connectors.size, "no duplicate/missing kinds")
        assertEquals(ConnectorKind.STREAM_JSON, view.default, "STREAM_JSON is the pre-selected default")

        // SINGLE-SOURCE: each descriptor's caps == the spawn/opt-in path's mapping (mutation: hardcode caps → diverges → reds)
        for (d in view.connectors) {
            assertEquals(ConnectorRouter.capabilitiesForKind(d.kind), d.capabilities, "caps for ${d.kind} must be single-sourced from ConnectorRouter.capabilitiesForKind")
        }

        // spot-check the fidelity contract the picker previews: A = full, B = degraded on the token dimension
        val streamJson = view.connectors.first { it.kind == ConnectorKind.STREAM_JSON }.capabilities
        val mcp = view.connectors.first { it.kind == ConnectorKind.MCP }.capabilities
        assertEquals(CapabilityStatus.AVAILABLE, streamJson.structuredUsage, "Connector A (stream-json) = full fidelity")
        assertEquals(CapabilityStatus.UNAVAILABLE, mcp.structuredUsage, "Connector B (MCP) degrades per-turn token usage")
    }
}
