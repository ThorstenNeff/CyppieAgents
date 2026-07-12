package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthPrincipal
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.PrincipalKey
import com.tneff.cyppieagents.auth.authenticatedApi
import com.tneff.cyppieagents.controlplane.CpOperatorSession
import com.tneff.cyppieagents.controlplane.HubTicketMinter
import com.tneff.cyppieagents.controlplane.HubTicketFailure
import com.tneff.cyppieagents.controlplane.HubTicketRequest
import com.tneff.cyppieagents.controlplane.HubTicketResponse
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * CYP-508 (Epic CYP-427 Phase-2, activation) — the CP **hubTicket mint** route. Operator-gated. The operator id is
 * taken from the **authenticated principal** ([PrincipalKey]) — NEVER from the request body ([HubTicketRequest] carries
 * `hubId` + `cb`, no identity) — which is enforcement POINT 2 (`sub` never client-chosen). The minter owner-checks the
 * `hubId` against the [com.tneff.cyppieagents.controlplane.HubRegistrar] (POINT 1) and mints an EdDSA CpJwt bound to
 * the request `cb` 1:1 (POINT 3) + the single-sourced Op-Session-TTL (POINT 4).
 *
 * **INERT until the flip:** with the default [com.tneff.cyppieagents.controlplane.InertHubTicketMinter] the response is
 * `NOT_AUTHORIZED_FOR_HUB` — mounting this changes nothing observable until `CYPPIE_REMOTE_RELAY_URL` + the CP signing
 * config are set. Business outcome = 200 + typed [HubTicketResponse] (mint idiom); auth failures stay 401/403.
 *
 * ★ The **Reviewer re-gates the 4 LIVE enforcement points here** — the CYP-503 teeth prove them on the minter; the
 * `Cyp508HubTicketRoutesTest` teeth prove the wiring preserves them end-to-end through the route.
 */
fun Route.hubTicketRoutes(
    minter: () -> HubTicketMinter,
    registry: TokenRegistry,
    deps: AuthDeps = AuthDeps(registry),
    apiBase: String = "/api",
    /** The operator-token (machine) principal's mint subject — the configured operator id (`CYPPIE_OPERATOR_ID`), or
     *  null when unset (→ CP_SESSION_EXPIRED, fail-closed). A Kratos Human always uses its own `identityId`. */
    machineOperatorId: String? = null,
) {
    authenticatedApi(deps, AuthRole.OPERATOR) {
        post("$apiBase/cp/hubticket") {
            val opId = when (val p = call.attributes[PrincipalKey]) {
                is AuthPrincipal.Human -> p.identityId
                AuthPrincipal.MachineOperator -> machineOperatorId
                is AuthPrincipal.MachineAgent -> null // never reaches an OPERATOR gate (403 first) — defensive
            } ?: return@post call.respond(HubTicketResponse(failure = HubTicketFailure.CP_SESSION_EXPIRED))
            val req = call.receive<HubTicketRequest>()
            // sub = the AUTHENTICATED operator (carried via CpOperatorSession from the principal), never the request.
            call.respond(minter().mint(req, CpOperatorSession(opId)))
        }
    }
}
