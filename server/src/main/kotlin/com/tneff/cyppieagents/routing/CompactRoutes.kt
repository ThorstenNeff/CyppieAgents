package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.authenticatedApi
import com.tneff.cyppieagents.boot.CompactConfigStore
import com.tneff.cyppieagents.model.CompactConfig
import com.tneff.cyppieagents.model.CompactStatus
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * CYP-326 — the compact-orchestration config/status surface.
 * - `GET /api/compact/status` (read-tier): the [CompactStatus] (allowed, threshold, armed, running, last X/N).
 * - `POST /api/compact/config` (OPERATOR): set the "compact allowed" checkbox + threshold, persisted.
 * Operator writes are gated STRUCTURALLY (fail-closed before the body is received), like [configRoutes].
 */
fun Route.compactRoutes(
    configStore: CompactConfigStore,
    status: () -> CompactStatus,
    registry: TokenRegistry,
    activeProjectId: () -> String,
    // CYP-326 kill-switch: invoked after a config write so the orchestrator can ABORT a running run if
    // "compact allowed" was turned off. Null (dev/tests) = no orchestrator to notify.
    onConfigUpdated: () -> Unit = {},
    deps: AuthDeps = AuthDeps(registry),
    apiBase: String = "/api",
) {
    route("$apiBase/compact") {
        get("/status") {
            call.requireCommReader(deps, registry)
            call.respond(status())
        }
        authenticatedApi(deps, AuthRole.OPERATOR) {
            post("/config") {
                val req = call.receive<CompactConfig>()
                configStore.set(activeProjectId(), req)
                onConfigUpdated() // CYP-326: allowed→false aborts a running orchestration
                call.respond(status())
            }
        }
    }
}
