package com.tneff.cyppieagents.auth

import com.tneff.cyppieagents.routing.ForbiddenException
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.UnauthorizedException
import com.tneff.cyppieagents.routing.bearerToken
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.util.AttributeKey

/** The Kratos browser session cookie name (native clients use the `X-Session-Token` header instead). */
const val KRATOS_SESSION_COOKIE = "ory_kratos_session"

/** The stashed principal for a guarded call (set by [authenticatedApi]). */
val PrincipalKey = AttributeKey<AuthPrincipal>("cyppie.AuthPrincipal")

/** Everything a principal resolution needs: the machine-token registry, the IdP seam, the role store. */
class AuthDeps(
    val tokens: TokenRegistry,
    val idp: IdentityProvider,
    val roles: RoleStore,
    val nowMs: () -> Long,
    /** CYP-186 C.1 — OPERATOR-only audit sink; the guard records every OPERATOR mutation here (default no-op). */
    val audit: AuditSink = NoOpAuditSink,
    /** CYP-186 C.2 — deploy kill-switch (boot/env only). Effective ONLY once a role-OPERATOR exists (never lock out). */
    val operatorTokenDisabled: Boolean = false,
    /** CYP-234b — the participant-scoped BYO-machine token class. Resolves a participant token to a read-SUBJECT
     *  (like a human MEMBER's identityId — the ACL is the only authz). Default = an empty store (no participant
     *  tokens → behavior unchanged) until the 234b-3 admin path mints them. */
    val participantTokens: ParticipantTokenStore = ParticipantTokenStore(nowMs),
    /** CYP-234b-3 (#7) — per-participant-SUBJECT token-bucket (reuses the CYP-161 WireRateLimiter). Keyed by the
     *  RESOLVED subject (a bounded, operator-assigned set — RC3: never an attacker-supplied value), so a flood on
     *  one BYO consumer is throttled (429) without starving others. One shared instance across the server. */
    val participantRateLimiter: com.tneff.cyppieagents.routing.WireRateLimiter = com.tneff.cyppieagents.routing.WireRateLimiter(),
) {
    /**
     * Token-only convenience (tests + the operator-token-only mount default): the human-auth path is
     * **deny-all** (no Kratos), so only the static operator token authenticates. Production wires the
     * real [IdentityProvider] + [SqliteRoleStore] via the primary constructor. Fail-closed by default:
     * with no IdP, a Kratos session credential resolves to null (401), never open.
     */
    constructor(tokens: TokenRegistry) : this(
        tokens,
        idp = FakeIdentityProvider(emptyMap()),
        roles = InMemoryRoleStore(),
        nowMs = { System.currentTimeMillis() },
    )
}

private fun AuthRole.satisfies(required: AuthRole): Boolean =
    required == AuthRole.MEMBER || this == AuthRole.OPERATOR

/**
 * The Kratos session credential: the native `X-Session-Token` header OR the browser session cookie. Public so
 * the settings shim (CYP-181 / P2.4) can forward the caller's OWN session to the Kratos settings flow — the
 * thin-shim delegates auth to Kratos, and the credential is the one the guard already validated.
 */
fun ApplicationCall.sessionCredential(): SessionCredential? {
    request.header("X-Session-Token")?.ifBlank { null }?.let { return SessionCredential(it, SessionCredential.Source.HEADER) }
    return request.cookies[KRATOS_SESSION_COOKIE]?.ifBlank { null }?.let { SessionCredential(it, SessionCredential.Source.COOKIE) }
}

/**
 * CYP-178 / P1 — the ONE principal resolution (fail-closed). Two mutually-exclusive paths, exactly one
 * auth per caller: (1) the static **operator token** → [AuthPrincipal.MachineOperator]; (2) a **verified**
 * Kratos session → [AuthPrincipal.Human] with its platform role. RC1: `verified==true` is required (not
 * merely session-valid); any absent/invalid/error/timeout from the IdP → null (unauthenticated).
 */
