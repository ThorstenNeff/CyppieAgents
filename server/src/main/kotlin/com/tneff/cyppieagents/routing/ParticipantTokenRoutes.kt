package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.authenticatedApi
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** CYP-234b-3 (#8) — mint a participant-scoped token for a BYO-machine consumer. */
@Serializable
data class MintParticipantTokenRequest(val subject: String, val ttlMs: Long? = null)

/** The mint response — the raw [token] is disclosed EXACTLY ONCE here (never listed / logged / re-rendered). */
@Serializable
data class MintedParticipantToken(val token: String, val subject: String, val expiresAtMs: Long?)

/** Secret-free list entry: WHO holds a token + when it expires — never the token or its hash. */
@Serializable
data class ParticipantTokenSummary(val subject: String, val issuedAtMs: Long, val expiresAtMs: Long?)

/**
 * CYP-234b-3 (#8) — the operator-gated mint/revoke admin path for the participant-token class. STRUCTURALLY
 * operator-gated ([authenticatedApi] `OPERATOR` group → fail-closed 401/403 before any work); the mint is the
 * ONLY place a participant credential is issued. Responses are **secret-free** except the one-time mint
 * disclosure: the list never returns a token or hash. Uses the SAME [AuthDeps.participantTokens] instance the
 * read-tier resolvers resolve from (single shared store), so a minted token is immediately usable and a revoked
 * one immediately denied.
 */
fun Route.participantTokenRoutes(deps: AuthDeps, apiBase: String = "/api") {
    authenticatedApi(deps, AuthRole.OPERATOR) {
        route("$apiBase/participant-tokens") {
            post {
                val req = call.receive<MintParticipantTokenRequest>()
                val raw = deps.participantTokens.mint(req.subject, req.ttlMs)
                val expiresAt = req.ttlMs?.let { deps.nowMs() + it }
                call.respond(HttpStatusCode.Created, MintedParticipantToken(raw, req.subject, expiresAt))
            }
            get {
                call.respond(deps.participantTokens.summaries().map { ParticipantTokenSummary(it.subject, it.issuedAtMs, it.expiresAtMs) })
            }
            delete {
                val subject = call.request.queryParameters["subject"]
                    ?: throw BadRequestException("subject query parameter required", code = "subject_required")
                val removed = deps.participantTokens.revokeSubject(subject)
                call.respond(HttpStatusCode.OK, RevokedParticipantTokens(subject, removed))
            }
        }
    }
}

/** Revoke receipt — how many of [subject]'s tokens were dropped (idempotent). */
@Serializable
data class RevokedParticipantTokens(val subject: String, val revoked: Int)
