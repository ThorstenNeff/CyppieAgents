package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthPrincipal
import com.tneff.cyppieagents.auth.Credential

/**
 * CYP-410 (S-A) — the **transport-agnostic Session Manager** seam (design 15 §6 S-A). The hub's session-level
 * business logic — which project is active, resolving a caller's credential to a principal — sits behind ONE
 * facade that holds no Ktor type. Today the Local API (the `/api` and `/ws` Ktor routes) is the only transport
 * that drives it; the Phase-2 Control-Plane transport ([ControlPlaneConnector]) mounts behind the SAME facade,
 * so both transports funnel into identical logic after authentication.
 *
 * This is a pure extraction: [switchProject] wraps the already-single-sourced [ProjectSwitcher.switch] (the
 * `onActiveSwitch` route body moved here verbatim), [activeRuntime] wraps `runtimeRegistry.active()`, and
 * [resolvePrincipal] wraps the transport-neutral `auth.resolvePrincipal(Credential, AuthDeps)` — the SAME
 * resolution the `/api` guard uses via its `ApplicationCall` overload.
 */
class SessionManager(
    private val booted: BootedPlatform,
    private val authDeps: AuthDeps,
) {
    /** Switch the active project (the runtime switch: drain outgoing → rescope → rehydrate → mark HOT). */
    suspend fun switchProject(pid: String) = booted.projectSwitcher.switch(pid)

    /** The live [ProjectRuntime] of the active project (fail-closed throw → 409 at the route boundary). */
    fun activeRuntime(): ProjectRuntime = booted.runtimeRegistry.active()

    /** Resolve a transport-neutral [Credential] to a [AuthPrincipal] (fail-closed) — the ONE resolution, so a
     *  Phase-2 transport authenticates exactly as the Local API does. */
    suspend fun resolvePrincipal(cred: Credential): AuthPrincipal? =
        com.tneff.cyppieagents.auth.resolvePrincipal(cred, authDeps)
}
