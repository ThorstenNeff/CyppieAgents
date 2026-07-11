package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.model.WsTicket
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * CYP-286 — `POST /api/ws-ticket`: mint a short-lived, single-use WS ticket bound to the CALLER's OWN read
 * subject. [requireCommReader] resolves the caller (an operator/agent token, a participant token, or a verified
 * human session → its read subject) — so an **unauthenticated** caller gets 401 and mints nothing; the ticket
 * carries the subject the caller already had (NO escalation). The raw ticket is disclosed ONCE (never logged).
 * The client then opens a read `/ws` socket with `?ticket=` instead of a long-lived `?token=` (which lands in
 * URL/referrer/proxy logs). The legacy `?token=` path stays until the client migrates (a separate follow-up).
 */
fun Route.wsTicketRoutes(
    registry: TokenRegistry,
    deps: AuthDeps = AuthDeps(registry),
    apiBase: String = "/api",
) {
    route("$apiBase/ws-ticket") {
        post {
            val subject = call.requireCommReader(deps, registry) // 401 (UnauthorizedException) if unauthenticated
            val ticket = deps.wsTickets.mint(subject)
            call.respond(HttpStatusCode.Created, WsTicket(ticket = ticket, expiresInMs = deps.wsTickets.ttlMs))
        }
    }
}
