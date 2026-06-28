package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.boot.ProjectDeleter
import com.tneff.cyppieagents.boot.ProjectRegistry
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.RenameProjectRequest
import com.tneff.cyppieagents.model.SwitchActiveRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * Multi-project lifecycle endpoints (S13 / CYP-91). **PROVISIONAL contract**, reconciled with the S13
 * design — the `:core` vocabulary ([com.tneff.cyppieagents.model.Project] et al.) is the reference.
 *
 * **All operations are operator-gated and fail-closed** (`requireOperator` runs BEFORE any body parse
 * or mutation — a non-operator gets a 401/403 and changes nothing; the touchy delete-safety lives in
 * the pure [com.tneff.cyppieagents.model.ProjectGuard], surfaced via the registry's 4xx codes). The
 * active project is the server-side pointer in [ProjectRegistry]; scoped endpoints resolve it
 * server-side (no client header).
 *
 * Routes:
 * - `GET    /api/projects`         → [com.tneff.cyppieagents.model.ProjectsView] (registry + active pointer)
 * - `POST   /api/projects`         → 201 [com.tneff.cyppieagents.model.Project] · 400 invalid_project_id · 409 project_exists
 * - `PUT    /api/projects/active`  → ProjectsView (flip active pointer; live re-instancing is deferred) · 404 project_not_found
 * - `PUT    /api/projects/{id}`    → Project (rename) · 400 invalid_project_id · 404 project_not_found
 * - `DELETE /api/projects/{id}?deleteWorktrees=` → ProjectDeleteReceipt · 404 project_not_found ·
 *   409 last_project / active_project_protected. `deleteWorktrees` defaults FALSE (worktree kept;
 *   config + events always cascade; branches always kept — S13-design §4).
 */
fun Route.projectRoutes(registry: ProjectRegistry, deleter: ProjectDeleter, tokens: TokenRegistry) {
    route("/api/projects") {
        get {
            call.requireOperator(tokens)
            call.respond(registry.view())
        }
        post {
            call.requireOperator(tokens)
            val req = call.receive<CreateProjectRequest>()
            call.respond(HttpStatusCode.Created, registry.create(req))
        }
        // Constant segment beats the `/{id}` parameter route, so `active` is never captured as an id.
        put("/active") {
            call.requireOperator(tokens)
            val req = call.receive<SwitchActiveRequest>()
            call.respond(registry.setActive(req.projectId))
        }
        put("/{id}") {
            call.requireOperator(tokens)
            val id = call.parameters["id"] ?: throw BadRequestException("missing project id")
            val req = call.receive<RenameProjectRequest>()
            call.respond(registry.rename(id, req.name))
        }
        delete("/{id}") {
            call.requireOperator(tokens)
            val id = call.parameters["id"] ?: throw BadRequestException("missing project id")
            val deleteWorktrees = call.request.queryParameters["deleteWorktrees"]?.toBoolean() ?: false
            call.respond(deleter.delete(id, deleteWorktrees))
        }
    }
}
