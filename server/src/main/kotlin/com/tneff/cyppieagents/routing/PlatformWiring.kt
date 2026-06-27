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
        commRoutes(booted.hub, booted.state, booted.tokenRegistry)
        // Production auth: only the operator token, or an agent watching its own session, is allowed.
        agentSocket(booted.connectorSessions, tokenAuthorize(booted.tokenRegistry))
        // /api/events — operator-only Browse over the Event-Log (CYP-39). Live but empty until the
        // mediator tap (CYP-37) records into booted.eventSink.
        eventRoutes(booted.eventSink, booted.tokenRegistry)
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
    val secrets = com.tneff.cyppieagents.boot.Secrets.fromEnv(config.agents.map { it.id })
    val worktrees = com.tneff.cyppieagents.boot.WorktreeManager(
        com.tneff.cyppieagents.boot.ProcessCommandRunner(),
        gitRoot,
    )
    val booted = com.tneff.cyppieagents.boot.BootOrchestrator(
        config = config,
        secrets = secrets,
        worktrees = worktrees,
        spawner = com.tneff.cyppieagents.connector.ProcessBuilderSpawner(),
        scope = scope,
    ).boot()
    installRestrictedCors(config.web.allowedOrigins) // CORS for the web client (Spec §14, CYP-30)
    installPlatform(booted)
    return booted
}
