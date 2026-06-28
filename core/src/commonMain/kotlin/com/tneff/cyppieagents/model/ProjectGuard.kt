package com.tneff.cyppieagents.model

/**
 * The multi-project lifecycle invariants (S13 / CYP-91), as a **pure** decision over the current
 * project list + the active pointer — the project analogue of [AgentMgmtGuard]. Compiled in `:core`
 * so the server registry and the client UI use the EXACT same rules (no drift), and so the destructive
 * delete-safety guardrails are testable platform-neutrally.
 *
 * Each function returns the wire error code to reject with, or `null` when the change is allowed —
 * fail-closed at the call site (the server throws the matching 4xx; the UI blocks before calling). A
 * rejected delete cascades NOTHING (the guard runs before any teardown).
 */
object ProjectGuard {

    /** `null` if [spec] may be created, else `invalid_project_id` / `project_exists`. */
    fun validateCreate(existing: List<Project>, spec: CreateProjectRequest): String? {
        if (spec.id.isBlank() || spec.name.isBlank() || !SAFE_ID.matches(spec.id)) return "invalid_project_id"
        if (existing.any { it.id == spec.id }) return "project_exists"
        return null
    }

    /** `null` if [id] may be renamed to [name], else `invalid_project_id` (blank name) / `project_not_found`. */
    fun validateRename(existing: List<Project>, id: String, name: String): String? {
        if (name.isBlank()) return "invalid_project_id"
        if (existing.none { it.id == id }) return "project_not_found"
        return null
    }

    /**
     * `null` if [id] may be deleted, else `project_not_found` / `last_project` / `active_project_protected`.
     *
     * Delete is the most destructive op in the platform (it cascades worktrees + events + config for the
     * project). Two PO-approved guardrails make it fail-closed: the **last** project is never deletable
     * (`last_project`), and the **active** project is never deletable (`active_project_protected` — you
     * must switch away first). `last_project` is checked before `active_project_protected` so the single
     * remaining project — which is necessarily also the active one — reports the clearer reason.
     */
    fun validateDelete(existing: List<Project>, id: String, activeProjectId: String): String? {
        if (existing.none { it.id == id }) return "project_not_found"
        if (existing.size <= 1) return "last_project"
        if (id == activeProjectId) return "active_project_protected"
        return null
    }

    /**
     * Project ids become a worktree root `projects/<id>/`, a hub channel projectId-stamp and an event
     * partition key, so they are constrained to a path/ref-safe charset — a stray `/`, space or `..`
     * would escape into the filesystem / a malformed scope key. Anything else is `invalid_project_id`,
     * fail-closed. Same discipline as [AgentMgmtGuard]'s id check.
     */
    private val SAFE_ID = Regex("^[a-zA-Z0-9_-]+$")
}
