package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.AgentConfigRegistry
import com.tneff.cyppieagents.boot.ConnectorOptIn
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.connector.CapabilityRegistry
import com.tneff.cyppieagents.events.EventFilter
import com.tneff.cyppieagents.events.EventRecorder
import com.tneff.cyppieagents.events.InMemoryEventSink
import com.tneff.cyppieagents.events.Page
import com.tneff.cyppieagents.events.SystemTimeSource
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.ApiError
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.ConnectorChoice
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.ApiException
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.connectorRoutes
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * CYP-122 opt-in control plane: `POST /api/agents/{id}/connector` is operator-gated + server-enforced and
 * **audits** the choice as `connector.optin`. There is no "off-message" path — only this operator-token
 * route changes a connector. Reviewer focus: fail-closed gating, 404, the audit event + caps re-declare.
 */
class ConnectorRoutesTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    private class Fixture {
        val state = HubState.hubAndSpoke(
            listOf(Agent("po", "PO", Role.PO, "po"), Agent("frontend", "FE", Role.WORKER, "frontend")),
            HubState.OPERATOR_ID, "default",
        )
        val configs = AgentConfigRegistry(listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("frontend", "FE", Role.WORKER)))
        val capabilityRegistry = CapabilityRegistry()
        val eventSink = InMemoryEventSink(SystemTimeSource())
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.app(fx: Fixture) {
        val recorder = EventRecorder(fx.eventSink, scope).also { it.start() }
        val optIn = ConnectorOptIn({ fx.configs }, { fx.capabilityRegistry }, recorder, { "default" })
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) {
                exception<ApiException> { call, cause -> call.respond(cause.status, ApiErrorBody(ApiError(cause.code, cause.message))) }
            }
            routing {
                connectorRoutes(fx.state, TokenRegistry(mapOf("tok-fe" to "frontend"), "tok-op"), fx.capabilityRegistry, optIn)
            }
        }
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.jsonClient() =
        createClient { install(ClientContentNegotiation) { json(CommJson) } }

    @Test fun post_noToken_401() = testApplication {
        app(Fixture()); val c = jsonClient()
        val r = c.post("/api/agents/frontend/connector") { contentType(ContentType.Application.Json); setBody(ConnectorChoice(ConnectorKind.MCP)) }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test fun post_participant_403_operatorRequired() = testApplication {
        app(Fixture()); val c = jsonClient()
        val r = c.post("/api/agents/frontend/connector") { bearerAuth("tok-fe"); contentType(ContentType.Application.Json); setBody(ConnectorChoice(ConnectorKind.MCP)) }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals("operator_required", r.body<ApiErrorBody>().error.code)
    }

    @Test fun post_operator_unknownAgent_404() = testApplication {
        app(Fixture()); val c = jsonClient()
        val r = c.post("/api/agents/ghost/connector") { bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(ConnectorChoice(ConnectorKind.MCP)) }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    @Test fun post_operator_optsIntoMcp_auditsAndRedeclaresCaps() = testApplication {
        val fx = Fixture()
        app(fx); val c = jsonClient()
        val r = c.post("/api/agents/frontend/connector") { bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(ConnectorChoice(ConnectorKind.MCP)) }
        assertEquals(HttpStatusCode.OK, r.status)
        val agent = r.body<Agent>()
        assertEquals(ConnectorKind.MCP, agent.connectorKind)
        assertEquals(ConnectorKind.MCP, agent.capabilities?.kind)

        // Persisted (effective next spawn) + caps re-declared for the read model/gating.
        assertEquals(ConnectorKind.MCP, fx.configs.connectorKindOf("frontend"))
        assertEquals(ConnectorKind.MCP, fx.capabilityRegistry.get("frontend")?.kind)

        // Audited: a content-free connector.optin event for frontend.
        val optinEvent = withTimeout(5_000) {
            var e = fx.eventSink.query(EventFilter(type = EventType.CONNECTOR_OPTIN), Page()).events.firstOrNull()
            while (e == null) { delay(20); e = fx.eventSink.query(EventFilter(type = EventType.CONNECTOR_OPTIN), Page()).events.firstOrNull() }
            e
        }
        assertNotNull(optinEvent)
        assertEquals("frontend", optinEvent.agentId)
        assertEquals(setOf("agentId", "connectorKind"), optinEvent.detail.keys)
    }
}
