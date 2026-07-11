package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.authenticatedApi
import com.tneff.cyppieagents.boot.HandoffMotor
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.model.ModeChangeRequest
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * CYP-355 (BE-2) — the hand-off trigger. `POST /api/agents/{id}/mode` moves the agent between the mediated
 * (ORCHESTRATION) and interactive (TERMINAL) poles, driven by the active project's [HandoffMotor].
 *
 * **Operator-gated and fail-closed**, gated STRUCTURALLY under [authenticatedApi] exactly like the lifecycle
 * controls (CYP-178): a non-operator never learns whether the id exists. The control plane lives on REST, NOT
 * on `/ws/terminal` (which stays a single-viewer PTY-I/O stream). ACL is untouched.
 *
 * **The response is the settled, non-optimistic outcome.** A business rejection (busy-timeout, already-in-mode,
 * in-transition, spawn-failed) is a **200 with `outcome = REJECTED` + a reason** — the client parses one shape
 * either way and flips only on CONFIRMED. Only structural failures are HTTP errors: 401/403 (auth) and 404
 * `agent_not_found` (an unknown agent, thrown by the motor and mapped by StatusPages).
 *
 * CYP-255 (.4b): [handoff] resolves the ACTIVE project's motor per request (a same-id agent in another project
 * has its own runtime + its own transition lock).
 */
fun Route.modeRoutes(
    handoff: () -> HandoffMotor,
    registry: TokenRegistry,
    deps: AuthDeps = AuthDeps(registry),
    apiBase: String = "/api",
) {
    authenticatedApi(deps, AuthRole.OPERATOR) {
        route("$apiBase/agents/{id}") {
            post("/mode") {
                val id = call.parameters["id"] ?: throw BadRequestException("missing agent id")
                val req = call.receive<ModeChangeRequest>()
                // heldBy = the operator (the route is operator-gated); the mode-flip is a human's control act.
                val response = handoff().requestMode(id, req.target, requestedBy = HubState.OPERATOR_ID)
                call.respond(response) // 200 { outcome, control, reason? } — REJECTED is a 200 body; 404 thrown for unknown id
            }
        }
    }
}
