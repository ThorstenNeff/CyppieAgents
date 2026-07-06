package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.authenticatedApi
import com.tneff.cyppieagents.boot.ConnectorOptIn
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.connector.CapabilityRegistry
import com.tneff.cyppieagents.model.ConnectorChoice
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Connector opt-in endpoint (CYP-122 / Doc 10 §3,§5): the operator-gated, server-enforced, audited way to
 * change which connector serves an agent. Separate from the general agent edit ([agentMgmtRoutes]) so a
 * connector change is always an explicit, logged decision — there is **no "off-message" path** (a channel
 * message can never flip a connector; only this operator-token route can).
 *
 * `POST /api/agents/{id}/connector` body `{ "connectorKind": "mcp" | "stream_json" }`:
 *  - **operator, fail-closed:** the STRUCTURAL [authenticatedApi] group (CYP-178) runs BEFORE the body is received (401/403 unparsed).
 *  - 404 `agent_not_found` for an unknown agent (checked before the mutation).
 *  - on success: persists + re-declares caps + emits `connector.optin` ([ConnectorOptIn.apply]); returns
 *    the updated [com.tneff.cyppieagents.model.Agent] (with the new connectorKind + capabilities).
 */
// CYP-255 (.4b): the concrete overload (dev/test) delegates with a constant provider; production passes
// { runtimeRegistry.active().capabilityRegistry } so the re-declared caps come from the ACTIVE project.
fun Route.connectorRoutes(
    state: HubState,
    registry: TokenRegistry,
    capabilityRegistry: CapabilityRegistry,
    optIn: ConnectorOptIn,
    deps: AuthDeps = AuthDeps(registry),
    apiBase: String = "/api",
) = connectorRoutes(state, registry, { capabilityRegistry }, optIn, deps, apiBase)

fun Route.connectorRoutes(
    state: HubState,
    registry: TokenRegistry,
    // CYP-255 (.4b): resolve the ACTIVE project's capability registry per request (the opt-in itself
    // already resolves active() internally).
    capabilityRegistry: () -> CapabilityRegistry,
    optIn: ConnectorOptIn,
    deps: AuthDeps = AuthDeps(registry),
    apiBase: String = "/api",
) {
    // CYP-178: operator gate is STRUCTURAL (mounted under the group), fail-closed BEFORE receive; the RC1
    // route-enumeration meta-test is the net. No "off-message" path — only this operator route flips a connector.
    authenticatedApi(deps, AuthRole.OPERATOR) {
        route("$apiBase/agents/{id}/connector") {
            post {
                val id = call.parameters["id"] ?: throw BadRequestException("missing path parameter 'id'")
                val agent = state.agents.firstOrNull { it.id == id }
                    ?: throw NotFoundException("agent '$id' not found")
                val choice = call.receive<ConnectorChoice>()
                optIn.apply(id, choice.connectorKind)
                call.respond(
                    agent.copy(connectorKind = choice.connectorKind, capabilities = capabilityRegistry().get(id)),
                )
            }
        }
    }
}
