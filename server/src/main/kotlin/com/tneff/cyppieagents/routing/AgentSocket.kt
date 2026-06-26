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
fun Application.installAgentSocket(
    sessions: ConnectorSessions,
    authorize: (ApplicationCall) -> Boolean = { true },
) {
    install(WebSockets)
    routing { agentSocket(sessions, authorize) }
}

fun Route.agentSocket(
    sessions: ConnectorSessions,
    authorize: (ApplicationCall) -> Boolean = { true },
) {
    webSocket("/ws/agent") {
        // Auth hook: the UI/operator token check wires in here once CYP-9's TokenRegistry is on
        // develop. Default allow keeps the scaffold testable; production passes a real predicate.
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
