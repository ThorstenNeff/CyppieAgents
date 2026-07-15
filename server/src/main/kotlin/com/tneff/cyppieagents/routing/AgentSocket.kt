package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.agentevents.AgentEventStore
import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthPrincipal
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.resolvePrincipal
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.model.StoredAgentEvent
import com.tneff.cyppieagents.model.UserTurn
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.request.path
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
import org.slf4j.LoggerFactory

// CYP-607 DIAGNOSTIC INSTRUMENTATION (dogfood 2026-07-15) — TEMPORARY. `/ws/agent` uses an opaque `authorize`
// predicate (not [wsReaderOrNull]); its close paths were as silent as the shared gate. This names each reject
// (authorize-false / agentId-missing / cross-project) + the agentId so the next dogfood run distinguishes them.
// Never logs a token value. Remove once the loopback-WS reject root is fixed.
private val agentWsDiagLog = LoggerFactory.getLogger("cyp607-diag")

/**
 * Per-agent event-stream WebSocket (Frame contract for Dev's `MappingAgentSession`):
 *
 *   GET /ws/agent?agentId=<id>
 *
 * **Server → Client:** one [com.tneff.cyppieagents.model.StoredAgentEvent] per text frame — the durable
 *   envelope `{seq, agentId, projectId, tsMs, event}` where `event` is the masked [StreamJsonEvent] — serialized
 *   with [CommJson] (`classDiscriminator="type"`), replayed-then-live from the agentId-keyed transcript store.
 *   The `seq` is load-bearing: the `?since` cursor replay + client-side seq de-dup depend on it (CYP-198/409).
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
    // CYP-198 / CYP-384: the durable transcript store — REQUIRED. `/ws/agent` output is always the durable
    // replay-then-live stream keyed by agentId; there is no live-connector fallback (which held a stale session
    // ref across restarts — the CYP-382 defect class). Production always wires it (PlatformWiring).
    agentEvents: AgentEventStore,
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
    // CYP-198 / CYP-384: REQUIRED durable transcript store — the single output source (no captured-session fallback).
    agentEvents: AgentEventStore,
    // CYP-255 ②: the ACTIVE project's agent ids. Non-null (production) → a bare agentId that is NOT in the
    // active project's slice is rejected fail-closed, BEFORE any session/transcript resolution — else a
    // same-id agent in ANOTHER project (its session lives under that project's runtime) could attach
    // cross-project (foreign terminal / stdin / stdout). Null (dev/test) → no membership filter (unchanged).
    activeAgentIds: (() -> Set<String>)? = null,
) {
    webSocket("/ws/agent") {
        if (!authorize(call)) {
            agentWsDiagLog.warn( // CYP-607: name the silent authorize reject (agentId for correlation; no token value)
                "ws-auth NULL path={} axis=agent-authorize agentId={} hasBearer={} hasQueryToken={}",
                call.request.path(), call.request.queryParameters["agentId"],
                call.bearerToken() != null, call.request.queryParameters["token"] != null,
            )
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return@webSocket
        }
        val agentId = call.request.queryParameters["agentId"]
        if (agentId.isNullOrBlank()) {
            agentWsDiagLog.warn("ws-auth REJECT path={} reason=agentId-missing", call.request.path()) // CYP-607
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "agentId required"))
            return@webSocket
        }
        // CYP-255 ②: fail-closed on cross-project agent-id — the id must belong to the ACTIVE project.
        // Even an operator (workspace-wide by token) can only watch the ACTIVE project's agents (switch first).
        if (activeAgentIds != null && agentId !in activeAgentIds!!()) {
            agentWsDiagLog.warn("ws-auth REJECT path={} reason=cross-project-agent agentId={}", call.request.path(), agentId) // CYP-607
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "no such agent in the active project"))
            return@webSocket
        }
        // Server → Client: CYP-198 — replay durable transcript history since the client's cursor, then live
        // (gapless, deduped by seq). CYP-384: this durable [agentEvents] stream, keyed by agentId, is the SINGLE
        // output source. There is NO captured-session fallback and no session ref is taken at connect — the old
        // fallback pump froze on a dead session across a restart (the CYP-382 defect class); the agentId-keyed
        // stream follows a respawn for free. A REMOTE/STOPPED agent with no live session still gets its (possibly
        // empty) read-only transcript here; `activeAgentIds` above is the guard against an unknown agent.
        val since = call.request.queryParameters["since"]?.toLongOrNull()
        val pump = launch {
            agentEvents.subscribe(agentId, since).collect { stored ->
                send(Frame.Text(CommJson.encodeToString(StoredAgentEvent.serializer(), stored)))
            }
        }
        try {
            // Client → Server: each text frame is a UserTurn; validate (CYP-143) then inject it into the CURRENT
            // session, resolved PER FRAME (CYP-382). A restart swaps the registry entry (LifecycleManager.doSpawn:
            // remove old + register new) while this WS stays open, so a ref captured at connect would inject into
            // the dead session and the turn would be lost — the "first turn after restart is ignored" defect. No
            // live session (a remote/STOPPED agent's read-only view, or the transient remove→register window) →
            // skip this frame (the operator resends; hub messages are delivered durably by MessageDeliverer,
            // which likewise resolves the session fresh per drain). No captured ref exists to go stale.
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val turn = CommJson.decodeFromString<UserTurn>(frame.readText())
                    try {
                        MessageInput.requireValidBody(turn.text)
                    } catch (e: ApiException) {
                        // CYP-143: reject an oversized/blank inject fail-closed — never drive the agent with it.
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, e.message))
                        return@webSocket
                    }
                    val live = sessions().session(agentId) ?: continue
                    live.sendTurn(turn)
                }
            }
        } finally {
            pump.cancel()
        }
    }
}
