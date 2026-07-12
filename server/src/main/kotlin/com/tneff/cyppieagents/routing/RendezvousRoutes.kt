package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.authenticatedApi
import com.tneff.cyppieagents.controlplane.HubRegistrar
import com.tneff.cyppieagents.controlplane.RelayRendezvous
import com.tneff.cyppieagents.controlplane.RendezvousFailure
import com.tneff.cyppieagents.controlplane.RendezvousResolveResponse
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * CYP-507 (Epic CYP-427 Phase-2, activation) — the CP-side **rendezvous endpoint** (register/resolve) over the
 * [RelayRendezvous] seam. The client (Dev CYP-494 `RelayDialer`) resolves a hub's opaque rendezvous; the hub
 * publishes (registers) its rendezvous. The relay never sees `hubId` — only the derived opaque id (RR4).
 *
 * **INERT until the Phase-2-Remote-GO:** with no live relay the seam yields `null`, so resolve is a 404 and register
 * a 503 — mounting this changes nothing observable until `CYPPIE_REMOTE_RELAY_URL` is set (then the seam is
 * [com.tneff.cyppieagents.controlplane.LiveRelayRendezvous]).
 *
 * ★ **CYP-511 (Defense-in-Depth, closes the CYP-507 envelope flag): resolve is OWNER-gated.** Both verbs are
 * operator-gated ([authenticatedApi] `OPERATOR`); additionally **resolve requires the operator to OWN the hub**
 * (`RegisteredHub.ownerId == operatorId`, the same check the CYP-508 mint applies). This closes the residual a
 * non-owner operator otherwise had (learning `hubId ↔ rendezvous` + pulling unauthenticated Noise handshakes = a
 * minor liveness-leak + DoS surface); a non-owner is now `NOT_REGISTERED` (non-leaky) **before** the id is derived.
 * Owner ⊆ operator, so this only tightens — it never breaks a legitimate resolution.
 *
 * The **register** side (a hub publishing its OWN rendezvous) keeps operator-gating: its auth model (hub-admitted
 * CP-identity via CYP-451 `HubRegistrar` vs operator session) is a **separate DEFERRED envelope decision**, not this
 * ticket — flagged, not invented; operator-gating can only over-restrict, never under-gate.
 */
fun Route.rendezvousRoutes(
    rendezvous: () -> RelayRendezvous,
    registrar: () -> HubRegistrar,
    registry: TokenRegistry,
    deps: AuthDeps = AuthDeps(registry),
    apiBase: String = "/api",
    /** The operator-token (machine) principal's identity for the owner-check — the configured operator id
     *  (`CYPPIE_OPERATOR_ID`), or null when unset (→ non-owner, fail-closed). A Kratos Human uses its `identityId`. */
    machineOperatorId: String? = null,
) {
    authenticatedApi(deps, AuthRole.OPERATOR) {
        route("$apiBase/cp/rendezvous/{hubId}") {
            // resolve (client CYP-494): the opaque rendezvous for hubId. Business outcome = 200 + typed body
            // (RELAY_UNAVAILABLE when INERT, NOT_REGISTERED when live-but-no-rendezvous); auth failures stay 401/403.
            get {
                val hubId = call.parameters["hubId"] ?: throw BadRequestException("missing hubId")
                val rz = rendezvous()
                if (!rz.isLive()) {
                    call.respond(RendezvousResolveResponse(failure = RendezvousFailure.RELAY_UNAVAILABLE))
                    return@get
                }
                // CYP-511 owner-gate (Defense-in-Depth): ONLY the hub's owner may resolve. A non-owner operator (or an
                // unknown hub) is NOT_REGISTERED — **non-leaky** (indistinguishable from "no rendezvous") and decided
                // BEFORE the opaque id is derived, so it never leaks the hubId↔rendezvous mapping nor a liveness probe.
                // Same `RegisteredHub.ownerId == operatorId` check the CYP-508 mint applies; owner ⊆ operator → only
                // tightens. CYP-516: via the shared [cpOperatorId] / [ownedBy] helper (one definition, no drift).
                val response = if (!registrar().ownedBy(hubId, call.cpOperatorId(machineOperatorId))) {
                    RendezvousResolveResponse(failure = RendezvousFailure.NOT_REGISTERED)
                } else {
                    rz.resolve(hubId)?.let { RendezvousResolveResponse(binding = it) }
                        ?: RendezvousResolveResponse(failure = RendezvousFailure.NOT_REGISTERED)
                }
                call.respond(response)
            }
            // register (hub publishes its rendezvous): rotate the CP-secret epoch, return the opaque id.
            post {
                val hubId = call.parameters["hubId"] ?: throw BadRequestException("missing hubId")
                val rz = rendezvous()
                if (!rz.isLive()) {
                    call.respond(RendezvousResolveResponse(failure = RendezvousFailure.RELAY_UNAVAILABLE))
                    return@post
                }
                // CYP-516 owner-gate (symmetric to the CYP-511 resolve gate): ONLY the hub's owner may register/rotate
                // its rendezvous. Otherwise ANY operator could epoch-rotate ANY hub as a DoS (the hub keeps dialing the
                // stale id while the owner resolves the new one → no pairing). A non-owner is NOT_REGISTERED — non-leaky
                // and decided BEFORE any rotation, so it neither rotates nor reveals the hubId↔rendezvous mapping.
                if (!registrar().ownedBy(hubId, call.cpOperatorId(machineOperatorId))) {
                    call.respond(RendezvousResolveResponse(failure = RendezvousFailure.NOT_REGISTERED))
                    return@post
                }
                val response = rz.register(hubId)?.let { RendezvousResolveResponse(binding = it) }
                    ?: RendezvousResolveResponse(failure = RendezvousFailure.RELAY_UNAVAILABLE)
                call.respond(response)
            }
        }
    }
}
