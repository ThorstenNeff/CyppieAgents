package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.authenticatedApi
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
 * ★ **ENVELOPE FLAG — Reviewer MUST-ASSESS (operator-gate vs per-hub-owner-gate on resolve):** both verbs are
 * **operator-gated** ([authenticatedApi] `OPERATOR`) — the conservative fail-closed default. resolve reveals
 * `hubId ↔ rendezvous` to ANY operator, so a non-owner operator could learn the id + pull unauthenticated Noise
 * handshakes against the hub (a **minor hub-liveness info-leak + a minor DoS surface**) — but they fail downstream at
 * the CYP-508 mint owner-check (`RegisteredHub.ownerId`) + the client dhPubKey TOFU-pin, so they never reach
 * CONNECTED. MVP-acceptable (authz is enforced at the mint), but the Reviewer weighs **defense-in-depth (owner-gate
 * resolve too)** EXPLICITLY — not silently. The **register** side (a hub publishing its OWN rendezvous) has a parallel
 * open question: hub-admitted-CP-identity (CYP-451 `HubRegistrar`) vs operator session. Both are envelope decisions,
 * flagged not invented; operator-gating can only over-restrict, never under-gate, so it is safe as the placeholder.
 */
fun Route.rendezvousRoutes(
    rendezvous: () -> RelayRendezvous,
    registry: TokenRegistry,
    deps: AuthDeps = AuthDeps(registry),
    apiBase: String = "/api",
) {
    authenticatedApi(deps, AuthRole.OPERATOR) {
        route("$apiBase/cp/rendezvous/{hubId}") {
            // resolve (client CYP-494): the opaque rendezvous for hubId. Business outcome = 200 + typed body
            // (RELAY_UNAVAILABLE when INERT, NOT_REGISTERED when live-but-no-rendezvous); auth failures stay 401/403.
            get {
                val hubId = call.parameters["hubId"] ?: throw BadRequestException("missing hubId")
                val rz = rendezvous()
                val response = when {
                    !rz.isLive() -> RendezvousResolveResponse(failure = RendezvousFailure.RELAY_UNAVAILABLE)
                    else -> rz.resolve(hubId)?.let { RendezvousResolveResponse(binding = it) }
                        ?: RendezvousResolveResponse(failure = RendezvousFailure.NOT_REGISTERED)
                }
                call.respond(response)
            }
            // register (hub publishes its rendezvous): rotate the CP-secret epoch, return the opaque id. Same
            // typed-body idiom — register yields null ONLY when INERT (a live relay always mints a binding).
            post {
                val hubId = call.parameters["hubId"] ?: throw BadRequestException("missing hubId")
                val response = rendezvous().register(hubId)?.let { RendezvousResolveResponse(binding = it) }
                    ?: RendezvousResolveResponse(failure = RendezvousFailure.RELAY_UNAVAILABLE)
                call.respond(response)
            }
        }
    }
}
