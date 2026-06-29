package com.tneff.cyppieagents.routing

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
 *  - **operator, fail-closed:** `requireOperator` runs BEFORE the body is received (401/403 unparsed).
 *  - 404 `agent_not_found` for an unknown agent (checked before the mutation).
 *  - on success: persists + re-declares caps + emits `connector.optin` ([ConnectorOptIn.apply]); returns
 *    the updated [com.tneff.cyppieagents.model.Agent] (with the new connectorKind + capabilities).
 */
fun Route.connectorRoutes(
    state: HubState,
    registry: TokenRegistry,
    capabilityRegistry: CapabilityRegistry,
    optIn: ConnectorOptIn,
) {
    route("/api/agents/{id}/connector") {
        post {
            call.requireOperator(registry) // fail-closed BEFORE receive
            val id = call.parameters["id"] ?: throw BadRequestException("missing path parameter 'id'")
            val agent = state.agents.firstOrNull { it.id == id }
                ?: throw NotFoundException("agent '$id' not found")
            val choice = call.receive<ConnectorChoice>()
            optIn.apply(id, choice.connectorKind)
            call.respond(
                agent.copy(connectorKind = choice.connectorKind, capabilities = capabilityRegistry.get(id)),
            )
        }
    }
}
