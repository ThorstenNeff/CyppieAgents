package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.AgentRunStateEvent
import com.tneff.cyppieagents.model.ApiErrorBody
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer

/**
 * Maps the server `:core` [AgentRunState] (RUNNING/STOPPED/ERROR) to the client [AgentLifecycleState].
 * [AgentLifecycleState.UNKNOWN] is client-only (before the first snapshot) and is never produced here.
 */
fun AgentRunState.toLifecycleState(): AgentLifecycleState = when (this) {
    AgentRunState.RUNNING -> AgentLifecycleState.RUNNING
    AgentRunState.STOPPED -> AgentLifecycleState.STOPPED
    AgentRunState.ERROR -> AgentLifecycleState.ERROR
}

/**
 * Thrown when a lifecycle control returns non-2xx. [code] is the uniform [ApiErrorBody] code
 * (`operator_required` / `agent_not_found` / `already_running` / `spawn_failed`) so the UI can surface
 * an honest reason instead of a generic failure (CYP-73).
 */
class AgentLifecycleHttpException(val status: Int, val code: String?) :
    Exception("lifecycle http $status (${code ?: "?"})")

/**
 * Real REST client for the **operator-gated** lifecycle controls (CYP-73):
 * `POST /api/agents/{id}/{stop|start|restart}` with the operator bearer. Errors decode the [ApiErrorBody]
 * code into [AgentLifecycleHttpException]. Decodes through the shared [CommJson] so the wire can't drift.
 */
class AgentLifecycleRepository(
    private val client: HttpClient,
    private val baseUrl: String,
    private val operatorToken: String,
) : AgentLifecycleApi {

    override suspend fun start(agentId: String) = action(agentId, "start")
    override suspend fun stop(agentId: String) = action(agentId, "stop")
    override suspend fun restart(agentId: String) = action(agentId, "restart")

    private suspend fun action(agentId: String, verb: String) {
        val response = client.post("$baseUrl/api/agents/$agentId/$verb") {
            // CYP-903: Bearer ONLY when present; token-less deployed web SPA → cookie auth (server prefers the
            // cookie over an empty Bearer, routing/Auth.kt `ifBlank{null}`). Mirrors AgentWsClient/CYP-230.
            if (operatorToken.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $operatorToken")
        }
        // 200 body is the AgentRunStateEvent; we don't apply it here — the /ws/lifecycle delta reflects
        // the same transition and the header is driven by that single live source.
        ensureSuccess(response, response.bodyAsText())
    }

    private fun ensureSuccess(response: HttpResponse, text: String) {
        if (!response.status.isSuccess()) {
            val code = runCatching {
                CommJson.decodeFromString(ApiErrorBody.serializer(), text).error.code
            }.getOrNull()
            throw AgentLifecycleHttpException(response.status.value, code)
        }
    }
}

// CYP-846: `AgentLifecycleLiveSource` (its own `/ws/lifecycle` socket + `GET /api/agents` snapshot) is REMOVED.
// The lifecycle deltas now demux from the ONE muxed `/ws/status` socket via `StatusMuxClient.lifecycle`, and the
// snapshot moved to `StatusMuxClient.fetchSnapshot()`. `toLifecycleState()` / `AgentLifecycleRepository` (the
// operator-gated control egress) stay — they are unaffected by the read-side mux.
