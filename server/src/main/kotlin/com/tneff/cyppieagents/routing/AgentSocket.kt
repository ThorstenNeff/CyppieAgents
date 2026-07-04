package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.agentevents.AgentEventStore
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.model.StoredAgentEvent
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
    // CYP-198: the durable transcript store — when wired, /ws/agent replays history-then-live from it.
    agentEvents: AgentEventStore? = null,
) {
    install(WebSockets) { maxFrameSize = MessageInput.MAX_FRAME_BYTES } // CYP-143: protocol backstop
    routing { agentSocket(sessions, authorize, agentEvents) }
}

fun Route.agentSocket(
    sessions: ConnectorSessions,
    // Fail-closed by default (F-B): without an explicit predicate, NO connection is authorized —
    // an open socket lets anyone inject user-messages into an agent (i.e. drive it). Production
    // passes [tokenAuthorize]; tests opt in explicitly.
    authorize: (ApplicationCall) -> Boolean = { false },
    agentEvents: AgentEventStore? = null,
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
        // A local session enables live inject; a wired [agentEvents] store enables durable transcript replay
        // (also for a REMOTE agent that has no local session — its window is read-only, driven over the wire).
        if (session == null && agentEvents == null) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "no live session for agent '$agentId'"))
            return@webSocket
        }

        // Server → Client: CYP-198 — replay durable history since the client's cursor, then live (gapless,
        // deduped by seq — the AgentEventStore is the single source). Falls back to the live-only connector
        // stream when no durable store is wired (backward-compatible).
        val since = call.request.queryParameters["since"]?.toLongOrNull()
        val pump = launch {
            if (agentEvents != null) {
                agentEvents.subscribe(agentId, since).collect { stored ->
                    send(Frame.Text(CommJson.encodeToString(StoredAgentEvent.serializer(), stored)))
                }
            } else {
                session!!.events.collect { event ->
                    send(Frame.Text(CommJson.encodeToString(StreamJsonEvent.serializer(), event)))
                }
            }
        }
        try {
            // Client → Server: each text frame is a UserTurn; validate (CYP-143) then inject it. Only a LOCAL
            // session can be driven this way — a remote agent's window is read-only here (it's driven over the wire).
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    if (session == null) continue // read-only transcript view (remote agent)
                    val turn = CommJson.decodeFromString<UserTurn>(frame.readText())
                    try {
                        MessageInput.requireValidBody(turn.text)
                    } catch (e: ApiException) {
                        // CYP-143: reject an oversized/blank inject fail-closed — never drive the agent with it.
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, e.message))
                        return@webSocket
                    }
                    session.sendTurn(turn)
                }
            }
        } finally {
            pump.cancel()
        }
    }
}
