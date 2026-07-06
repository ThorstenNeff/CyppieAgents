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

class NotFoundException(message: String = "not found", code: String = "not_found") :
    ApiException(HttpStatusCode.NotFound, code, message)

/** A bounded server action could not be carried out (e.g. an agent process failed to respawn, CYP-73). */
class ServiceUnavailableException(message: String, code: String = "unavailable") :
    ApiException(HttpStatusCode.ServiceUnavailable, code, message)

class BadRequestException(message: String, code: String = "bad_request") :
    ApiException(HttpStatusCode.BadRequest, code, message)

/** The request body exceeds a bounded size cap (CYP-143). 413, fail-closed: nothing is sent/injected. */
class PayloadTooLargeException(message: String, code: String = "payload_too_large") :
    ApiException(HttpStatusCode.PayloadTooLarge, code, message)

/**
 * The request is well-formed and authorized but would violate a hub invariant that the server is
 * the source of truth for — e.g. locking the PO out of a channel it is the hub of (CYP-49).
 * 409 Conflict, fail-closed: nothing is persisted.
 */
class ConflictException(message: String, code: String = "conflict") :
    ApiException(HttpStatusCode.Conflict, code, message)

/**
 * CYP-255 / CYP-259 — the active project has no live runtime (not yet activated, or LRU-evicted by the
 * teardown policy). Fail-closed: rather than silently falling back to another project's lifecycle (the very
 * cross-project bleed L exists to prevent), [com.tneff.cyppieagents.boot.RuntimeRegistry.active] throws this,
 * and the route boundary maps it to a clean **409** ("not runnable yet") instead of a 500. In normal flow it
 * never fires — a switch mints the target's runtime (getOrCreate) BEFORE it becomes active.
 */
class ProjectNotRunnableException(message: String, code: String = "project_not_runnable") :
    ApiException(HttpStatusCode.Conflict, code, message)
