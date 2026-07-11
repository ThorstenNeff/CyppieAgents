package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.authenticatedApi
import com.tneff.cyppieagents.boot.ResourceGovernor
import com.tneff.cyppieagents.model.Capacity
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * CYP-417 (S-G) — `GET /api/capacity`: the **server-authoritative** capacity read for the UI's capacity pill
 * (the mount-time snapshot; live updates arrive via the `capacity.changed` event). Content-free
 * `{current, estimatedMax?}`. MEMBER-tier — the counters aren't sensitive, but the endpoint sits on the SAME
 * auth line as the other `/api` reads (no anonymous access, no special opening).
 *
 * **Same source as `capacity.changed` (no divergence):** [runningCount] is the active runtime's RUNNING count —
 * the exact count `admitSpawn` gates on and the `capacity.changed` event carries — and [governor] is the same
 * estimate. `estimatedMax` is null (omitted) when the hub has no reliable estimate (`null≠0`).
 */
fun Route.capacityRoutes(
    governor: () -> ResourceGovernor?,
    runningCount: () -> Int,
    registry: TokenRegistry,
    deps: AuthDeps = AuthDeps(registry),
    apiBase: String = "/api",
) {
    authenticatedApi(deps, AuthRole.MEMBER) {
        route("$apiBase/capacity") {
            get {
                call.respond(Capacity(current = runningCount(), estimatedMax = governor()?.estimatedMax()))
            }
        }
    }
}
