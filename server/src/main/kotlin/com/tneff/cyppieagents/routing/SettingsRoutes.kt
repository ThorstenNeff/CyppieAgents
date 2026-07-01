package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.KratosSettingsClient
import com.tneff.cyppieagents.auth.authenticatedApi
import com.tneff.cyppieagents.auth.sessionCredential
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * CYP-181 / P2.4 — the authenticated self-management surface (change password / email). A **thin,
 * platform-mediated shim** behind the P1 structural guard at **MEMBER** (the first real MEMBER-tier use):
 *
 *  - `POST /api/auth/settings/password` `{ "newPassword": … }`
 *  - `POST /api/auth/settings/email`    `{ "newEmail": … }`
 *
 * Reviewer conditions (AC): **(a/C)** the new value is passed straight through to Kratos — never logged or
 * stored (see [KratosSettingsClient]); **(b)** all security is Kratos's (session, `privileged_session_max_age`,
 * hashing, re-verification); **(c/B)** self-scoped — the acted-on identity is the caller's session, there is
 * **no `identityId` in the body** (nothing here reads one), so a member can only change their OWN account.
 * **(A)** the email request pins exactly one `newEmail`. **(F)** these cookie-authed POSTs are covered by the
 * RC5 CSRF double-submit (enforced structurally in [authenticatedApi]).
 *
 * A caller with no browser/native **session** (e.g. the machine operator token) has no Kratos identity to
 * change → **403 `session_required`** (fail-closed).
 */
fun Route.settingsRoutes(deps: AuthDeps, kratos: KratosSettingsClient) {
    authenticatedApi(deps, AuthRole.MEMBER) {
        route("/api/auth/settings") {
            post("/password") {
                val cred = call.sessionCredential()
                    ?: throw ForbiddenException("a browser/native session is required to change settings", code = "session_required")
                val req = call.receive<ChangePasswordRequest>()
                val outcome = kratos.changePassword(cred, req.newPassword)
                call.respondText(outcome.body, ContentType.Application.Json, HttpStatusCode.fromValue(outcome.status))
            }
            post("/email") {
                val cred = call.sessionCredential()
                    ?: throw ForbiddenException("a browser/native session is required to change settings", code = "session_required")
                val req = call.receive<ChangeEmailRequest>()
                val outcome = kratos.changeEmail(cred, req.newEmail)
                call.respondText(outcome.body, ContentType.Application.Json, HttpStatusCode.fromValue(outcome.status))
            }
        }
    }
}

/** (A) single-value schemas — exactly one field; no `identityId` (self-scoped, condition c/B). */
@Serializable
data class ChangePasswordRequest(val newPassword: String)

@Serializable
data class ChangeEmailRequest(val newEmail: String)
