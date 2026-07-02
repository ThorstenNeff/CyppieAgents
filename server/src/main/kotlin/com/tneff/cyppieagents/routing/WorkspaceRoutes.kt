package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.authenticatedApi
import com.tneff.cyppieagents.model.WorkspaceMember
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * CYP-186 BE3a — the **OPERATOR-only** workspace roster. `GET /api/workspace/members` → [WorkspaceMember]
 * list, structurally OPERATOR-gated under [authenticatedApi] (a MEMBER → **403 content-free**, no contact-dump;
 * the RC1 route-enumeration meta-test is the net). Content = identityId + tier only — **no email, no secret**.
 * [WorkspaceMember.displayName] is deferred (null) until the Kratos admin-API seam + a name trait land.
 */
fun Route.workspaceRoutes(deps: AuthDeps) {
    authenticatedApi(deps, AuthRole.OPERATOR) {
        get("/api/workspace/members") {
            call.respond(deps.roles.list().map { WorkspaceMember(it.identityId, it.role.name) })
        }
    }
}
