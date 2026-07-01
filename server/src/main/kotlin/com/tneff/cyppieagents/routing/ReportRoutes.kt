package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.authenticatedApi
import com.tneff.cyppieagents.model.GenerateReportRequest
import com.tneff.cyppieagents.report.ReportStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Product-Lead report endpoints (S16 / CYP-89 — PRODUCT-LEAD §2). **All three are operator-gated and
 * fail-closed:** aggregating operator-gated observability must not become a side-channel, so a caller
 * without the operator token gets **no report at all** (not a partial) — the STRUCTURAL [authenticatedApi]
 * group (CYP-178) runs before any read or body parse. The items the store returns are content-free by construction (see
 * [com.tneff.cyppieagents.report.ReportGenerator]).
 */
fun Route.reportRoutes(store: ReportStore, registry: TokenRegistry, deps: AuthDeps = AuthDeps(registry)) {
    // CYP-178: operator gate is STRUCTURAL (by mounting under the group), not a per-handler call a new
    // endpoint could forget; the RC1 route-enumeration meta-test is the net. Operator = the static
    // operator token OR a Kratos-verified human with the OPERATOR role.
    authenticatedApi(deps, AuthRole.OPERATOR) {
        route("/api/reports") {
            get {
                call.respond(store.list())
            }
            post {
                val req = call.receive<GenerateReportRequest>()
                call.respond(HttpStatusCode.Created, store.generate(req)) // new immutable snapshot
            }
            get("/{id}") {
                val id = call.parameters["id"] ?: throw BadRequestException("missing report id")
                call.respond(store.get(id)) // 404 report_not_found
            }
        }
    }
}
