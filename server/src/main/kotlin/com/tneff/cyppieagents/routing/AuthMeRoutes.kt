package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.resolvePrincipal
import com.tneff.cyppieagents.auth.sessionCredential
import com.tneff.cyppieagents.model.AuthMe
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * CYP-182 / P3 — `GET /api/auth/me`, the client whoami read. Deliberately **public** (no guard): it must be
 * callable by an UNauthenticated caller to answer "am I logged in?" with `{authenticated:false}` rather than a
 * 401. It returns only the content-free [AuthMe] snapshot (no id / email / secrets), derived from the P1
 * principal, so the Compose client can drive `AuthState` without inferring it from guarded-route status codes.
 *
 * Three states: a resolvable principal (operator token / agent token / **verified** human) → authenticated +
 * role + verified; a valid-but-**unverified** Kratos session → authenticated but `verified=false`, no role yet
 * (RC1 — the client shows "verify your email"); neither → `{authenticated:false}`.
 */
fun Route.authMeRoutes(deps: AuthDeps) {
    get("/api/auth/me") {
        val principal = call.resolvePrincipal(deps)
        val me = when {
            // Operator token / agent token / verified human — resolvePrincipal is non-null only when verified.
            principal != null -> AuthMe(authenticated = true, role = principal.role.name, verified = true)
            // No principal: distinguish a valid-but-unverified session from no session at all.
            else -> {
                val resolved = deps.idp.resolve(call.sessionCredential())
                if (resolved != null) {
                    AuthMe(authenticated = true, role = null, verified = resolved.verified) // verified==false here
                } else {
                    AuthMe(authenticated = false)
                }
            }
        }
        call.respond(me)
    }
}
