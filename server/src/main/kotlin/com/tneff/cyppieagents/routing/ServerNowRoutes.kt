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
 * Content-free: only [ServerNow.serverNowMs]. **Read-tier via [requireCommReader]** (token OR a verified human
 * OPERATOR/MEMBER session) — the SAME resolver as `GET /api/agents`, which the frontend fetches at the same attach.
 *
 * **CYP-487 (cutover landmine):** this was token-only ([requireParticipant]) — the tokenless SPA (Kratos session
 * cookie, no bearer — CYP-230) got **401** here, exactly the CYP-320 bug class. Since CYP-346's client-born
 * transcript timestamping ("stamp against SERVER time instead of the BROWSER clock") is a browser-session read, the
 * token-only gate was a latent cutover 401. The Tier stays PARTICIPANT (the OpenAPI already advertised
 * `sessionCookie`); the fix aligns the gate with the contract. [now] is injected so tests drive a fixed instant.
 */
fun Route.serverNowRoutes(
    now: () -> Long,
    registry: TokenRegistry,
    deps: AuthDeps = AuthDeps(registry),
    apiBase: String = "/api",
) {
    route("$apiBase/server-now") {
        get {
            call.requireCommReader(deps, registry) // token OR verified human session; 401 if unauthenticated
            call.respond(ServerNow(serverNowMs = now()))
        }
    }
}
