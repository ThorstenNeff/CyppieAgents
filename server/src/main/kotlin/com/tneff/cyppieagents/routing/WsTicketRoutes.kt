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
            // BINDING (the load-bearing property, PO1 re-gate): the ticket is bound to the caller's OWN resolved
            // read-principal and NOTHING the client sends. [requireCommReader] resolves `subject` from the caller's
            // OWN credential (agent/operator token, participant token, or verified human session → its identityId);
            // there is NO request field that could set it, so a member CANNOT mint a ticket for the operator or
            // another agent (no privilege escalation). [mint] carries THAT subject verbatim.
            val subject = call.requireCommReader(deps, registry) // 401 (UnauthorizedException) if unauthenticated
            // The minted ticket therefore unlocks ONLY what `subject` already may read: [wsReaderOrNull] resolves it
            // to the SAME subject the caller's credential would, so a read WS stays ACL-scoped to the minter (never
            // wider). The drive/operator socket `/ws/agent` uses [tokenAuthorize], which does NOT consume tickets —
            // so a ticket can never unlock a foreign/operator WS. (Proven end-to-end in Cyp286WsTicketRoutesTest.)
            val ticket = deps.wsTickets.mint(subject)
            call.respond(HttpStatusCode.Created, WsTicket(ticket = ticket, expiresInMs = deps.wsTickets.ttlMs))
        }
    }
}
