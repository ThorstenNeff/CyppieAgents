package com.tneff.cyppieagents.model

import kotlinx.serialization.Serializable

/**
 * Multi-project lifecycle wire contract (S13 / CYP-91 — "die Eins auf N aufmachen" für Projekte, on
 * the S12 [DEFAULT_PROJECT_ID] / [ProjectScope] foundation). The DTOs live in `:core` so the server
 * registry endpoints and the client Project-Lifecycle UI compile against ONE definition (no drift),
 * and so the touchy guardrails ([ProjectGuard]) are testable platform-neutrally.
 *
 * **The endpoint contract is PROVISIONAL** — it reconciles with the UIUX S13-design once that exists.
 * The active-switch *semantics* (per-project hub/session re-instancing) are the architectural part and
 * are deliberately NOT in this fundament; the registry only owns the persisted active *pointer*.
 */

/**
 * A project (tenant). [id] is the scoping key shared with [ProjectScope.permits] — it becomes a
 * worktree root `projects/<id>/`, a channel projectId-stamp and an event partition key, so it is
 * constrained to the same path/ref-safe charset as an agent id ([ProjectGuard]).
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
)

/** GET /api/projects response: the full registry plus which project is active. */
@Serializable
data class ProjectsView(
    val activeProjectId: String,
    val projects: List<Project>,
)

/** POST /api/projects body. `id` is validated path/ref-safe ([ProjectGuard]); `name` is a display label. */
@Serializable
data class CreateProjectRequest(
    val id: String,
    val name: String,
)

/** PUT /api/projects/{id} body — rename only. id is identity-defining and intentionally immutable. */
@Serializable
data class RenameProjectRequest(
    val name: String,
)

/**
 * PUT /api/projects/active body (PROVISIONAL). Flips the persisted active pointer only; live
 * hub/session re-instancing is the deferred S13-design part, NOT this ticket.
 */
@Serializable
data class SwitchActiveRequest(
    val projectId: String,
)
