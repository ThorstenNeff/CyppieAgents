package com.tneff.cyppieagents.project

import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.Project
import com.tneff.cyppieagents.model.ProjectsView
import com.tneff.cyppieagents.model.RenameProjectRequest

/**
 * Project-lifecycle data port (S13 — CYP-91 management + CYP-92 switcher). Mirrors the PO-decided
 * `/api/projects` seam (all mutations operator-gated, active = server-side pointer, no `X-Project-Id`
 * header). Built against [StubProjectRepository] for hermetic tests; the live `HttpProjectRepository`
 * is a later stub→real swap with no UI/VM change (pattern: CYP-85/90).
 *
 * **Active is server state** (`ProjectsView.activeProjectId`): both [list] and [switchActive] return the
 * full view, so the UI never invents the active pointer. Delete returns nothing the S13 UI consumes —
 * the receipt's counts are a Fast-Follow (PROJECT-MANAGEMENT §6.5); the UI re-fetches after.
 */
interface ProjectRepository {
    /** `GET /api/projects` → registry + active pointer. */
    suspend fun list(): ProjectsView

    /** `POST /api/projects` (operator). Throws [ProjectException] (`invalid_project_id`/`project_exists`). */
    suspend fun create(request: CreateProjectRequest): Project

    /** `POST /api/projects/switch` (operator). Flips the active pointer; returns the updated view.
     *  (A dedicated path, not `/active`, so a project with id `active` can't shadow the route.) */
    suspend fun switchActive(projectId: String): ProjectsView

    /** `PUT /api/projects/{id}` (operator). Rename only — id is immutable. Throws `project_not_found`. */
    suspend fun rename(id: String, request: RenameProjectRequest): Project

    /**
     * `DELETE /api/projects/{id}?deleteWorktrees=<bool>` (operator). Cascades; the active and last
     * projects are server-protected (`active_project_protected`/`last_project`). Returns Unit — the
     * S13 UI re-fetches; the delete receipt's counts are a Fast-Follow (not consumed here).
     */
    suspend fun delete(id: String, deleteWorktrees: Boolean)
}

/**
 * A project call was rejected. [code] is the server reason — the `:core` wire codes (PROJECT-MANAGEMENT
 * §6.4): `invalid_project_id`, `project_exists`, `project_not_found`, `last_project`,
 * `active_project_protected`, `operator_required` (403), `unauthorized` (401).
 */
class ProjectException(val code: String) : Exception(code)
