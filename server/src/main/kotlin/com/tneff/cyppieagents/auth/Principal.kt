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
    // Machine axis first (a bearer token). A present bearer is an authenticated machine: operator → OPERATOR,
    // anything else → a MEMBER MachineAgent (so an operator route is a 403, not a 401 — the pre-CYP-178
    // `requireOperator` semantics). Only the ABSENCE of any credential is 401.
    val bearer = bearerToken()
    if (bearer != null) {
        // CYP-186 C.2: the operator token is INERT when the deploy kill-switch is set AND a role-OPERATOR
        // already exists (never-lock-out: it carries until the first human bootstraps OPERATOR). Inert →
        // falls through to a MEMBER MachineAgent → 403 on operator routes (C collapses to A by config).
        if (deps.tokens.isOperator(bearer) && !(deps.operatorTokenDisabled && deps.roles.hasOperator())) {
            return AuthPrincipal.MachineOperator
        }
        return AuthPrincipal.MachineAgent(deps.tokens.agentFor(bearer))
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
        val role = if (deps.tokens.isOperator(bearer)) AuthRole.OPERATOR else AuthRole.MEMBER
        return com.tneff.cyppieagents.model.AuthMe(authenticated = true, role = role.name, verified = true)
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
    val guarded = createChild(AuthenticatedRouteSelector())
    guarded.install(AuthGuard) { this.deps = deps; this.required = required }
    guarded.build()
    return guarded
}

private val UNSAFE_METHODS = setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete, HttpMethod.Patch)

/** CYP-186 C.1 — attribute the authorized principal, NEVER a generic "operator". */
private fun actorOf(p: AuthPrincipal): String = when (p) {
    is AuthPrincipal.Human -> "human:${p.identityId}"
    AuthPrincipal.MachineOperator -> "operator-token" // the machine / bootstrap / break-glass path
    is AuthPrincipal.MachineAgent -> "agent:${p.agentId ?: "unknown"}" // never reaches an OPERATOR gate (403 first)
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
 */
private class AuthenticatedRouteSelector : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
        RouteSelectorEvaluation.Transparent
}
