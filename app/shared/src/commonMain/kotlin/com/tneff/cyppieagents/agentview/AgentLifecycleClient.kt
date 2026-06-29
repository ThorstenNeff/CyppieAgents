package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.AgentRunStateEvent
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.net.logWsError
import com.tneff.cyppieagents.net.reconnecting
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
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
 * Real lifecycle **state source** (CYP-73, non-gated display): the initial [snapshot] from the public
 * `GET /api/agents` `runState`, and live [events] from the participant-gated `/ws/lifecycle`
 * (snapshot-then-deltas of content-free [AgentRunStateEvent]). The socket auto-reconnects with backoff
 * ([reconnecting]); a reconnect just re-streams the snapshot, which the per-agent header upserts
 * idempotently. Kept separate from the operator-gated egress — no leak vector (same boundary as CYP-55).
 */
class AgentLifecycleLiveSource(
    private val client: HttpClient,
    private val httpBaseUrl: String,
    private val wsBaseUrl: String,
    private val token: String,
) : AgentLifecycleSource {

    override suspend fun snapshot(): Map<String, AgentLifecycleState> = try {
        val response = client.get("$httpBaseUrl/api/agents") // public, secret-free
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

    override fun events(): Flow<AgentLifecycleEvent> = channelFlow {
        // channelFlow (not flow): the webSocket body runs on the engine dispatcher (Dispatchers.IO on Native),
        // so emitting from here is a cross-context send — illegal in flow{} (the ISE behind the CYP-115 Darwin
        // churn) but exactly what channelFlow allows. `.reconnecting()` still re-subscribes on a real drop.
        try {
            client.webSocket(urlString = lifecycleUrl()) {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val event = CommJson.decodeFromString(AgentRunStateEvent.serializer(), frame.readText())
                        this@channelFlow.send(AgentLifecycleEvent(event.agentId, event.runState.toLifecycleState()))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // CYP-125: log a real /ws/lifecycle failure instead of relying purely on reconnecting() to swallow
            // it — observability parity with Comm/Acl/Events (CYP-115). A normal close doesn't throw → only real
            // errors, no per-disconnect noise; CancellationException is rethrown; `.reconnecting()` still
            // re-subscribes (on the block's completion or failure).
            logWsError("lifecycle", e)
        }
    }.reconnecting()

    private fun lifecycleUrl(): String {
        val sep = if (wsBaseUrl.endsWith("/")) "" else "/"
        return "$wsBaseUrl${sep}ws/lifecycle?token=$token"
    }
}
