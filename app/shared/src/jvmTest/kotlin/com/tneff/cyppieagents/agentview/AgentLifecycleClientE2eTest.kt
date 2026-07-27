package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.ApiError
import com.tneff.cyppieagents.model.ApiErrorBody
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * CYP-73: the operator-gated lifecycle **control** client ([AgentLifecycleRepository]) + the run-state mapping,
 * on the frozen contract. A non-2xx control decodes the [ApiError] `code` into [AgentLifecycleHttpException]
 * (honest 409).
 *
 * CYP-846: the lifecycle **display** source (`/ws/lifecycle` socket-stream + `GET /api/agents` snapshot) was folded
 * into `StatusMuxClient`; that stream-decode and the snapshot fail-closed/credential teeth now live in
 * `Cyp846StatusMuxTest`. The control repository + `toLifecycleState` mapping below are unchanged by the mux.
 */
class AgentLifecycleClientE2eTest {

    @Test
    fun control_conflict_decodesApiErrorCode() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            routing {
                post("/api/agents/{id}/start") {
                    val body = CommJson.encodeToString(ApiErrorBody.serializer(), ApiErrorBody(ApiError("already_running", "agent 'backend' is already running")))
                    call.respondText(body, status = HttpStatusCode.Conflict)
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO)
            try {
                val repo = AgentLifecycleRepository(client, "http://127.0.0.1:$port", operatorToken = "op")
                try {
                    repo.start("backend")
                    fail("expected AgentLifecycleHttpException")
                } catch (e: AgentLifecycleHttpException) {
                    assertEquals(409, e.status)
                    assertEquals("already_running", e.code)
                }
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }

    @Test
    fun runStateMapping_coversAllServerValues() {
        assertEquals(AgentLifecycleState.RUNNING, AgentRunState.RUNNING.toLifecycleState())
        assertEquals(AgentLifecycleState.STOPPED, AgentRunState.STOPPED.toLifecycleState())
        assertEquals(AgentLifecycleState.ERROR, AgentRunState.ERROR.toLifecycleState())
    }
}
