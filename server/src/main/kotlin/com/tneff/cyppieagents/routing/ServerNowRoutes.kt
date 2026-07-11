package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.model.ServerNow
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * CYP-421 (a) — `GET /api/server-now`: the server states its OWN clock at the moment of the call, so the client
 * can stamp its client-BORN transcript rows against server time instead of the browser clock (CYP-346). A2 = a
 * REST read the client makes at /ws/agent attach, deliberately NOT a new WS frame (a browser WebSocket can't read
 * handshake headers → frame-or-REST; REST leaves CYP-412's bare `StoredAgentEvent` /ws/agent seam untouched).
 *
 * Content-free: only [ServerNow.serverNowMs]. **PARTICIPANT-tier** — the same authenticated read line as
 * `GET /api/agents`, which the frontend already fetches at attach (no anonymous access, nothing sensitive). The
 * [now] clock is injected so tests can drive a fixed instant.
 */
fun Route.serverNowRoutes(
    now: () -> Long,
    registry: TokenRegistry,
    deps: AuthDeps = AuthDeps(registry),
    apiBase: String = "/api",
) {
    route("$apiBase/server-now") {
        get {
            call.requireParticipant(deps)
            call.respond(ServerNow(serverNowMs = now()))
        }
    }
}
