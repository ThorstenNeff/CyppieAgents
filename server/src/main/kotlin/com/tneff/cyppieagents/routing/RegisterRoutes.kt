package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.RegisterMediator
import com.tneff.cyppieagents.auth.RegisterOutcome
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/**
 * The CONSTANT register response body — byte-identical for a NEW and an EXISTING email (the §B closure). The
 * client shows "check your email" either way; the mail (verify-your-account vs you-already-have-an-account)
 * carries the branch, never the HTTP response.
 */
const val REGISTER_GENERIC_BODY = """{"status":"verification_pending"}"""

/**
 * CYP-179 / §B(b) — the platform **register-wrapper** endpoint: `POST /api/auth/register { email, password }`.
 * Deliberately **PUBLIC** (unauthenticated — anyone may register), so it is on the route-enum public allowlist
 * (audited there, like `GET /api/auth/me`). It is the platform's ONLY app register path (MUST-1). All the
 * anti-enumeration logic lives in [RegisterMediator]; this handler only guarantees the RESPONSE is
 * branch-invariant: the SAME [REGISTER_GENERIC_BODY] + status for new and existing.
 *
 * Deploy assumptions (documented, coordinated by the PO — this wrapper does NOT depend on them at build time):
 *  - the raw Kratos self-service register (`:4433`) is made **off-box unreachable** at the edge (RC4), so this
 *    wrapper is the only reachable register path off localhost;
 *  - the per-IP **edge throttle (G3)** fronts this endpoint (same layer as login, §A) — the throttle is not
 *    in-app.
 */
fun Route.registerRoutes(mediator: RegisterMediator) {
    post("/api/auth/register") {
        val req = call.receive<RegisterRequest>()
        // Existence-INDEPENDENT input validation only (a malformed input is the same for everyone → not an
        // enumeration oracle). Never branches on whether the email exists.
        if (req.email.isBlank() || req.password.isBlank()) {
            return@post call.respondText(
                """{"error":"invalid_request"}""", ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
        }
        val status = when (mediator.register(req.email.trim(), req.password)) {
            RegisterOutcome.ACCEPTED -> HttpStatusCode.OK          // new AND existing → identical
            RegisterOutcome.UNAVAILABLE -> HttpStatusCode.ServiceUnavailable // admin outage → uniform for all (MUST-3)
        }
        call.respondText(REGISTER_GENERIC_BODY, ContentType.Application.Json, status)
    }
}

/** `{ email, password }` — the only register inputs; nothing existence-revealing leaves the handler. */
@Serializable
data class RegisterRequest(val email: String, val password: String)
