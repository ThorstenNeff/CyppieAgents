package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthPrincipal
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.resolvePrincipal
import com.tneff.cyppieagents.comm.HubState
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

/**
 * MVP auth (Spec 02 §14): a bearer token per agent maps to an agent id; one separate operator token
 * authorizes ACL changes. The server maps token → agentId; the agent's identity is therefore the
 * *token*, never anything the client sends in a body (Reviewer Gate #1).
 *
 * CYP-171 / E2.6 (S3): the map is **runtime-mutable** — boot seeds it from the env ([Secrets.agentTokens])
 * + the persisted remote tokens, and [mint] adds a server-generated per-agent token at runtime (operator
 * pre-provision of a remote/BYOA agent). The lookup semantics are UNCHANGED: [agentFor] stays the SOLE
 * identity source; mint only adds an entry, never a second identity path. Thread-safe.
 */
class TokenRegistry(
    seed: Map<String, String>,
    private val operatorToken: String?,
) {
    private val tokenToAgent = java.util.concurrent.ConcurrentHashMap(seed)

    fun agentFor(token: String?): String? = token?.let { tokenToAgent[it] }
    fun isOperator(token: String?): Boolean = operatorToken != null && token == operatorToken

    /**
     * CYP-171 — mint a cryptographically-random per-agent bearer token (256-bit `SecureRandom`,
     * base64url), register `token→agentId`, and return it. Server-generated, never client-chosen; the
     * returned value is the credential disclosed once. Regenerates on the (astronomically unlikely)
     * collision with an existing token or the operator token, so the new credential is always unique.
     */
    fun mint(agentId: String): String {
        var token: String
        do {
            token = secureToken()
        } while (tokenToAgent.containsKey(token) || token == operatorToken)
        tokenToAgent[token] = agentId
        return token
    }

    /** CYP-171 — revoke every token bound to [agentId] (idempotent; on agent removal). After this,
     *  `agentFor(<that token>)` is null → the next wire connect closes VIOLATED_POLICY (fail-closed). */
    fun revoke(agentId: String) {
        tokenToAgent.entries.removeIf { it.value == agentId }
    }

    /** CYP-171 — boot load of a persisted `(token→agentId)` binding (the secret-at-rest restore). */
    fun bind(token: String, agentId: String) {
        tokenToAgent[token] = agentId
    }

    private fun secureToken(): String {
        val bytes = ByteArray(32) // 256-bit
        java.security.SecureRandom().nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/** Extracts the bearer token from the Authorization header, or null. */
fun ApplicationCall.bearerToken(): String? {
    val header = request.header(HttpHeaders.Authorization) ?: return null
    if (!header.startsWith("Bearer ", ignoreCase = true)) return null
    return header.substring(7).trim().ifBlank { null }
}

/** Resolves the caller's agent id from the bearer token, or throws 401. */
fun ApplicationCall.requireAgent(registry: TokenRegistry): String {
    val token = bearerToken() ?: throw UnauthorizedException()
    return registry.agentFor(token) ?: throw UnauthorizedException()
}

/**
 * Resolves a token to a comm **participant** id: an agent id, or [HubState.OPERATOR_ID] for the
 * operator token, or null. The operator is a privileged participant in the ACL — not a bypass —
 * so downstream the same [com.tneff.cyppieagents.model.AclMatrix] checks apply (CYP-18).
 */
fun TokenRegistry.participantFor(token: String?): String? =
    agentFor(token) ?: if (isOperator(token)) HubState.OPERATOR_ID else null

/** Resolves the caller (agent OR operator) for the comm read/send endpoints, or throws 401. */
fun ApplicationCall.requireParticipant(registry: TokenRegistry): String =
    registry.participantFor(bearerToken()) ?: throw UnauthorizedException()

/**
 * CYP-186 BE2 — resolve the comm-**READ** participant. Token axis first (agent / operator — unchanged), then a
 * verified **human** session: a human OPERATOR maps to [HubState.OPERATOR_ID] (member-of-all, like the operator
 * token); a human MEMBER maps to their **identityId** — a first-class ACL read-subject, so
 * `acl.canRead(channel, identityId)` is **fail-closed empty** until an OPERATOR grants them per-channel read.
 * READ-ONLY by construction: the send path keeps [requireParticipant] (token axis only), so a MEMBER session
 * can never post — and the grant is written `canWrite:false`.
 */
suspend fun ApplicationCall.requireCommReader(deps: AuthDeps, registry: TokenRegistry): String {
    registry.participantFor(bearerToken())?.let { return it } // agent / operator token — unchanged
    return when (val p = resolvePrincipal(deps)) {
        is AuthPrincipal.Human -> if (p.role == AuthRole.OPERATOR) HubState.OPERATOR_ID else p.identityId
        else -> throw UnauthorizedException()
    }
}
