package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.authenticatedApi
import com.tneff.cyppieagents.boot.AgentBusyStateTracker
import com.tneff.cyppieagents.boot.AgentTokenUsageTracker
import com.tneff.cyppieagents.boot.LifecycleManager
import com.tneff.cyppieagents.boot.TerminalControlStateTracker
import com.tneff.cyppieagents.model.AgentRunStateEvent
import com.tneff.cyppieagents.model.AgentBusyStateEvent
import com.tneff.cyppieagents.model.AgentTerminalControlEvent
import com.tneff.cyppieagents.model.AgentTokenUsageEvent
import com.tneff.cyppieagents.model.BusyStatus
import com.tneff.cyppieagents.model.LifecycleStatus
import com.tneff.cyppieagents.model.StatusFrame
import com.tneff.cyppieagents.model.TerminalStatus
import com.tneff.cyppieagents.model.TokenUsageStatus
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

/**
 * Agent lifecycle controls (CYP-73). `POST /api/agents/{id}/{stop|start|restart}` are **operator-gated
 * and fail-closed** (gated STRUCTURALLY under the [authenticatedApi] group, CYP-178, so a non-operator
 * never even learns whether an id exists). The control surface is the only operator-gated part; the
 * *status display* (`GET /api/agents`
 * + `/ws/lifecycle`) is not.
 *
 * Errors via the uniform envelope: 401 (no token), 403 `operator_required`, 404 `agent_not_found`,
 * 409 `already_running`, 503 `spawn_failed` — thrown by [LifecycleManager], mapped by StatusPages.
 */
// CYP-255 (.4b): [lifecycle] resolves the ACTIVE project's LifecycleManager per request, so stop/start/
// restart act on the switched-to project's agents (a same-id agent in another project has its own runtime).
fun Route.lifecycleRoutes(lifecycle: () -> LifecycleManager, registry: TokenRegistry, deps: AuthDeps = AuthDeps(registry), apiBase: String = "/api") {
    // CYP-178: operator gate is STRUCTURAL (mounted under the group) — 401/403 before the agent id is
    // touched (no existence leak to non-operators); the RC1 route-enumeration meta-test is the net.
    authenticatedApi(deps, AuthRole.OPERATOR) {
        route("$apiBase/agents/{id}") {
            post("/stop") { call.lifecycleAction { lifecycle().stop(it) } }
            post("/start") { call.lifecycleAction { lifecycle().start(it) } }
            post("/restart") { call.lifecycleAction { lifecycle().restart(it) } }
        }
    }
}

private suspend inline fun ApplicationCall.lifecycleAction(
    action: (agentId: String) -> AgentRunStateEvent,
) {
    val id = parameters["id"] ?: throw BadRequestException("missing agent id")
    respond(action(id)) // 200 { agentId, status }; 404/409/503 thrown inside
}

/**
 * `/ws/lifecycle` — content-free agent status feed (CYP-73). **Participant-gated, NOT operator-only**
 * (the AgentWindow header is always visible, so any agent/operator token may watch); fail-closed with
 * WS close **1008** on a missing/invalid token, the same convention as `/ws/agent|comm|events`.
 *
 * It carries ONLY [AgentRunStateEvent] (`{agentId, status}`) — never event details/bodies/metadata — and
 * is a separate socket from the operator-gated `/ws/events`, so it cannot become a leak vector around
 * the Event-Log egress. On connect it streams a snapshot (one per agent) then live deltas.
 */
fun Route.lifecycleSocket(lifecycle: () -> LifecycleManager, registry: TokenRegistry, deps: com.tneff.cyppieagents.auth.AuthDeps = com.tneff.cyppieagents.auth.AuthDeps(registry)) {
    webSocket("/ws/lifecycle") {
        // CYP-188 B: read-tier (token OR verified human session) — the status feed is content-free (agent
        // runState only), so any authenticated reader (like GET /api/agents) may watch it live.
        if (call.wsReaderOrNull(deps, registry) == null) {
            return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
        }
        // CYP-255 (.4b): bind to the ACTIVE project's lifecycle at connect (the status feed is per-view; a
        // switch reopens the socket, mirrored by the client). Snapshot-then-deltas in ONE coroutine.
        val lc = lifecycle()
        lc.events
            .onStart { lc.snapshot().forEach { emit(it) } }
            .collect { event ->
                send(Frame.Text(CommJson.encodeToString(AgentRunStateEvent.serializer(), event)))
            }
    }
}

/**
 * `/ws/token-usage` — CYP-316 per-agent context-window occupancy feed for the AgentWindow title bar.
 * **Participant-gated** (read-tier, like `/ws/lifecycle`): the header is always visible and the payload is
 * a content-free token *count* only. Deliberately its OWN socket, separate from `/ws/lifecycle` — the
 * token value is per-turn higher-frequency than start/stop, so isolating it keeps the lifecycle feed's
 * reconnect profile clean (CYP-115) and `AgentRunStateEvent` single-typed.
 *
 * **Latest-wins, idempotent:** on connect it streams a snapshot (one [AgentTokenUsageEvent] per agent)
 * then live deltas; the client upserts by `agentId`, so a reconnect snapshot never duplicates or loses.
 * Bound to the ACTIVE project's tracker at connect (per-view; a switch reopens the socket, like lifecycle).
 */