suspend fun ApplicationCall.resolvePrincipal(deps: AuthDeps): AuthPrincipal? {
    // Machine axis first (a bearer token). A KNOWN bearer is an authenticated machine: operator → OPERATOR,
    // a registered agent token → a MEMBER MachineAgent (403, not 401, on operator routes — the pre-CYP-178
    // `requireOperator` semantics; CYP-186 BE2: known agents read the MEMBER-tier event-log). Only the ABSENCE
    // of any credential is 401.
    val bearer = bearerToken()
    if (bearer != null) {
        // CYP-186 C.2: the operator token is INERT when the deploy kill-switch is set AND a role-OPERATOR
        // already exists (never-lock-out: it carries until the first human bootstraps OPERATOR). Inert →
        // falls through to a MEMBER MachineAgent → 403 on operator routes (C collapses to A by config).
        if (deps.tokens.isOperator(bearer) && !(deps.operatorTokenDisabled && deps.roles.hasOperator())) {
            return AuthPrincipal.MachineOperator
        }
        val agentId = deps.tokens.agentFor(bearer)
        if (agentId != null) return AuthPrincipal.MachineAgent(agentId)
        // CYP-186 C.2: the DISABLED operator token (isOperator, but inert by the kill-switch above) is a KNOWN
        // credential deliberately DOWNGRADED to MEMBER ("collapses to A", never-lock-out) — NOT "unknown". Keep it.
        if (deps.tokens.isOperator(bearer)) return AuthPrincipal.MachineAgent(null)
        // CYP-234b-2 ①-fix: an UNKNOWN bearer (garbage, or a CYP-234b participant token) is NOT a role-bearing
        // principal — it must NOT resolve to MEMBER (that let ANY bearer read the MEMBER-tier `/api/events`
        // cross-agent metadata with no grant). Fall through: with no verified session it becomes 401; a
        // participant token is instead accepted ONLY by the canRead-scoped resolvers (requireCommReader etc.).
    }
    // Human axis (a Kratos session). RC1: session-valid is NOT enough — the identity must be verified.
    val resolved = deps.idp.resolve(sessionCredential()) ?: return null
    if (!resolved.verified) return null
    return AuthPrincipal.Human(resolved.identityId, deps.roles.ensureAssigned(resolved.identityId, deps.nowMs()))
}

/**
 * CYP-182 — the `/api/auth/me` projection of the caller's auth STATE in a **single** `idp.resolve`. Unlike
 * [resolvePrincipal] (which the guard uses and which collapses an unverified session to null), this reports
 * the authenticated-but-unverified state too — so `/me` resolves once instead of twice (a present garbage/
 * unverified token no longer double-calls Kratos on the PUBLIC endpoint). Same axis order as [resolvePrincipal]:
 * a present bearer is the machine axis (operator → OPERATOR, any other → MEMBER, no idp call), else the
 * session axis (one whoami). Content-free — no id/email leaves here.
 */
suspend fun ApplicationCall.resolveAuthState(deps: AuthDeps): com.tneff.cyppieagents.model.AuthMe {
    val bearer = bearerToken()
    if (bearer != null) {
        // CYP-234b-2 ①-fix (2nd path): validate the bearer — only a KNOWN credential is authenticated here.
        // An unknown bearer (garbage / a participant token) is NOT MEMBER (it used to report authenticated MEMBER
        // for ANY bearer); it falls through to the session path → `authenticated:false` with no session.
        if (deps.tokens.isOperator(bearer)) return com.tneff.cyppieagents.model.AuthMe(true, AuthRole.OPERATOR.name, true)
        if (deps.tokens.agentFor(bearer) != null) return com.tneff.cyppieagents.model.AuthMe(true, AuthRole.MEMBER.name, true)
        // unknown bearer → fall through (a participant token is not the human whoami subject)
    }
    // No session credential at all → not authenticated, WITHOUT an idp call (no-cred = zero whoami).
    val cred = sessionCredential() ?: return com.tneff.cyppieagents.model.AuthMe(authenticated = false)
    val resolved = deps.idp.resolve(cred) ?: return com.tneff.cyppieagents.model.AuthMe(authenticated = false)
    return if (resolved.verified) {
        com.tneff.cyppieagents.model.AuthMe(true, deps.roles.ensureAssigned(resolved.identityId, deps.nowMs()).name, true)
    } else {
        com.tneff.cyppieagents.model.AuthMe(authenticated = true, role = null, verified = false)
    }
}

