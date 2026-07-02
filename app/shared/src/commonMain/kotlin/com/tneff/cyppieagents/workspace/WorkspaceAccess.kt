package com.tneff.cyppieagents.workspace

import com.tneff.cyppieagents.auth.UserTier

/**
 * CYP-186 — the ONE operator-access derivation (hybrid access-model **C**, Auftraggeber 2026-07-02): an
 * operator surface is editable when the user's [tier] is [UserTier.OPERATOR] **OR** a break-glass
 * [operatorToken] is present (bootstrap / override). Pure + centralized so the whole desktop gates on one
 * boolean and it can be teethed directly.
 *
 * **Fail-closed:** MEMBER (the default for an unknown tier) with no token ⇒ `false` (read-only). A session
 * error never grants operator rights. NOT the Agent-Role — this is the human User-Tier.
 */
fun isOperatorAccess(tier: UserTier, operatorToken: String?): Boolean =
    tier == UserTier.OPERATOR || operatorToken != null

/**
 * CYP-186 (roster fold) — should the OPERATOR-only member roster (§3.2) be mounted? Gated on the true [tier]
 * being OPERATOR, **not** on [isOperatorAccess]: the backend `GET /api/workspace/members` is tier-gated (403,
 * content-free, for any non-OPERATOR identity — including a break-glass-token MEMBER), so mounting it for
 * anyone else would be a dead 403 window. A MEMBER's tree therefore never contains the roster nodes
 * (enumeration/defense-in-depth seam §3.3) — the omission is client-side, not merely a disabled control.
 */
fun showRoster(tier: UserTier): Boolean = tier == UserTier.OPERATOR