fun Route.tokenUsageSocket(tokenUsage: () -> AgentTokenUsageTracker, registry: TokenRegistry, deps: com.tneff.cyppieagents.auth.AuthDeps = com.tneff.cyppieagents.auth.AuthDeps(registry)) {
    webSocket("/ws/token-usage") {
        if (call.wsReaderOrNull(deps, registry) == null) {
            return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
        }
        val t = tokenUsage()
        t.events
            .onStart { t.snapshot().forEach { emit(it) } }
            .collect { event ->
                send(Frame.Text(CommJson.encodeToString(AgentTokenUsageEvent.serializer(), event)))
            }
    }
}

/**
 * CYP-324 — the per-agent busy/idle feed for the title-bar `*`. Same shape as [tokenUsageSocket]: one-way
 * server→client, participant/read-tier gated (fail-closed WS close 1008), snapshot-then-deltas so a
 * reconnect mid-turn re-delivers `busy = true`. Bound to the ACTIVE project's tracker at connect.
 */
fun Route.busyStateSocket(busyState: () -> AgentBusyStateTracker, registry: TokenRegistry, deps: com.tneff.cyppieagents.auth.AuthDeps = com.tneff.cyppieagents.auth.AuthDeps(registry)) {
    webSocket("/ws/busy-state") {
        if (call.wsReaderOrNull(deps, registry) == null) {
            return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
        }
        val t = busyState()
        t.events
            .onStart { t.snapshot().forEach { emit(it) } }
            .collect { event ->
                send(Frame.Text(CommJson.encodeToString(AgentBusyStateEvent.serializer(), event)))
            }
    }
}

/**
 * CYP-354 (BE-1) — the per-agent terminal-control-mode feed (the CYP-333 toggle mirrors this, never infers).
 * Same shape as [busyStateSocket]: one-way server→client, participant/read-tier gated (fail-closed WS close
 * 1008), snapshot-then-deltas so a reconnect re-delivers the current mode + holder. Bound to the ACTIVE
 * project's tracker at connect. **Content-free — state/identity/time only, NEVER keystrokes/terminal content.**
 */
fun Route.terminalControlSocket(terminalControl: () -> TerminalControlStateTracker, registry: TokenRegistry, deps: com.tneff.cyppieagents.auth.AuthDeps = com.tneff.cyppieagents.auth.AuthDeps(registry)) {
    webSocket("/ws/terminal-state") {
        if (call.wsReaderOrNull(deps, registry) == null) {
            return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
        }
        val t = terminalControl()
        t.events
            .onStart { t.snapshot().forEach { emit(it) } }
            .collect { event ->
                send(Frame.Text(CommJson.encodeToString(AgentTerminalControlEvent.serializer(), event)))
            }
    }
}

/**
 * CYP-840 (decompose of CYP-612) — the **muxed** `/ws/status`: ONE socket that replaces the 4 content-free
 * status feeds ([lifecycleSocket]/[tokenUsageSocket]/[busyStateSocket]/[terminalControlSocket]), so the client
 * opens ONE stream instead of four. Same read-tier gate ([wsReaderOrNull], fail-closed WS `1008`) and same
 * content-free payloads — muxing them into ONE participant stream dissolves NO egress boundary (the operator-gated
 * `/ws/events` and the auth-/PTY-gated `/ws/comm|agent|terminal` deliberately stay SEPARATE — the CYP-840
 * content-free-only invariant).
 *
 * Each of the 4 substreams keeps its OWN **snapshot-then-deltas** semantics **atomically** — its `snapshot()`
 * prologue runs before its live deltas, inside its own [flow] — and the 4 are [merge]d live under one
 * [StatusFrame] discriminator (`type` = lifecycle/tokenUsage/busy/terminal). The client upserts by `(type,
 * agentId)`, so cross-substream snapshot interleaving is order-immaterial (no "snapshot-complete" marker needed),
 * exactly as each single socket already relies on for its reconnect snapshot. **Additive-parallel:** the 4
 * individual sockets stay wired until the client consumer cuts over.
 */
fun Route.statusSocket(
    lifecycle: () -> LifecycleManager,
    tokenUsage: () -> AgentTokenUsageTracker,
    busyState: () -> AgentBusyStateTracker,
    terminalControl: () -> TerminalControlStateTracker,
    registry: TokenRegistry,
    deps: com.tneff.cyppieagents.auth.AuthDeps = com.tneff.cyppieagents.auth.AuthDeps(registry),
) {
    webSocket("/ws/status") {
        if (call.wsReaderOrNull(deps, registry) == null) {
            return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
        }
        val lc = lifecycle(); val tu = tokenUsage(); val bs = busyState(); val tc = terminalControl()
        merge(
            flow { lc.snapshot().forEach { emit(LifecycleStatus(it)) }; lc.events.collect { emit(LifecycleStatus(it)) } },
            flow { tu.snapshot().forEach { emit(TokenUsageStatus(it)) }; tu.events.collect { emit(TokenUsageStatus(it)) } },
            flow { bs.snapshot().forEach { emit(BusyStatus(it)) }; bs.events.collect { emit(BusyStatus(it)) } },
            flow { tc.snapshot().forEach { emit(TerminalStatus(it)) }; tc.events.collect { emit(TerminalStatus(it)) } },
        ).collect { frame ->
            send(Frame.Text(CommJson.encodeToString(StatusFrame.serializer(), frame)))
        }
    }
}
