package com.tneff.cyppieagents.auth

import com.tneff.cyppieagents.routing.ForbiddenException
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.UnauthorizedException
import com.tneff.cyppieagents.routing.bearerToken
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
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
    val roles: SqliteRoleStore,
    val nowMs: () -> Long,
)

private fun AuthRole.satisfies(required: AuthRole): Boolean =
    required == AuthRole.MEMBER || this == AuthRole.OPERATOR

/** The Kratos session credential: the native `X-Session-Token` header OR the browser session cookie. */
private fun ApplicationCall.sessionCredential(): String? =
    request.header("X-Session-Token")?.ifBlank { null } ?: request.cookies[KRATOS_SESSION_COOKIE]

/**
 * CYP-178 / P1 — the ONE principal resolution (fail-closed). Two mutually-exclusive paths, exactly one
 * auth per caller: (1) the static **operator token** → [AuthPrincipal.MachineOperator]; (2) a **verified**
 * Kratos session → [AuthPrincipal.Human] with its platform role. RC1: `verified==true` is required (not
 * merely session-valid); any absent/invalid/error/timeout from the IdP → null (unauthenticated).
 */
suspend fun ApplicationCall.resolvePrincipal(deps: AuthDeps): AuthPrincipal? {
    if (deps.tokens.isOperator(bearerToken())) return AuthPrincipal.MachineOperator
    val resolved = deps.idp.resolve(sessionCredential()) ?: return null
    if (!resolved.verified) return null // RC1: session-valid is NOT enough — the identity must be verified
    return AuthPrincipal.Human(resolved.identityId, deps.roles.ensureAssigned(resolved.identityId, deps.nowMs()))
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
    val guarded = createChild(AuthenticatedRouteSelector)
    guarded.install(AuthGuard) { this.deps = deps; this.required = required }
    guarded.build()
    return guarded
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
        call.attributes.put(PrincipalKey, p)
    }
}

private object AuthenticatedRouteSelector : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
        RouteSelectorEvaluation.Transparent
}
