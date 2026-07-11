package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.agentevents.AgentEventStore
import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthPrincipal
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.resolvePrincipal
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
 * Authorizer built on the CYP-9 [TokenRegistry] + the CYP-178 auth chain. A connection is allowed when it:
 * (1) presents a valid **operator token**, or (2) an **agent token** whose agent matches the requested
 * `agentId` (an agent may watch its own session), or (3) carries a **verified OPERATOR Kratos session**
 * (CYP-230). Token via `Authorization: Bearer` or the `?token=` query fallback.
 *
 * **CYP-230 (deploy-blocker):** the tokenless public SPA has no real agent token (those are `HUB_TOKEN_*`
 * secrets that must NOT ship to a public client) — it relies on its same-origin Kratos session cookie, which
 * the browser sends automatically on the WSS handshake. The session branch is **OPERATOR-only, fail-closed**:
 * only a human operator may watch **another** agent's stream (a MEMBER session is rejected → 1008); an agent
 * still watches its own via its token. The suspend `resolvePrincipal` reads the cookie (there is no bearer for
 * the SPA), so this predicate is `suspend`.
 */
fun tokenAuthorize(registry: TokenRegistry, deps: AuthDeps): suspend (ApplicationCall) -> Boolean = { call ->
    val token = call.bearerToken() ?: call.request.queryParameters["token"]
    when {
        registry.isOperator(token) -> true
        registry.agentFor(token)?.let { it == call.request.queryParameters["agentId"] } == true -> true
        // CYP-230: a verified OPERATOR session (Kratos cookie). NOT any session — a MEMBER human is rejected.
        else -> (call.resolvePrincipal(deps) as? AuthPrincipal.Human)?.role == AuthRole.OPERATOR
    }
}

fun Application.installAgentSocket(
    sessions: ConnectorSessions,
    authorize: suspend (ApplicationCall) -> Boolean = { false },
    // CYP-198: the durable transcript store — when wired, /ws/agent replays history-then-live from it.
    agentEvents: AgentEventStore? = null,
    // CYP-255 ②: the ACTIVE project's agent slice — see [agentSocket]. Null (dev/test) → no membership filter.
    activeAgentIds: (() -> Set<String>)? = null,
) {
    install(WebSockets) { maxFrameSize = MessageInput.MAX_FRAME_BYTES } // CYP-143: protocol backstop
    routing { agentSocket({ sessions }, authorize, agentEvents, activeAgentIds) }
}

fun Route.agentSocket(
    // CYP-255 ②: resolved per connection through the ACTIVE project's runtime (not a boot-pinned instance),
    // so a bare agentId maps to the ACTIVE project's sessions — two projects with an agent `backend` no
    // longer share one session map.
    sessions: () -> ConnectorSessions,
    // Fail-closed by default (F-B): without an explicit predicate, NO connection is authorized —
    // an open socket lets anyone inject user-messages into an agent (i.e. drive it). Production
    // passes [tokenAuthorize]; tests opt in explicitly.
    authorize: suspend (ApplicationCall) -> Boolean = { false },
    agentEvents: AgentEventStore? = null,
    // CYP-255 ②: the ACTIVE project's agent ids. Non-null (production) → a bare agentId that is NOT in the
    // active project's slice is rejected fail-closed, BEFORE any session/transcript resolution — else a
    // same-id agent in ANOTHER project (its session lives under that project's runtime) could attach
    // cross-project (foreign terminal / stdin / stdout). Null (dev/test) → no membership filter (unchanged).
    activeAgentIds: (() -> Set<String>)? = null,
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
        // CYP-255 ②: fail-closed on cross-project agent-id — the id must belong to the ACTIVE project.
        // Even an operator (workspace-wide by token) can only watch the ACTIVE project's agents (switch first).
        if (activeAgentIds != null && agentId !in activeAgentIds!!()) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "no such agent in the active project"))
            return@webSocket
        }
        val session = sessions().session(agentId)
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
                    // CYP-382: resolve the CURRENT session PER FRAME — never inject through the ref captured at
                    // connect (above). A restart swaps the registry entry (LifecycleManager.doSpawn: remove the
                    // old session + register the new one) while this WS stays open; the captured ref then points
                    // at the dead, removed session, and its `sendTurn` writes to a destroyed process (throws /
                    // silently lost) — the "first turn after restart is ignored" defect. The output pump follows
                    // the agentId via `agentEvents`, so ONLY this inject path held a stale ref. A transient null
                    // during the swap window → skip this frame (the operator resends; hub messages are delivered
                    // durably by MessageDeliverer, which already resolves the session fresh per drain).
                    val live = sessions().session(agentId) ?: continue
                    live.sendTurn(turn)
                }
            }
        } finally {
            pump.cancel()
        }
    }
}
