package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.comm.HubState
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

/**
 * MVP auth (Spec 02 §14): a static bearer token per agent maps to an agent id; one separate
 * operator token authorizes ACL changes. The server maps token → agentId; the agent's identity
 * is therefore the *token*, never anything the client sends in a body (Reviewer Gate #1).
 */
class TokenRegistry(
    private val tokenToAgent: Map<String, String>,
    private val operatorToken: String?,
) {
    fun agentFor(token: String?): String? = token?.let { tokenToAgent[it] }
    fun isOperator(token: String?): Boolean = operatorToken != null && token == operatorToken
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

/** Requires the operator token, or throws 401/403. */
fun ApplicationCall.requireOperator(registry: TokenRegistry) {
    val token = bearerToken() ?: throw UnauthorizedException()
    if (!registry.isOperator(token)) {
        throw ForbiddenException("operator token required", code = "operator_required")
    }
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
