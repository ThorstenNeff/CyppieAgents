package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.AgentConfigRegistry
import com.tneff.cyppieagents.boot.AgentManagement
import com.tneff.cyppieagents.boot.LifecycleManager
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentDetail
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.routing.ApiException
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.agentMgmtRoutes
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * HTTP contract for agent CRUD (S14 / CYP-97). Reviewer focus: operator-only writes (fail-closed), the
 * §2 error codes, and the CYP-101 edit-prefill detail.
 */
class AgentMgmtRoutesTest {

    private class FakeSession(override val agentId: String) : ConnectorSession {
        override val events: Flow<StreamJsonEvent> = emptyFlow()
        override suspend fun sendTurn(turn: UserTurn) {}
        override fun close() {}
        override suspend fun closeAndAwait() {}
    }

    private fun mgmt(): AgentManagement {
        val state = HubState.hubAndSpoke(
            listOf(Agent("po", "PO", Role.PO, "po"), Agent("frontend", "FE", Role.WORKER, "frontend")),
            HubState.OPERATOR_ID, "default",
        )
        val configs = AgentConfigRegistry(
            listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("frontend", "FE", Role.WORKER, launch = "claude", claudeMd = "fe-persona")),
        )
        val lifecycle = LifecycleManager(
            initialWorktrees = mapOf("po" to "po", "frontend" to "frontend"),
            sessions = ConnectorSessions(),
            ensureWorktree = {},
            spawn = { id, _ -> FakeSession(id) },
        )
        return AgentManagement(state, lifecycle, configs, ensureWorktree = {}, deleteWorktree = {})
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.app(mgmt: AgentManagement) {
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) {
                exception<ApiException> { call, cause ->
                    call.respond(cause.status, ApiErrorBody(com.tneff.cyppieagents.model.ApiError(cause.code, cause.message)))
                }
            }
            routing { agentMgmtRoutes(mgmt, TokenRegistry(mapOf("tok-fe" to "frontend"), "tok-op")) }
        }
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.jsonClient() =
        createClient { install(ClientContentNegotiation) { json(CommJson) } }

    // ---- operator gating (fail-closed) ----

    @Test fun post_noToken_401() = testApplication {
        app(mgmt()); val c = jsonClient()
        val r = c.post("/api/agents") { contentType(ContentType.Application.Json); setBody(NewAgentSpec("backend", "BE", Role.WORKER)) }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test fun post_participant_403_operatorRequired() = testApplication {
        app(mgmt()); val c = jsonClient()
        val r = c.post("/api/agents") { bearerAuth("tok-fe"); contentType(ContentType.Application.Json); setBody(NewAgentSpec("backend", "BE", Role.WORKER)) }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals("operator_required", r.body<ApiErrorBody>().error.code)
    }

    @Test fun delete_participant_403() = testApplication {
        app(mgmt()); val c = jsonClient()
        assertEquals(HttpStatusCode.Forbidden, c.delete("/api/agents/frontend") { bearerAuth("tok-fe") }.status)
    }

    // ---- happy / codes ----

    @Test fun post_operator_creates201_stopped() = testApplication {
        app(mgmt()); val c = jsonClient()
        val r = c.post("/api/agents") { bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(NewAgentSpec("backend", "Backend", Role.WORKER, persona = "be")) }
        assertEquals(HttpStatusCode.Created, r.status)
        val created = r.body<com.tneff.cyppieagents.model.CreatedAgent>() // CYP-171: 201 body is CreatedAgent
        assertEquals("backend", created.agent.id)
        assertEquals(com.tneff.cyppieagents.model.AgentRunState.STOPPED, created.agent.runState)
        assertNull(created.token, "a local (non-remote) create discloses no token")
    }

    @Test fun post_operator_duplicate_409_agentExists() = testApplication {
        app(mgmt()); val c = jsonClient()
        val r = c.post("/api/agents") { bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(NewAgentSpec("frontend", "X", Role.WORKER)) }
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertEquals("agent_exists", r.body<ApiErrorBody>().error.code)
    }

    @Test fun delete_operator_lastPo_409() = testApplication {
        app(mgmt()); val c = jsonClient()
        val r = c.delete("/api/agents/po") { bearerAuth("tok-op") }
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertEquals("last_po", r.body<ApiErrorBody>().error.code)
    }

    // ---- CYP-101 edit-prefill detail ----

    @Test fun getDetail_participant_returnsPersonaAndLaunch_forPrefill() = testApplication {
        app(mgmt()); val c = jsonClient()
        val d = c.get("/api/agents/frontend") { bearerAuth("tok-fe") }.body<AgentDetail>()
        assertEquals("fe-persona", d.persona)
        assertEquals("claude", d.launch)
        assertEquals(Role.WORKER, d.role)
    }

    @Test fun getDetail_unknown_404() = testApplication {
        app(mgmt()); val c = jsonClient()
        val r = c.get("/api/agents/ghost") { bearerAuth("tok-fe") }
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertEquals("agent_not_found", r.body<ApiErrorBody>().error.code)
    }
}
