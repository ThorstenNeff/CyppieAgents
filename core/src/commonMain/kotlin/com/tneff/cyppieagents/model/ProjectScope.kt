package com.tneff.cyppieagents.model

/**
 * The single canonical project id for the MVP (S12 / Epic CYP-74). MVP runs exactly one project, so
 * payloads and config written without a `projectId` decode onto THIS concrete project. It is a real
 * project id, **not** an "all/global" wildcard: [ProjectScope.permits] matches it exactly like any
 * other id, so the default can never become a back-door to cross-project visibility.
 */
const val DEFAULT_PROJECT_ID: String = "default"

/**
 * The ONE project-scoping decision (S12 / Epic CYP-74), as a **pure** function — the tenant analogue
 * of [AclMatrix]. It is folded into [AclMatrix], so every existing enforcement site (REST, WS,
 * mediation) inherits project isolation through the same chokepoint, composed with the ACL and
 * deny-wins.
 *
 * Structurally fail-closed: an entity is in scope ONLY when its `projectId` is non-blank AND exactly
 * equals the active project. A missing/blank/mismatched `projectId` → DENY. There is no wildcard or
 * "global" sentinel anywhere, so scoping cannot fail *open* at "no project" — the dangerous adjacent
 * vector. Note a blank active project denies everything (a non-blank entity can never equal it, and a
 * blank entity is rejected up front), so a misconfiguration also fails closed.
 */
object ProjectScope {
    fun permits(entityProjectId: String, activeProjectId: String): Boolean =
        entityProjectId.isNotBlank() && entityProjectId == activeProjectId
}
