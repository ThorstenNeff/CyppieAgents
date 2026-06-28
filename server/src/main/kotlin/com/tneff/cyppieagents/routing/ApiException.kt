package com.tneff.cyppieagents.routing

import io.ktor.http.HttpStatusCode

/**
 * Domain errors mapped to the uniform error envelope `{ error: { code, message } }` (Spec 02 §7)
 * by the StatusPages handler. Carrying the HTTP status here keeps the routes declarative.
 */
sealed class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
) : RuntimeException(message)

class UnauthorizedException(message: String = "missing or invalid bearer token") :
    ApiException(HttpStatusCode.Unauthorized, "unauthorized", message)

class ForbiddenException(message: String, code: String = "forbidden") :
    ApiException(HttpStatusCode.Forbidden, code, message)

class NotFoundException(message: String = "not found") :
    ApiException(HttpStatusCode.NotFound, "not_found", message)

class BadRequestException(message: String) :
    ApiException(HttpStatusCode.BadRequest, "bad_request", message)

/**
 * The request is well-formed and authorized but would violate a hub invariant that the server is
 * the source of truth for — e.g. locking the PO out of a channel it is the hub of (CYP-49).
 * 409 Conflict, fail-closed: nothing is persisted.
 */
class ConflictException(message: String, code: String = "conflict") :
    ApiException(HttpStatusCode.Conflict, code, message)
