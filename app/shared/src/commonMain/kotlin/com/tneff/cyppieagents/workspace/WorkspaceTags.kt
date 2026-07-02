package com.tneff.cyppieagents.workspace

/**
 * `testTag` contract for the Multi-User MEMBER/OPERATOR surfaces (CYP-80 / CYP-186), exactly per
 * `docs/design/member-operator-tags.md`. Test-Contract v0.5 §2: prefixless `<area>[.<scopeId>].<element>`,
 * segment values `[A-Za-z0-9-]+` (camelCase, no dots). **Area `workspace` is new** — 0 collision against the
 * existing `*Tags.kt`. **Shared API with QA (CYP-7) — do not rename silently; coordinate via the PO.**
 *
 * The **User-Tier** (human: operator/member) — NOT the Agent-Role (`agent_role_*`), which is untouched. Most
 * tier-dependent controls need NO new tag (only the gating boolean changes, not the node — they reuse the
 * existing GATED/read-only tags); new is only the role surface below. The roster ([MEMBERS] + [member]) is
 * structurally omitted for a MEMBER — a MEMBER's test tree never contains those nodes (enumeration seam §3.3).
 */
object WorkspaceTags {
    const val AREA = "workspace"

    /** Persistent role indicator in the top bar ("You are the operator / a member"). Visible to all. */
    const val ROLE_INDICATOR = "workspace.roleIndicator"

    /** The operator identity shown to a MEMBER ("Operator: %1$s"). Visible to a MEMBER only. */
    const val OPERATOR_NAME = "workspace.operatorName"

    /** Members roster container — mounted for an OPERATOR only (structurally omitted for a MEMBER). */
    const val MEMBERS = "workspace.members"

    /** Roster row for a user (scope = user id). Operator-only. */
    fun member(id: String) = "workspace.member.$id"

    /** The row's tier label (Operator/Member); `.you` qualifier marks the own entry. Operator-only. */
    fun memberRole(id: String) = "workspace.member.$id.role"
}
