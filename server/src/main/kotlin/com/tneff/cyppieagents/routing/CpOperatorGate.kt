package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthPrincipal
import com.tneff.cyppieagents.auth.PrincipalKey
import com.tneff.cyppieagents.controlplane.HubRegistrar
import io.ktor.server.application.ApplicationCall

/**
 * CYP-516 — the SINGLE source of the CP operator-identity derivation + owner-check, shared by the mint (HubTicket),
 * resolve, AND register routes. Previously hand-duplicated in each route (drift risk: a future change to one copy
 * silently diverges the operator-binding across the CP surface). Centralised so `sub`/owner-check are one definition.
 */

/**
 * The operator id for a CP route = the **authenticated principal** (never the request body): a Kratos Human's
 * `identityId`, or the operator-token's configured [machineOperatorId]. Null for anything else (a MachineAgent never
 * reaches an OPERATOR gate — 403 first — so this is defensive).
 */
fun ApplicationCall.cpOperatorId(machineOperatorId: String?): String? = when (val p = attributes[PrincipalKey]) {
    is AuthPrincipal.Human -> p.identityId
    AuthPrincipal.MachineOperator -> machineOperatorId
    is AuthPrincipal.MachineAgent -> null
}

/**
 * The operator **OWNS** the hub: a non-blank [operatorId] equals the hub's `RegisteredHub.ownerId`. A blank/absent
 * operator, or an unknown / blank-owner hub, → false (fail-closed — blank never owns, folding in the CYP-512
 * blank-ownerId guard so no degenerate ""=="" owns). This is the ONE owner predicate the mint, resolve, and register
 * gates all use.
 */
fun HubRegistrar.ownedBy(hubId: String, operatorId: String?): Boolean =
    operatorId != null && operatorId.isNotBlank() && lookup(hubId)?.ownerId == operatorId
