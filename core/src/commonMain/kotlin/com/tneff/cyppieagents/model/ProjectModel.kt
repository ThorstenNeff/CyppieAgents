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
 * The runtime state of a project's agent-lifecycle (CYP-255 .4b / CYP-247.4 — the ratified teardown). It is
 * about the LIVE agent processes, NOT the project's data (channels/events/config persist in every state):
 *  - [HOT] — the ACTIVE project; its agents' sessions are live.
 *  - [BACKGROUND] — live but not active, within the LRU cap K; its agents keep running in the background.
 *  - [SUSPENDED] — beyond the LRU cap K (or never activated): its agent processes are killed (session ids
 *    persisted), resumed via `--resume` on re-entry. The cheap runtime object may stay in memory (MVP: full
 *    runtime reclaim is deferred to per-project config persistence, CYP-247.5 / CYP-220).
 *
 * Surfaced additively on [Project.runtimeState] so the client (CYP-249 / CYP-262-T2) can show a background /
 * suspended indicator — the ratified "resource honesty" of the L client UX. The client treats [HOT] (and an
 * absent field, pre-.4b) as **no indicator**, so [HOT] is the fail-safe default for a never-activated project
 * (which has no live processes and must not show a "was-live" background/suspended badge).
 */
@Serializable
enum class RuntimeState { HOT, BACKGROUND, SUSPENDED }

/**
 * A project (tenant). [id] is the scoping key shared with [ProjectScope.permits] — it becomes a
 * worktree root `projects/<id>/`, a channel projectId-stamp and an event partition key, so it is
 * constrained to the same path/ref-safe charset as an agent id ([ProjectGuard]).
 *
 * [runtimeState] (CYP-255 .4b) is additive + defaulted [RuntimeState.HOT] (= no indicator): the persisted
 * registry doesn't store it (it is a live property of the runtime, not of the project record), so the
 * registry leaves the default and the `GET /api/projects` route fills the real per-project state from the
 * live suspension policy. A pre-.4b payload (no field) decodes to HOT → the client shows no indicator.
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val runtimeState: RuntimeState = RuntimeState.HOT,
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
