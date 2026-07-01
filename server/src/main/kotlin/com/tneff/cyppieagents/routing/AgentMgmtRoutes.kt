package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.authenticatedApi
import com.tneff.cyppieagents.boot.AgentManagement
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.WorktreeFate
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * Agent-management CRUD endpoints (S14 / CYP-97 — AGENT-MANAGEMENT §2). The list `GET /api/agents`
 * stays in [commRoutes] (it already reads the now-mutable `HubState.agents`, so it reflects add/remove
 * — the dynamic window list, §9.5). This adds the edit-prefill detail + the operator-gated mutations.
 *
 * Gates: detail GET = **participant** (the management list is read-only visible without an operator
 * token); POST/PUT/DELETE = **operator, fail-closed** — gated STRUCTURALLY under the [authenticatedApi]
 * group (CYP-178), so the operator check runs **before** the body is received and cannot be forgotten by a
 * new endpoint added inside the group; a non-operator is rejected (401/403) without the request being parsed.
 */
fun Route.agentMgmtRoutes(mgmt: AgentManagement, registry: TokenRegistry, deps: AuthDeps = AuthDeps(registry)) {
    route("/api/agents") {
        // Detail = participant (the management list is read-only visible without an operator token).
        get("/{id}") {
            call.requireParticipant(registry)
            call.respond(mgmt.detail(call.parameters.getOrFail("id"))) // 404 agent_not_found
        }
        // CYP-178: the mutations are gated STRUCTURALLY under the group — fail-closed BEFORE the body is
        // received (a non-operator is 401/403, unparsed). The RC1 route-enumeration meta-test is the net.
        authenticatedApi(deps, AuthRole.OPERATOR) {
            post {
                val spec = call.receive<NewAgentSpec>()
                call.respond(HttpStatusCode.Created, mgmt.add(spec)) // 400/409 per the guard
            }
            put("/{id}") {
                val edit = call.receive<AgentEdit>()
                call.respond(mgmt.edit(call.parameters.getOrFail("id"), edit)) // 404/409 per the guard
            }
            delete("/{id}") {
                // Default fate = KEEP (safe); only an explicit ?worktree=delete is the destructive path.
                val fate = if (call.request.queryParameters["worktree"].equals("delete", ignoreCase = true)) {
                    WorktreeFate.DELETE
                } else {
                    WorktreeFate.KEEP
                }
                mgmt.remove(call.parameters.getOrFail("id"), fate) // 404/409 last_po per the guard
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun io.ktor.http.Parameters.getOrFail(name: String): String =
    this[name] ?: throw BadRequestException("missing path parameter '$name'")
