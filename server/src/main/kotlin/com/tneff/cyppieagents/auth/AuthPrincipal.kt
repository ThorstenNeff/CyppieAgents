package com.tneff.cyppieagents.auth

/**
 * CYP-178 / P1 — the resolved caller identity behind the ONE `requirePrincipal` chokepoint. Exactly two
 * kinds, matching the Middleway: a **machine** (the static operator token — the existing model, always
 * OPERATOR) or a **human** (a verified Kratos session mapped to a platform [AuthRole]). Every protected
 * route authorizes off `.role`; a route is never gated by more than one auth (RC1).
 */
sealed interface AuthPrincipal {
    val role: AuthRole

    /** The static operator token (machines/scripts). Unchanged from the pre-CYP-178 model. */
    data object MachineOperator : AuthPrincipal {
        override val role: AuthRole get() = AuthRole.OPERATOR
    }

    /** A human authenticated via a **verified** Kratos session, mapped to a platform role. */
    data class Human(val identityId: String, override val role: AuthRole) : AuthPrincipal
}
