package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.PrincipalKey
import com.tneff.cyppieagents.auth.authenticatedApi
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.events.EventFilter
import com.tneff.cyppieagents.events.EventSink
import com.tneff.cyppieagents.events.Page
import com.tneff.cyppieagents.model.AclMatrix
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
 * **MEMBER-tier, fail-closed (CYP-186 BE2):** the Event-Log is secret-free, cross-agent *metadata* (never
 * bodies), so it is readable at the MEMBER tier — a verified human MEMBER (or an agent/operator token), NOT
 * operator-only. The STRUCTURAL [authenticatedApi] group (CYP-178) enforces it: a missing credential → 401.
 * The cross-project `?projectId=` override stays OPERATOR-only (a non-operator is forced to the active
 * project). (Historical note: this was operator-only pre-CYP-186; the read tier was widened to MEMBER there.)
 *
 * **CYP-719:** `comm.*` events additionally carry a channel, so they are ACL-narrowed here ([aclQuery]) — a
 * non-operator sees a `comm.sent`/`comm.received` row only for a channel it may `canRead` (operator bypasses),
 * closing the leak where any MEMBER learned "who posted where/when" without a per-channel grant.
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
    deps: AuthDeps = AuthDeps(registry),
    // CYP-719: the CURRENT ACL (live var, rebuilt on every grant/project switch) for the comm.* read-filter.
    // Defaulted to a FAIL-CLOSED empty matrix (canRead → false for every non-operator) so a caller that omits
    // it can never fall OPEN — production (PlatformWiring) always passes `{ booted.hub.state.acl }`.
    acl: () -> AclMatrix = { AclMatrix(emptyList(), emptyList()) },
    apiBase: String = "/api",
) {
    // CYP-186 BE2: the event-log is secret-free metadata → readable at the MEMBER tier (gate STRUCTURAL,
    // fail-closed; the RC1 route-enumeration meta-test is the net). The cross-project `?projectId` override
    // stays OPERATOR-only: a non-operator (agent / human MEMBER) is FORCED to the active project so the
    // event read can never enumerate or leak OTHER projects (guardrail b).
    authenticatedApi(deps, AuthRole.MEMBER) {
        route("$apiBase/events") {
            get {
                val q = call.request.queryParameters
                // CYP-240 (A) + CYP-719 §3: reuse the principal the STRUCTURAL AuthGuard already resolved +
                // stashed under [PrincipalKey] — do NOT call resolvePrincipal again (that was a 2nd live Kratos
                // whoami per /api/events request, guarded by EventsPrincipalReuseTest). The handler runs inside
                // the authenticatedApi(MEMBER) group, so PrincipalKey is always present. [commReadSubjectOf]
                // derives the ACL read-subject from that same stash (whoami-free), matching the WS resolver
                // vocab; `isOperator` is then just "the subject is the member-of-all operator".
                val subject = commReadSubjectOf(call.attributes[PrincipalKey])
                val isOperator = subject == HubState.OPERATOR_ID
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
                    // operator's authorized set, fail-closed to active. Non-operators: override IGNORED → active only.
                    projectId = resolveEventScope(
                        if (isOperator) q["projectId"] else null, activeProjectId(), authorizedProjects(),
                    ),
                )
                val afterSeq = q["afterSeq"]?.let { parseLong(it, "afterSeq") }
                val limit = (q["limit"]?.let { parseInt(it, "limit") } ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
                // CYP-719: ACL-filter comm.* down to channels the subject may canRead (operator bypasses),
                // over-fetching so the `limit`/`nextAfterSeq` cursor stays correct across denied rows.
                call.respond(aclQuery(sink, filter, Page(afterSeq = afterSeq, limit = limit), subject, isOperator, acl()))
            }
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
    acl: () -> AclMatrix = { AclMatrix(emptyList(), emptyList()) }, // CYP-719: fail-closed default
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
    routing { eventRoutes(sink, registry, activeProjectId, authorizedProjects, acl = acl) }
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
