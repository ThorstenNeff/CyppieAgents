package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.authenticatedApi
import com.tneff.cyppieagents.controlplane.HubRegistrar
import com.tneff.cyppieagents.controlplane.RelayRendezvous
import com.tneff.cyppieagents.controlplane.toDescriptor
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * CYP-530 (Epic CYP-427 Phase-2, S-J) — the operator-facing **hub-discovery** endpoint `GET {apiBase}/cp/hubs`: the
 * list the GUI's hub picker consumes (client `ControlPlaneClient.hubs()`). It projects the CP registry the CYP-512
 * admission flow populates — a hub **self-admits** at boot (server→CP `HubAdmissionClient`), so the operator only
 * LISTS + SELECTS; there is no operator-facing *register* (that DTO is vestigial for M1).
 *
 * Security stance — pure reuse, no new gate:
 *  - **Operator-gated** ([authenticatedApi] `OPERATOR`) — same tier as the rest of the CP surface.
 *  - **Owner-scoped:** only hubs whose `RegisteredHub.ownerId` equals the **authenticated** operator
 *    ([cpOperatorId], never a request field) are returned — via [HubRegistrar.hubsOwnedBy], which is fail-closed on a
 *    blank/null operator (owns nothing). So op-A can never enumerate op-B's hubs.
 *  - **Zero-knowledge preserved:** the registry stores identity/routing only (no presence). `online` is derived
 *    **cheaply + honestly** from the live rendezvous — a hub that has published its rendezvous ([RelayRendezvous.resolve]
 *    non-null) is actually relay-reachable; INERT relay ⇒ null ⇒ `online=false` (honest: no remote reachability). No
 *    presence feed is added. `lastSeen` = the hub's `admittedAt` (it re-admits each boot). Both are advisory (H1) —
 *    never a connection guarantee (the connect feed S-2 is the truth).
 *
 * **INERT-safe:** mounted always (contract honesty), but the registry is empty until a hub self-admits — so this
 * returns `[]` until `CYPPIE_CP_URL`/`CYPPIE_OPERATOR_ID`/custody let the boot self-admit run. It needs no relay URL
 * itself (it only reads the registry); `online` simply stays false while the relay is INERT.
 */
fun Route.hubDiscoveryRoutes(
    registrar: () -> HubRegistrar,
    rendezvous: () -> RelayRendezvous,
    registry: TokenRegistry,
    deps: AuthDeps = AuthDeps(registry),
    apiBase: String = "/api",
    /** The operator-token (machine) principal's id for the owner-scope — the configured `CYPPIE_OPERATOR_ID`, or null
     *  when unset (→ owns nothing, fail-closed). A Kratos Human uses its `identityId`. Same helper as the CP gates. */
    machineOperatorId: String? = null,
) {
    authenticatedApi(deps, AuthRole.OPERATOR) {
        get("$apiBase/cp/hubs") {
            val opId = call.cpOperatorId(machineOperatorId)
            val rz = rendezvous()
            val hubs = registrar().hubsOwnedBy(opId).map { hub ->
                // online = the hub has a live rendezvous published (relay-reachable) — a cheap map lookup, no presence
                // feed, zero-knowledge preserved. lastSeen = last admit (re-admits each boot). Both advisory (H1).
                hub.toDescriptor(online = rz.resolve(hub.hubId) != null, lastSeen = hub.admittedAt)
            }
            call.respond(hubs)
        }
    }
}
