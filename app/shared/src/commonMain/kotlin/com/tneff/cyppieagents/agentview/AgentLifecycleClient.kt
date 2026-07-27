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
            header(HttpHeaders.Authorization, "Bearer $operatorToken")
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

/**
 * Real lifecycle **state source** (CYP-73, non-gated display): the initial [snapshot] from the
 * `GET /api/agents` `runState` (CC1/CYP-179 — now credentialed like the comm reads, see [snapshot]),
 * and live [events] from the participant-gated `/ws/lifecycle`
 * (snapshot-then-deltas of content-free [AgentRunStateEvent]). The socket auto-reconnects with backoff
 * ([reconnecting]); a reconnect just re-streams the snapshot, which the per-agent header upserts
 * idempotently. Kept separate from the operator-gated egress — no leak vector (same boundary as CYP-55).
 */
class AgentLifecycleLiveSource(
    private val client: HttpClient,
    private val httpBaseUrl: String,
    private val wsBaseUrl: String,
    private val token: String,
    // CYP-819: fired when the feed sees a terminal 1008 (VIOLATED_POLICY) auth-revoke, so the workspace surfaces
    // the revoke instead of freezing the last run state as if it were still current (the web-ts twin was CYP-815).
    private val onRevoked: () -> Unit = {},
) : AgentLifecycleSource {

    override suspend fun snapshot(): Map<String, AgentLifecycleState> = try {
        // CC1 / CYP-179: `GET /api/agents` is now gated by requireCommReader (agent/operator token OR a verified
        // human session), like /api/channels — so send the same bearer we already hold ([token]). Without it the
        // gate 401s and the snapshot fails closed to empty (header stays UNKNOWN until /ws/lifecycle delivers a state).
        val response = client.get("$httpBaseUrl/api/agents") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            emptyMap()
        } else {
            CommJson.decodeFromString(ListSerializer(Agent.serializer()), text)
                .associate { it.id to it.runState.toLifecycleState() }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // Server unreachable / undecodable → no snapshot. Honest: the header stays UNKNOWN until the
        // /ws/lifecycle feed delivers a state (never crash the window over a missing snapshot).
        emptyMap()
    }

    override fun events(): Flow<AgentLifecycleEvent> =
        client.statusFeed(lifecycleUrl(), "lifecycle") {
            // The REAL :core event; mapped to the client [AgentLifecycleEvent] (no hand-parse, no drift).
            val event = CommJson.decodeFromString(AgentRunStateEvent.serializer(), it)
            AgentLifecycleEvent(event.agentId, event.runState.toLifecycleState())
        }.terminalOnRevoke(onRevoked)

    private fun lifecycleUrl(): String {
        val sep = if (wsBaseUrl.endsWith("/")) "" else "/"
        return "$wsBaseUrl${sep}ws/lifecycle?token=$token"
    }
}
