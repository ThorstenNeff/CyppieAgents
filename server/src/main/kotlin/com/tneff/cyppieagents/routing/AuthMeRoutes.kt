package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.resolveAuthState
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
fun Route.authMeRoutes(deps: AuthDeps, apiBase: String = "/api") {
    // resolveAuthState is a SINGLE idp.resolve (no double whoami on a present garbage/unverified token).
    get("$apiBase/auth/me") { call.respond(call.resolveAuthState(deps)) }
}
