package com.tneff.cyppieagents.routing

/** The `all`-projects sentinel for the CYP-94 event read override (PO-§6.3, CROSS-PROJECT.md §3.2). */
const val EVENT_SCOPE_ALL: String = "all"

/**
 * The SINGLE resolver for the operator-only cross-project Event-Log read override (S17 / CYP-94),
 * shared by `/api/events` (REST `?projectId=` query param) and `/ws/events`
 * ([com.tneff.cyppieagents.model.SubscribeEvents.projectId]) — ONE policy, no cross-surface drift.
 * Returns the `EventFilter.projectId` to scope by (`null` = unscoped).
 *
 * Policy (CROSS-PROJECT.md §3.4 / §5.3, PO-§6.3 — must NOT weaken CYP-102):
 *  - **No override** ([requested] null/blank) → the **active project** (forced-active default, CYP-102 unchanged).
 *  - **[EVENT_SCOPE_ALL]** → unscoped (`null`) — the operator's own projects. MVP single-tenant: `all == unscoped`.
 *    **S18 multi-tenant MUST bind `all` to [authorizedProjects] (an IN-set), NOT null**, or it leaks across tenants.
 *  - **An authorized other project** ([requested] ∈ [authorizedProjects]) → that projectId.
 *  - **Anything else** (an unauthorized id, garbage) → **fail-closed to the active project** — a client can never
 *    widen past the operator's authorization, and the `projectId==null` match-all path is unreachable except via the
 *    deliberate, authorized `all` (closing the fail-OPEN LOW finding on `EventFilter.matches(projectId==null)`).
 *
 * [active] is nullable only because the standalone test helpers default to unscoped; production always passes a
 * concrete active project.
 */
fun resolveEventScope(requested: String?, active: String?, authorizedProjects: Set<String>): String? = when {
    requested.isNullOrBlank() -> active
    requested == EVENT_SCOPE_ALL -> if (authorizedProjects.isNotEmpty()) null else active
    requested in authorizedProjects -> requested
    else -> active
}
