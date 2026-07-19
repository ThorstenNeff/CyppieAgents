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
    apiBase: String = "/api",
    // CYP-247 S2: null (dev/tests) → the view never carries the pending flag and a PUT marks nothing.
    reprovision: com.tneff.cyppieagents.boot.RepoReprovision? = null,
    // CYP-736: the live clone-status store (resolved from the active project's WorktreeManager). null (dev/tests)
    // → the view never carries a cloneStatus (the client decodes NOT_CONFIGURED/CONFIGURED_NEVER_CLONED, never OK).
    cloneStatus: (() -> com.tneff.cyppieagents.boot.CloneStatusStore)? = null,
) {
    route("$apiBase/config") {
        // GET = any authenticated reader — agent/operator token OR a verified human (incl. a MEMBER session,
        // CYP-186 BE2): the MASKED status line only, never the key. The reveal/raw key is egressed by NO GET.
        get("/repo") {
            call.requireCommReader(deps, registry)
            val pid = activeProjectId()
            // CYP-247 S2: surface the pending-re-provision (EFFECT_DEFERRED) so the UI shows "takes effect on restart".
            val view = store.repoView(pid).copy(reprovisionPending = reprovision?.pending(pid) != null)
            // CYP-736: surface the live clone lifecycle. The ③-invariant is enforced HERE at the wire boundary via
            // cloneStatusForWire (a non-null cloneStatus ONLY when configured) — so `configured=false`+CLONED_OK can
            // never leave the server. A null cloneStatus → the client decodes NOT_CONFIGURED/CONFIGURED_NEVER_CLONED.
            val cs = com.tneff.cyppieagents.boot.cloneStatusForWire(view.configured, cloneStatus?.invoke()?.get(pid))
            call.respond(view.copy(cloneStatus = cs?.status, cloneFailReason = cs?.reason))
        }
        get("/apikey") {
            call.requireCommReader(deps, registry)
            call.respond(store.apiKeyView(activeProjectId())) // only { set, masked } — MEMBER never sees the raw key
        }
        // CYP-178: operator WRITES gated STRUCTURALLY under the group — fail-closed BEFORE the body is
        // received (a non-operator is 401/403, unparsed). The RC1 route-enumeration meta-test is the net.
        authenticatedApi(deps, AuthRole.OPERATOR) {
            put("/repo") {
                val req = call.receive<RepoConfigRequest>()
                val pid = activeProjectId()
                val view = store.setRepo(pid, req.url, req.branch) // 400 invalid_repo_url
                // CYP-247 S2 (D4/D5): a repo change marks the clone STALE → re-provision on the next agent
                // (re)start (guarded by §2d). The response signals EFFECT_DEFERRED so the UI can hint a restart.
                reprovision?.markStale(pid, req.discardUnpushed)
                call.respond(view.copy(reprovisionPending = reprovision != null))
            }
            put("/apikey") {
                val req = call.receive<ApiKeyRequest>()
                call.respond(store.setApiKey(activeProjectId(), req.apiKey)) // 400 invalid_api_key; returns { set, masked }
            }
        }
    }
}
