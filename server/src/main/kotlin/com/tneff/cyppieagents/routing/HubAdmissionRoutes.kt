package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthPrincipal
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.PrincipalKey
import com.tneff.cyppieagents.auth.authenticatedApi
import com.tneff.cyppieagents.controlplane.AdmitResult
import com.tneff.cyppieagents.controlplane.HubAdmissionNonce
import com.tneff.cyppieagents.controlplane.HubAdmissionRequest
import com.tneff.cyppieagents.controlplane.HubAdmissionResult
import com.tneff.cyppieagents.controlplane.HubChallenge
import com.tneff.cyppieagents.controlplane.HubRegistrar
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.util.Base64

/**
 * CYP-512 (Epic CYP-427 Phase-2, activation) — the CP-side **live hub-admission** endpoint. **Operator-authenticated**
 * (a Kratos-session OPERATOR or the operator bearer). Two steps:
 *  - `GET  {apiBase}/cp/challenge` → a fresh single-use PoP nonce ([HubAdmissionNonce]).
 *  - `POST {apiBase}/cp/admit` {registration, nonce} → live [HubRegistrar.admit] (transcript PoP + self-certifying
 *    hubId, CYP-451). Populates the SHARED `cpHubRegistrar` the CYP-508 mint + CYP-511 resolve owner-check read.
 *
 * ★ **(A) ownerId is bound to the AUTHENTICATED operator, NEVER the hub payload** (PO GO, symmetric to CYP-508 P2):
 * the CP requires `registration.ownerId == the authenticated operator id`, else `owner_mismatch`. (The CYP-451 PoP is
 * over the transcript INCLUDING ownerId, so the CP **enforces equality** rather than overriding — same property, the
 * ratified admit stays intact: a hub can never register itself as owned by someone else.)
 * ★ **Nonce single-use + anti-replay:** the nonce is consumed FIRST (any presentation burns it), so a captured
 * registration cannot be replayed (`nonce_invalid`).
 * ★ **PoP no-bypass:** admission still runs the full CYP-451 [HubRegistrar.admit] (PoP over the transcript + hubId
 * self-certification). Fail-closed throughout: without a successful admit the hub does not enter (no half-state).
 *
 * INERT until a hub actually registers (the endpoint exists; the registry stays empty → the mint/resolve owner-check
 * stay fail-closed). The hub side dials this via [com.tneff.cyppieagents.boot.HubAdmissionClient] (env CYPPIE_CP_URL).
 */
fun Route.hubAdmissionRoutes(
    registrar: () -> HubRegistrar,
    nonces: HubAdmissionNonce,
    registry: TokenRegistry,
    deps: AuthDeps = AuthDeps(registry),
    apiBase: String = "/api",
    machineOperatorId: String? = null,
) {
    authenticatedApi(deps, AuthRole.OPERATOR) {
        get("$apiBase/cp/challenge") {
            call.respond(HubChallenge(Base64.getEncoder().encodeToString(nonces.issue())))
        }
        post("$apiBase/cp/admit") {
            val opId = when (val p = call.attributes[PrincipalKey]) {
                is AuthPrincipal.Human -> p.identityId
                AuthPrincipal.MachineOperator -> machineOperatorId
                is AuthPrincipal.MachineAgent -> null // never reaches an OPERATOR gate (403 first) — defensive
            } ?: return@post call.respond(HubAdmissionResult(admitted = false, reason = "no_operator"))
            val req = call.receive<HubAdmissionRequest>()
            val nonce = runCatching { Base64.getDecoder().decode(req.nonce) }.getOrNull()
                ?: return@post call.respond(HubAdmissionResult(admitted = false, reason = "nonce_invalid"))
            // anti-replay FIRST: any presentation of a nonce consumes it (single-use) — a replay finds it gone.
            if (!nonces.consume(nonce)) {
                return@post call.respond(HubAdmissionResult(admitted = false, reason = "nonce_invalid"))
            }
            // (A) ownerId MUST be the authenticated operator, never the hub's free claim. CYP-512 (Reviewer Finding #1
            // root): a BLANK ownerId is rejected too — else a `ownerId==""` hub could be admitted when `opId==""`, and
            // the resolve-owner-check (guards `!= null`, not blank) would then GRANT it. Blank never admits.
            if (req.registration.ownerId.isBlank() || req.registration.ownerId != opId) {
                return@post call.respond(HubAdmissionResult(admitted = false, reason = "owner_mismatch"))
            }
            // full CYP-451 admit: transcript PoP + self-certifying hubId. Stores ownerId (== the authenticated operator).
            val result = when (val r = registrar().admit(req.registration, nonce)) {
                is AdmitResult.Admitted -> HubAdmissionResult(admitted = true, hubId = r.hub.hubId)
                is AdmitResult.Rejected -> HubAdmissionResult(admitted = false, reason = r.reason)
            }
            call.respond(result)
        }
    }
}
