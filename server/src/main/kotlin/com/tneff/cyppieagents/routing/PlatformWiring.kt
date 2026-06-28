package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.boot.BootedPlatform
import com.tneff.cyppieagents.model.ApiError
import com.tneff.cyppieagents.model.ApiErrorBody
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.plugins.BadRequestException as KtorBadRequestException

/**
 * Wires a [BootedPlatform] into the HTTP/WS surface: the comm REST (operator/agent bearer) and the
 * per-agent event-stream socket. The socket is authorized with the PRODUCTION [tokenAuthorize] over
 * the real [BootedPlatform.tokenRegistry] (Reviewer #1) — fail-closed, no allow-all default.
 *
 * Reviewer #4: the boot entrypoint must bind the server to localhost (127.0.0.1) as long as the
 * `?token=` query fallback exists, so the socket is not reachable off-box. See [bootHost].
 */
fun Application.installPlatform(booted: BootedPlatform) {
    install(ContentNegotiation) { json(CommJson) }
    install(WebSockets)
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ApiErrorBody(ApiError(cause.code, cause.message)))
        }
        exception<KtorBadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiErrorBody(ApiError("bad_request", cause.message ?: "bad request")))
        }
        exception<Throwable> { call, _ ->
            call.respond(HttpStatusCode.InternalServerError, ApiErrorBody(ApiError("internal", "internal error")))
        }
    }
    routing {
        commRoutes(booted.hub, booted.state, booted.tokenRegistry, booted.lifecycle)
        // Production auth: only the operator token, or an agent watching its own session, is allowed.
        agentSocket(booted.connectorSessions, tokenAuthorize(booted.tokenRegistry))
        // /api/events — operator-only Browse over the Event-Log (CYP-39). CYP-102: scoped to the active
        // project (resolved server-side from the registry pointer; a switch re-scopes without restart).
        eventRoutes(booted.eventSink, booted.tokenRegistry, booted.projectRegistry::activeProjectId)
        // /ws/events — operator-only live-tail of the Event-Log (CYP-40), fail-closed; CYP-102 active-scoped.
        eventSocket(booted.eventSink, booted.tokenRegistry, booted.projectRegistry::activeProjectId)
        // CYP-73: agent lifecycle controls (operator-gated) + content-free status feed (participant-gated).
        lifecycleRoutes(booted.lifecycle, booted.tokenRegistry)
        lifecycleSocket(booted.lifecycle, booted.tokenRegistry)
        // CYP-96: project-settings config — GET participant (masked key), PUT operator (fail-closed).
        configRoutes(booted.projectConfig, booted.tokenRegistry, booted.activeProjectId)
        // CYP-97: agent CRUD — detail GET participant, POST/PUT/DELETE operator (fail-closed).
        agentMgmtRoutes(booted.agentManagement, booted.tokenRegistry)
        // CYP-89: Product-Lead reports — all three operator-gated/fail-closed, content-free items.
        reportRoutes(booted.reportStore, booted.tokenRegistry)
        // CYP-91: multi-project lifecycle — all operator-gated/fail-closed; cascade-delete is the
        // most destructive op (no-cross-project, opt-in worktree teardown, branches kept).
        // CYP-102: a switch re-scopes the live comm hub (HubState.rescope) so channels/inbox/acl/ws-comm
        // follow the active project without a restart.
        projectRoutes(booted.projectRegistry, booted.projectDeleter, booted.tokenRegistry, booted.state::rescope)
    }
}

/** Bind to localhost while the `?token=` fallback exists (Reviewer #4). */
const val bootHost: String = "127.0.0.1"

/**
 * Production boot path: load `platform.config.json`, resolve secrets from the host env (fail-closed),
 * orchestrate worktrees + per-agent spawns, and wire the HTTP/WS surface. Real git + real `claude`
 * spawner; an actual multi-agent run needs `ANTHROPIC_API_KEY` (D3). Bind with [bootHost].
 */
fun Application.bootPlatform(
    configFile: java.io.File,
    gitRoot: java.io.File,
    scope: kotlinx.coroutines.CoroutineScope,
): BootedPlatform {
    val config = com.tneff.cyppieagents.boot.PlatformConfig.load(configFile)
    // S12 / CYP-82: the active project (config.projectId) single-sources both the per-project API-key
    // resolution and the project-scoped worktree layout (projects/<projectId>/<agent>).
    val secrets = com.tneff.cyppieagents.boot.Secrets.fromEnv(config.agents.map { it.id }, listOf(config.projectId))
    val worktrees = com.tneff.cyppieagents.boot.WorktreeManager(
        com.tneff.cyppieagents.boot.ProcessCommandRunner(),
        gitRoot,
        config.projectId,
    )
    val booted = com.tneff.cyppieagents.boot.BootOrchestrator(
        config = config,
        secrets = secrets,
        worktrees = worktrees,
        spawner = com.tneff.cyppieagents.connector.ProcessBuilderSpawner(),
        scope = scope,
        // CYP-43: persistent SQLite sink (WAL/batch) + hook spool, both resolved from the events
        // config under the git root (not in the repo). Default in-memory only for tests.
        eventSinkFactory = {
            com.tneff.cyppieagents.events.SqliteEventSink(
                gitRoot.toPath().resolve(config.events.sinkPath),
                com.tneff.cyppieagents.events.SystemTimeSource(),
            )
        },
        spoolPath = gitRoot.toPath().resolve(config.events.spoolPath),
        // CYP-96: operator-set repo/API-key overrides persist here — out-of-repo under the gitRoot,
        // next to events.db, written 0600 + gitignored (the key never enters the repo or a log).
        projectConfigFile = gitRoot.toPath().resolve("project-config.json").toFile(),
        // CYP-91: the multi-project registry persists here — out-of-repo under the gitRoot, 0600,
        // gitignored, seeded with config.projectId on first boot.
        projectRegistryFile = gitRoot.toPath().resolve("projects.json").toFile(),
    ).boot()
    installRestrictedCors(config.web.allowedOrigins) // CORS for the web client (Spec §14, CYP-30)
    installPlatform(booted)
    return booted
}
