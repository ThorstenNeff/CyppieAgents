package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.authenticatedApi
import com.tneff.cyppieagents.boot.RepoReprovision
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.model.ReprovisionPreview
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * CYP-466 — `GET /api/config/repo/reprovision-preview`: the HONEST discard-confirm feed. It answers, for the
 * ACTIVE project, "if you discard + restart to re-provision the repo, which agents lose what?" — the LIVE
 * per-agent losable work ([WorktreeManager.unpushedWork]) plus whether a re-provision is even pending.
 *
 * **Live / on-demand — never stashed.** The at-risk set is DYNAMIC (an agent can commit/push AFTER the repo PUT
 * and clear its own risk), so it is computed at THIS read, not captured at PUT time (a stashed list goes stale →
 * the confirm would show phantom or missing work). "Confirm the loss you SEE" = the loss computed now.
 *
 * **Single-sourced with the block decision.** [WorktreeManager.unpushedWork] is the SAME function
 * `BootOrchestrator.ensureActiveWorktree` consults to BLOCK the teardown, so what the operator confirms here is
 * EXACTLY the set that will (or won't) block — no drift between "what we warn" and "what we block".
 *
 * **Operator-tier** (project-config surface, like `PUT /api/config/repo`): gated STRUCTURALLY under
 * [authenticatedApi] so a non-operator is rejected fail-closed (401/403) before the handler runs. The at-risk work
 * (agent names + dirty/unpushed flags) is operator-only settings context, not a member-reachable read.
 */
fun Route.reprovisionPreviewRoutes(
    worktrees: () -> WorktreeManager,
    activeProjectId: () -> String,
    registry: TokenRegistry,
    deps: AuthDeps = AuthDeps(registry),
    apiBase: String = "/api",
    // null (dev/tests) → nothing is ever pending; the preview reports pending=false and the live at-risk work.
    reprovision: RepoReprovision? = null,
) {
    authenticatedApi(deps, AuthRole.OPERATOR) {
        get("$apiBase/config/repo/reprovision-preview") {
            val pid = activeProjectId()
            call.respond(
                ReprovisionPreview(
                    reprovisionPending = reprovision?.pending(pid) != null,
                    atRisk = worktrees().unpushedWork(), // LIVE, single-sourced with the block guard
                ),
            )
        }
    }
}
