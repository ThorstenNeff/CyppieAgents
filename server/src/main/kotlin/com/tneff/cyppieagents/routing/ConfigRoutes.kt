package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.authenticatedApi
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
 *  - **PUT = operator, fail-closed:** gated STRUCTURALLY under the [authenticatedApi] group (CYP-178), so
 *    the check runs **before** the body is received and a non-operator is rejected (401/403) unparsed.
 *
 * **S13 / CYP-102:** [activeProjectId] is a RESOLVER (not a by-value string) bound to the registry
 * active pointer, so config follows a project switch (`POST /api/projects/switch`) WITHOUT a restart —
 * the same live-resolution as `/api/events` (CYP-102). A by-value bind would keep resolving the boot
 * project after a switch → the operator would read/write the WRONG project's repo + API key at rest
 * (credential mis-scoping, Doc 05 D3). Resolved per request, so each call reads the current active project.
 */
fun Route.configRoutes(
    store: ProjectConfigStore,
    registry: TokenRegistry,
    activeProjectId: () -> String,
    deps: AuthDeps = AuthDeps(registry),
) {
    route("/api/config") {
        // GET = participant (any authenticated agent/operator): the masked status line, never the key.
        get("/repo") {
            call.requireParticipant(registry)
            call.respond(store.repoView(activeProjectId()))
        }
        get("/apikey") {
            call.requireParticipant(registry)
            call.respond(store.apiKeyView(activeProjectId())) // only { set, masked }
        }
        // CYP-178: operator WRITES gated STRUCTURALLY under the group — fail-closed BEFORE the body is
        // received (a non-operator is 401/403, unparsed). The RC1 route-enumeration meta-test is the net.
        authenticatedApi(deps, AuthRole.OPERATOR) {
            put("/repo") {
                val req = call.receive<RepoConfigRequest>()
                call.respond(store.setRepo(activeProjectId(), req.url, req.branch)) // 400 invalid_repo_url
            }
            put("/apikey") {
                val req = call.receive<ApiKeyRequest>()
                call.respond(store.setApiKey(activeProjectId(), req.apiKey)) // 400 invalid_api_key; returns { set, masked }
            }
        }
    }
}
