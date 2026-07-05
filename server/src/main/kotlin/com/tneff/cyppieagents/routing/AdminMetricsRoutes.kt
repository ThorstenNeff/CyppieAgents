package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.authenticatedApi
import com.tneff.cyppieagents.tier.DbMetricsProvider
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * CYP-220 Phase 5 — the admin DB-monitor endpoint (Design §7.4): `GET /api/admin/db/metrics`, **operator-gated**
 * (structural [authenticatedApi] group → fail-closed BEFORE any work). The [DbMetricsProvider] snapshot is
 * **content-free / secret-free** (dsnIds + masked host + numbers only). Live boot-wiring of the provider (from
 * the DsnRegistry + HikariCP pool stats + `pg_database_size`) lands with the store-wiring phase.
 */
fun Route.adminMetricsRoutes(provider: DbMetricsProvider, deps: AuthDeps) {
    route("/api/admin/db") {
        authenticatedApi(deps, AuthRole.OPERATOR) {
            get("/metrics") {
                call.respond(provider.snapshot())
            }
        }
    }
}
