package com.tneff.cyppieagents.model

import kotlinx.serialization.Serializable

/**
 * CYP-186 BE3a — one row of the **OPERATOR-only** workspace roster (`GET /api/workspace/members`). Carries
 * only [identityId] + [tier] + a friendly [displayName]; **never an email or any secret**. This surface is
 * OPERATOR-only (a MEMBER gets 403, content-free) — the identityId is not egressed to non-operators.
 *
 * [displayName] is **nullable and currently null**: it comes from the Kratos identity traits via the admin
 * API, which is not yet wired server-side, and the identity schema has no non-email name trait yet. The shape
 * is stable so the client can fold it now; names populate once the admin-API seam + a name trait land.
 */
@Serializable
data class WorkspaceMember(
    val identityId: String,
    val tier: String,
    val displayName: String? = null,
)
