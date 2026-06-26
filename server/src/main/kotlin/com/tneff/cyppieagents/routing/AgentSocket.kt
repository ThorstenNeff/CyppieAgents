package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Per-agent event-stream WebSocket (Frame contract for Dev's `MappingAgentSession`):
 *
 *   GET /ws/agent?agentId=<id>
 *
 * **Server → Client:** one masked [StreamJsonEvent] per text frame, serialized with [CommJson]
 *   (`classDiscriminator="type"`), streamed 1:1 from the agent's [ConnectorSession.events].
 * **Client → Server:** one [UserTurn] (`{"text":"…"}`) per text frame → injected via
 *   [ConnectorSession.sendTurn] (single-flight per session).
 *
 * Masking is applied by the connector before events reach [ConnectorSession.events] (Gate #3),
 * so this route never sees unmasked content.
 */
/**
 * Authorizer built on the CYP-9 [TokenRegistry]: a connection is allowed when it presents a valid
 * operator token, or an agent token whose agent matches the requested `agentId` (an agent may watch
 * its own session). Token via `Authorization: Bearer` or the `?token=` query fallback (browsers).
 */
fun tokenAuthorize(registry: TokenRegistry): (ApplicationCall) -> Boolean = { call ->
    val token = call.bearerToken() ?: call.request.queryParameters["token"]
    when {
        registry.isOperator(token) -> true
        else -> {
            val agentForToken = registry.agentFor(token)
            agentForToken != null && agentForToken == call.request.queryParameters["agentId"]
        }
    }
}

fun Application.installAgentSocket(
    sessions: ConnectorSessions,
    authorize: (ApplicationCall) -> Boolean = { false },
) {
    install(WebSockets)
    routing { agentSocket(sessions, authorize) }
}

fun Route.agentSocket(
    sessions: ConnectorSessions,
    // Fail-closed by default (F-B): without an explicit predicate, NO connection is authorized —
    // an open socket lets anyone inject user-messages into an agent (i.e. drive it). Production
    // passes [tokenAuthorize]; tests opt in explicitly.
    authorize: (ApplicationCall) -> Boolean = { false },
) {
    webSocket("/ws/agent") {
        if (!authorize(call)) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return@webSocket
        }
        val agentId = call.request.queryParameters["agentId"]
        if (agentId.isNullOrBlank()) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "agentId required"))
            return@webSocket
        }
        val session = sessions.session(agentId)
        if (session == null) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "no live session for agent '$agentId'"))
            return@webSocket
        }

        // Server → Client: stream masked events, one JSON object per text frame.
        val pump = launch {
            session.events.collect { event ->
                send(Frame.Text(CommJson.encodeToString(StreamJsonEvent.serializer(), event)))
            }
        }
        try {
            // Client → Server: each text frame is a UserTurn; inject it.
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val turn = CommJson.decodeFromString<UserTurn>(frame.readText())
                    session.sendTurn(turn)
                }
            }
        } finally {
            pump.cancel()
        }
    }
}
