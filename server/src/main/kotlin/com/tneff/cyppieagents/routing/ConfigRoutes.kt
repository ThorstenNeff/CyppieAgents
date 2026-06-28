package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.boot.ProjectConfigStore
import com.tneff.cyppieagents.model.ApiKeyRequest
import com.tneff.cyppieagents.model.RepoConfigRequest
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * Project-settings config endpoints (S15 / CYP-96 — PROJECT-SETTINGS §2). Per-`projectId` (MVP = 1,
 * the active project); the API key is **write-only** on the wire.
 *
 * Gates (Reviewer merge-gate):
 *  - **GET = participant** (any authenticated agent/operator) so the masked status line is visible
 *    without an operator token (design §1.3/§4.2). GET never returns the plaintext key.
 *  - **PUT = operator, fail-closed:** `requireOperator` runs **before** the body is received, so a
 *    non-operator is rejected (403 `operator_required`) without the request ever being parsed.
 */
fun Route.configRoutes(store: ProjectConfigStore, registry: TokenRegistry, activeProjectId: String) {
    route("/api/config") {
        get("/repo") {
            call.requireParticipant(registry)
            call.respond(store.repoView(activeProjectId))
        }
        put("/repo") {
            call.requireOperator(registry)
            val req = call.receive<RepoConfigRequest>()
            call.respond(store.setRepo(activeProjectId, req.url, req.branch)) // 400 invalid_repo_url
        }
        get("/apikey") {
            call.requireParticipant(registry)
            call.respond(store.apiKeyView(activeProjectId)) // only { set, masked }
        }
        put("/apikey") {
            call.requireOperator(registry)
            val req = call.receive<ApiKeyRequest>()
            call.respond(store.setApiKey(activeProjectId, req.apiKey)) // 400 invalid_api_key; returns { set, masked }
        }
    }
}
