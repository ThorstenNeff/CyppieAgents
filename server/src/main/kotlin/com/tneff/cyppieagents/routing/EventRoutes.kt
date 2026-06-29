package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.events.EventFilter
import com.tneff.cyppieagents.events.EventSink
import com.tneff.cyppieagents.events.Page
import com.tneff.cyppieagents.model.ApiError
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * `/api/events` — historical Browse over the Event-Log (PRD §6, ST5/CYP-39).
 *
 * **Operator-only, fail-closed (PRD §7):** the Event-Log aggregates team-wide, cross-agent metadata,
 * so a single agent reading it would be a cross-agent metadata leak of the same class as the filtered
 * `AclEvent` (CYP-18). [requireOperator] enforces it like `PUT /api/acl`: a missing token → 401, an
 * agent token → 403.
 *
 * Filters arrive as **query params** (not a JSON body, mirroring `CommApi.messages`); the time window
 * is half-open `[since, until)`; paging is stable over `seq` (`afterSeq` cursor + `limit`).
 */
fun Route.eventRoutes(
    sink: EventSink,
    registry: TokenRegistry,
    activeProjectId: () -> String?,
    // S17 / CYP-94: the operator's authorized project set (MVP = all of the registry's projects). The
    // override `?projectId=` is honored only for ids in this set; defaulted empty so legacy installs
    // never honor an override (forced-active stays the only behavior).
    authorizedProjects: () -> Set<String> = { emptySet() },
) {
    route("/api/events") {
        get {
            call.requireOperator(registry) // fail-closed before any query runs
            val q = call.request.queryParameters
            val filter = EventFilter(
                agentId = q["agentId"],
                type = q["type"]?.let { parseType(it) },
                severity = q["severity"]?.let { parseSeverity(it) },
                since = q["since"]?.let { parseLong(it, "since") },
                until = q["until"]?.let { parseLong(it, "until") },
                correlationId = q["correlationId"],
                sessionId = q["sessionId"],
                // S13 / CYP-102 default = forced-active; S17 / CYP-94 operator-only override resolved here
                // (single resolver, also used by /ws/events): `?projectId=<id>|all`, bounded to the
                // operator's authorized set, fail-closed to active on anything unauthorized.
                projectId = resolveEventScope(q["projectId"], activeProjectId(), authorizedProjects()),
            )
            val afterSeq = q["afterSeq"]?.let { parseLong(it, "afterSeq") }
            val limit = (q["limit"]?.let { parseInt(it, "limit") } ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
            call.respond(sink.query(filter, Page(afterSeq = afterSeq, limit = limit)))
        }
    }
}

/**
 * Standalone install (plugins + route) for tests / a dedicated events surface. In production the
 * route is mounted by `installPlatform`, which already installs ContentNegotiation + StatusPages and
 * ALWAYS passes the registry active-pointer resolver. [activeProjectId] defaults to unscoped here so
 * legacy single-store event tests are unaffected; production is never unscoped (CYP-102).
 */
fun Application.installEvents(
    sink: EventSink,
    registry: TokenRegistry,
    activeProjectId: () -> String? = { null },
    authorizedProjects: () -> Set<String> = { emptySet() },
) {
    install(ContentNegotiation) { json(CommJson) }
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ApiErrorBody(ApiError(cause.code, cause.message)))
        }
        exception<Throwable> { call, _ ->
            call.respond(HttpStatusCode.InternalServerError, ApiErrorBody(ApiError("internal", "internal error")))
        }
    }
    routing { eventRoutes(sink, registry, activeProjectId, authorizedProjects) }
}

private const val DEFAULT_LIMIT = 100
private const val MAX_LIMIT = 1000

// Strict parsing: an unparseable filter is a 400, not a silently-empty result — the operator must
// know their filter was rejected. `type=unknown` is valid (the modelled sentinel); `type=garbage` 400s.
private fun parseType(raw: String): EventType =
    EventType.entries.firstOrNull { it.wire == raw }
        ?: throw BadRequestException("unknown event type: $raw")

private fun parseSeverity(raw: String): Severity =
    Severity.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: throw BadRequestException("unknown severity: $raw")

private fun parseLong(raw: String, name: String): Long =
    raw.toLongOrNull() ?: throw BadRequestException("invalid $name: $raw")

private fun parseInt(raw: String, name: String): Int =
    raw.toIntOrNull() ?: throw BadRequestException("invalid $name: $raw")
