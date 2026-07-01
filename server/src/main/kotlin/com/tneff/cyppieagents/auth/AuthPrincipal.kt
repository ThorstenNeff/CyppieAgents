package com.tneff.cyppieagents.auth

/**
 * CYP-178 / P1 — the resolved caller identity behind the ONE `requirePrincipal` chokepoint. Three kinds,
 * matching the Middleway + the pre-existing machine model: the static **operator token** (always
 * OPERATOR); an authenticated **agent bearer** (a MEMBER machine — present-but-insufficient, so an
 * operator route is a 403, exactly as the pre-CYP-178 `requireOperator` returned); or a **human**
 * (a verified Kratos session mapped to a platform [AuthRole]). Every protected route authorizes off
 * `.role`; a route is never gated by more than one auth (RC1).
 */
sealed interface AuthPrincipal {
    val role: AuthRole

    /** The static operator token (machines/scripts). Unchanged from the pre-CYP-178 model. */
    data object MachineOperator : AuthPrincipal {
        override val role: AuthRole get() = AuthRole.OPERATOR
    }

    /**
     * A present bearer token that is **not** the operator token — a valid agent ([agentId] set) or an
     * otherwise-unknown bearer ([agentId] null). Always [AuthRole.MEMBER]: authenticated-but-insufficient,
     * so an OPERATOR route is a **403** (preserving the pre-CYP-178 `requireOperator` semantics for a
     * non-operator bearer). Distinguishing this from "no credential" (401) keeps the fail-closed status codes
     * exactly as before the migration.
     */
    data class MachineAgent(val agentId: String?) : AuthPrincipal {
        override val role: AuthRole get() = AuthRole.MEMBER
    }

    /** A human authenticated via a **verified** Kratos session, mapped to a platform role. */
    data class Human(val identityId: String, override val role: AuthRole) : AuthPrincipal
}