/** Per-handler guard (fail-closed): 401 unauth, 403 role-insufficient. Returns the principal on success. */
suspend fun ApplicationCall.requirePrincipal(deps: AuthDeps, required: AuthRole = AuthRole.OPERATOR): AuthPrincipal {
    val p = resolvePrincipal(deps) ?: throw UnauthorizedException()
    if (!p.role.satisfies(required)) throw ForbiddenException("operator required", code = "operator_required")
    return p
}

/**
 * RC1 — the **structural** group guard: every child route is protected **by mounting**, not by a per-handler
 * call a new endpoint could forget. The interceptor resolves + authorizes the principal before any child
 * handler runs and stashes it under [PrincipalKey]. A route placed OUTSIDE this group is unguarded — which
 * the route-enumeration meta-test is designed to catch.
 */
fun Route.authenticatedApi(deps: AuthDeps, required: AuthRole = AuthRole.OPERATOR, build: Route.() -> Unit): Route {
    // A FRESH selector instance per call: the selector is identity-equal only to itself, so Ktor does NOT
    // merge sibling guarded sub-trees under a shared parent into one node (which would try to install
    // AuthGuard twice → DuplicatePluginException). Each authenticatedApi block is its own guarded child.
    val guarded = createChild(AuthenticatedRouteSelector(required))
    guarded.install(AuthGuard) { this.deps = deps; this.required = required }
    guarded.build()
    return guarded
}

private val UNSAFE_METHODS = setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete, HttpMethod.Patch)

/** CYP-186 C.1 — attribute the authorized principal, NEVER a generic "operator". */
private fun actorOf(p: AuthPrincipal): String = when (p) {
    is AuthPrincipal.Human -> "human:${p.identityId}"
    AuthPrincipal.MachineOperator -> "operator-token" // the machine / bootstrap / break-glass path
    is AuthPrincipal.MachineAgent -> "agent:${p.agentId ?: "operator-disabled"}" // a known agent, or the CYP-186 C.2 downgraded operator; never reaches an OPERATOR gate (403 first)
}

class AuthGuardConfig {
    lateinit var deps: AuthDeps
    var required: AuthRole = AuthRole.OPERATOR
}

/** The route-scoped guard: resolves + authorizes the principal before any child handler (fail-closed). */
val AuthGuard = createRouteScopedPlugin("AuthGuard", ::AuthGuardConfig) {
    val deps = pluginConfig.deps
    val required = pluginConfig.required
    onCall { call ->
        val p = call.resolvePrincipal(deps) ?: throw UnauthorizedException()
        if (!p.role.satisfies(required)) throw ForbiddenException("operator required", code = "operator_required")
        call.enforceCsrf() // RC5: a cookie-authed state-changing request must carry the double-submit token
        call.attributes.put(PrincipalKey, p)
        // CYP-186 C.1: attribute every OPERATOR MUTATION (unsafe method) to the principal — single-source, no
        // per-handler drift. Body/payload is NEVER captured (the apikey PUT body IS a secret): method + path only.
        if (required == AuthRole.OPERATOR && call.request.httpMethod in UNSAFE_METHODS) {
            deps.audit.record(OperatorAudit(actorOf(p), call.request.httpMethod.value, call.request.path(), deps.nowMs()))
        }
    }
}

/**
 * A transparent (no-path-segment) selector: matches without consuming a path segment, so a guarded child
 * preserves its parent's path. A CLASS (not an object) so each [authenticatedApi] call gets a distinct,
 * identity-unique instance and Ktor never merges two guarded sub-trees (see [authenticatedApi]).
 *
 * CYP-272: it CARRIES its [required] role (no equals/hashCode override → still reference-identity-equal, so
 * the merge-avoidance holds) so a routing-tree walk can read the EXACT enforced tier per guarded route and
 * bind it to the documented `RestContract.tier` (the tier-tooth), catching a stale-doc desync structurally.
 */
internal class AuthenticatedRouteSelector(val required: AuthRole) : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
        RouteSelectorEvaluation.Transparent
}
