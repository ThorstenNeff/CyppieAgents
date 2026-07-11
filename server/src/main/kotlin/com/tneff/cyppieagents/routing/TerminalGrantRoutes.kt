package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.authenticatedApi
import com.tneff.cyppieagents.model.TerminalGrantRequest
import com.tneff.cyppieagents.model.TerminalGrants
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * CYP-421 (c) — operator-only management of per-agent terminal-delegation grants over the real
 * [TerminalGrantAdmin]. The socket gate ([mayOpenTerminal]) + kill-on-revoke ([TerminalGrantStore.track]) already
 * exist (CYP-394); this is the additive grant surface, not a gate rewrite.
 *
 * **c2 flag firewall.** [admin] is non-null ONLY when `CYPPIE_TERMINAL_DELEGATION_ENABLED` is on. When null
 * (default, OFF) every handler denies `403 terminal_delegation_disabled`: the endpoint is MOUNTED (so the REST
 * contract + drift enumeration stay honest and Dev5 gets the type) but INERT, so merging (c) changes nothing
 * observable. THE FLAG IS THE ACCESS GATE IN CODE — a WORKING `PUT` here IS the ungated member-delegation past
 * the Auftraggeber Access-Go; member ACTIVATION stays a separate Access-Go even after the flag flips.
 */
fun Route.terminalGrantRoutes(
    admin: TerminalGrantAdmin?,
    registry: TokenRegistry,
    deps: AuthDeps = AuthDeps(registry),
    apiBase: String = "/api",
) {
    authenticatedApi(deps, AuthRole.OPERATOR) {
        route("$apiBase/agents/{id}/terminal-grants") {
            get {
                val a = admin ?: forbidDelegationDisabled()
                val agentId = call.parameters["id"]!!
                call.respond(TerminalGrants(agentId, a.listGrants(agentId)))
            }
            put {
                val a = admin ?: forbidDelegationDisabled()
                val agentId = call.parameters["id"]!!
                val subject = call.receive<TerminalGrantRequest>().subject
                call.respond(TerminalGrants(agentId, a.grant(agentId, subject)))
            }
            delete {
                val a = admin ?: forbidDelegationDisabled()
                val agentId = call.parameters["id"]!!
                val subject = call.receive<TerminalGrantRequest>().subject
                call.respond(TerminalGrants(agentId, a.revoke(agentId, subject)))
            }
        }
    }
}

/** c2: the fail-closed deny when the delegation flag is OFF — the endpoint exists but grants nothing. */
private fun forbidDelegationDisabled(): Nothing =
    throw ForbiddenException("terminal member-delegation is disabled", code = "terminal_delegation_disabled")
