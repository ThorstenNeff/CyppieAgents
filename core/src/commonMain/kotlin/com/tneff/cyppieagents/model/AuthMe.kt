package com.tneff.cyppieagents.model

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

/**
 * CYP-182 / P3 — the client whoami read (`GET /api/auth/me`). A **content-free** snapshot of the caller's
 * auth state, derived server-side from the P1 principal — **no identity id, no email, no secrets**, just what
 * the Compose client needs to drive `AuthState` (replacing the CYP-177 status-inference stub):
 *
 *  - [authenticated] — a valid credential is present (operator token, agent token, or a valid Kratos session).
 *  - [role] — the platform authZ role (`"OPERATOR"` | `"MEMBER"`) once established, else null (e.g. a
 *    valid-but-unverified session has no role yet). A String, not the server-only `AuthRole`, to keep this a
 *    thin cross-module contract.
 *  - [verified] — whether the identity is verified (RC1). A logged-in-but-unverified caller is
 *    `authenticated=true, verified=false` → the client shows "verify your email" (guarded routes still 401).
 */
@Serializable
data class AuthMe(
    val authenticated: Boolean,
    val role: String? = null,
    // CYP-498 — @Required makes `verified` ALWAYS present on the wire (even when false, independent of any
    // serializer's encodeDefaults) AND flips it non-optional in the descriptor, which is what the OpenAPI
    // generator (SchemaWalker) reads to mark it contract-`required`. Single-sourced: this one annotation drives
    // both the wire invariant and the schema `required` flag, so they can't drift. Closes the liveness edge where
    // an omitted `verified` would fail-closed a legitimate operator at the verify gate. Default stays → no call
    // site changes. Locked by AuthMeVerifiedRequiredTest.
    @Required val verified: Boolean = false,
)
