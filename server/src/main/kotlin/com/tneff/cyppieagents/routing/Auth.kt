package com.tneff.cyppieagents.routing

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
