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
fun Application.installPlatform(
    booted: BootedPlatform,
    // CYP-178: the principal-resolution deps for the operator-gated `/api` writes. Defaults to the
    // token-only path (only the static operator token authenticates; the Kratos human path is deny-all,
    // fail-closed). A real boot passes an [com.tneff.cyppieagents.auth.AuthDeps] backed by the Kratos
    // [com.tneff.cyppieagents.auth.KratosIdentityProvider] + the durable [com.tneff.cyppieagents.auth.SqliteRoleStore]
    // so a verified human OPERATOR also authenticates. ONE instance is shared across every operator route.
    authDeps: com.tneff.cyppieagents.auth.AuthDeps = com.tneff.cyppieagents.auth.AuthDeps(booted.tokenRegistry),
    // CYP-181 / P2.4: the Kratos settings shim for `/api/auth/settings/*`. Null (dev/no-auth) → the routes
    // are not mounted (no human self-management surface without Kratos); a real boot passes it.
    settingsClient: com.tneff.cyppieagents.auth.KratosSettingsClient? = null,
    // CYP-179 / §B(b): the register-wrapper mediator for `POST /api/auth/register`. Null (dev/no-auth, or no
    // kratosAdminUrl) → the route is not mounted (fail-closed: no app register path); a real boot passes it.
    registerMediator: com.tneff.cyppieagents.auth.RegisterMediator? = null,
) {
    install(ContentNegotiation) { json(CommJson) }
    install(com.tneff.cyppieagents.auth.CsrfCookieIssuer) // CYP-178 RC5: issue the double-submit CSRF cookie
    install(WebSockets) { maxFrameSize = MessageInput.MAX_FRAME_BYTES } // CYP-143: protocol backstop on inject frames
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
        commRoutes(
            booted.hub, booted.state, booted.tokenRegistry, booted.lifecycle,
            capabilitiesOf = booted.capabilityRegistry::get,
            connectorKindOf = booted.agentConfigs::connectorKindOf,
            providerOf = booted.providerRegistry::get, // CYP-137: surface the provider in GET /api/agents
            deps = authDeps, // CYP-178: operator gate for PUT /api/acl
        )
        // Production auth: only the operator token, or an agent watching its own session, is allowed.
        agentSocket(booted.connectorSessions, tokenAuthorize(booted.tokenRegistry))
        // CYP-146: the in-process Hub MCP server (`POST /mcp/hub`) — exposes `hub_send` to a Connector-A
        // agent (the emission half). Token→agentId server-bound, localhost, single write path via postAsAgent.
        hubMcpRoutes(booted.hub, booted.tokenRegistry)
        // CYP-138 / E2.2: the versioned external Hub-Wire-Protocol (`/ws/hub`) for remote / BYOA connectors.
        // Auth-first; the handshake clamps self-declared caps to the REMOTE ceiling (FO#1); Send funnels
        // postAsAgent, Subscribe funnels channelMessages — the same chokepoints as the local paths.
        // CYP-161 / E2.5a: one WireRateLimiter shared across all /ws/hub connections (per-agentId token-buckets)
        // defends the live wire against a remote-connector flood (the named E2.2-M2 obligation).
        // CYP-141 / E2.5: connectorSessions lets a handshaking remote connector register a wire-backed
        // ConnectorSession → the CYP-132 deliverer pushes inbound (WireDeliver) to it like any local agent.
        hubWireRoutes(booted.hub, booted.tokenRegistry, booted.capabilityRegistry, booted.providerRegistry, WireRateLimiter(), booted.connectorSessions, booted.eventRecorder, { booted.hub.state.activeProjectId })
        // /api/events — operator-only Browse over the Event-Log (CYP-39). CYP-102: scoped to the active
        // project (resolved server-side from the registry pointer; a switch re-scopes without restart).
        // CYP-94: the operator's authorized set (MVP = all of the registry's projects) bounds the
        // optional `?projectId=<id>|all` cross-project read override; default stays forced-active.
        eventRoutes(
            booted.eventSink, booted.tokenRegistry, booted.projectRegistry::activeProjectId,
            authorizedProjects = { booted.projectRegistry.projects().map { it.id }.toSet() },
            deps = authDeps, // CYP-178: structural operator gate for GET /api/events
        )
        // /ws/events — operator-only live-tail of the Event-Log (CYP-40), fail-closed; CYP-102 active-scoped,
        // CYP-94 operator-only SubscribeEvents.projectId override (same authorized-set bound).
        eventSocket(
            booted.eventSink, booted.tokenRegistry, booted.projectRegistry::activeProjectId,
            authorizedProjects = { booted.projectRegistry.projects().map { it.id }.toSet() },
        )
        // CYP-73: agent lifecycle controls (operator-gated) + content-free status feed (participant-gated).
        lifecycleRoutes(booted.lifecycle, booted.tokenRegistry, authDeps) // CYP-178: structural operator gate
        lifecycleSocket(booted.lifecycle, booted.tokenRegistry)
        // CYP-96: project-settings config — GET participant (masked key), PUT operator (fail-closed).
        // CYP-102 fix: bind the LIVE active-pointer resolver (not booted.activeProjectId by-value) so
        // config follows a project switch, mirroring eventRoutes/eventSocket above.
        configRoutes(booted.projectConfig, booted.tokenRegistry, booted.projectRegistry::activeProjectId, authDeps) // CYP-178
        // CYP-97: agent CRUD — detail GET participant, POST/PUT/DELETE operator (fail-closed).
        agentMgmtRoutes(booted.agentManagement, booted.tokenRegistry, authDeps) // CYP-178: structural operator gate
        // CYP-122: connector opt-in (operator-gated, server-enforced, audited) — sets an agent's connector.
        connectorRoutes(booted.state, booted.tokenRegistry, booted.capabilityRegistry, booted.connectorOptIn, authDeps) // CYP-178
        // CYP-89: Product-Lead reports — all three operator-gated/fail-closed, content-free items.
        reportRoutes(booted.reportStore, booted.tokenRegistry, authDeps) // CYP-178: structural operator gate
        // CYP-91: multi-project lifecycle — all operator-gated/fail-closed; cascade-delete is the
        // most destructive op (no-cross-project, opt-in worktree teardown, branches kept).
        // CYP-102: a switch re-scopes the live comm hub (HubState.rescope) so channels/inbox/acl/ws-comm
        // follow the active project without a restart.
        projectRoutes(booted.projectRegistry, booted.projectDeleter, booted.tokenRegistry, booted.state::rescope, deps = authDeps) // CYP-178
        // CYP-93: cross-project channel-share — GET participant (disclosure), PUT/DELETE operator/owner
        // (the authorization gate, fail-closed); the AclMatrix permit takes effect via HubState.refreshShares.
        channelShareRoutes(booted.state, booted.channelShares, booted.tokenRegistry, authDeps) // CYP-178: structural operator gate
        // CYP-181 / P2.4: authenticated self-management (change pw/email) — MEMBER-guarded thin Kratos shim.
        settingsClient?.let { settingsRoutes(authDeps, it) }
        // CYP-179 / §B(b): the platform register-wrapper — PUBLIC by design (anyone may register), returns a
        // branch-invariant response so registration reveals no account existence (closes RC2 §B). Mounted only
        // when the Kratos admin URL is configured (else no app register path — fail-closed).
        registerMediator?.let { registerRoutes(it) }
        // CYP-182 / P3: the content-free client whoami read — PUBLIC by design (reports {authenticated:false}
        // to an unauthenticated caller, never a 401); no id/email/secrets.
        authMeRoutes(authDeps)
        workspaceRoutes(authDeps) // CYP-186 BE3a: OPERATOR-only workspace roster
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
        // CYP-132: durable per-recipient delivered-id log — out-of-repo under the gitRoot, gitignored
        // (newline-separated keys, atomic-move flush). Survives restart so re-attach replays correctly.
        deliveryLog = com.tneff.cyppieagents.comm.JsonFileDeliveryLog(
            gitRoot.toPath().resolve("delivery-log.txt").toFile(),
        ),
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
        // CYP-93: the cross-project channel-share gate persists here — out-of-repo, 0600, gitignored.
        channelShareFile = gitRoot.toPath().resolve("channel-shares.json").toFile(),
        // CYP-146: per-agent token-bearing --mcp-config files live here — out-of-repo under the gitRoot,
        // 0600, NEVER in the tracked worktree (F1: a committed token would leak into the shared remote).
        mcpConfigDir = gitRoot.toPath().resolve("mcp").toFile(),
        // CYP-167: durable `(projectId,agentId)→session_id` so agents resume after a restart — out-of-repo
        // under the gitRoot, gitignored (it carries no secret, just CLI session ids).
        sessionStoreFile = gitRoot.toPath().resolve("session-store.json").toFile(),
        // CYP-171: runtime-minted remote-agent tokens — out-of-repo under the gitRoot, 0600, gitignored,
        // NEVER logged (a committed token would leak into the shared remote). Secret-at-rest.
        remoteTokensFile = gitRoot.toPath().resolve("remote-tokens.json").toFile(),
    ).boot()
    installRestrictedCors(config.web.allowedOrigins) // CORS for the web client (Spec §14, CYP-30)
    // CYP-178: build the real AuthDeps — the verified-human OPERATOR path — when Kratos is configured;
    // otherwise fail-closed to token-only (human path deny-all). The role store is durable + out-of-repo.
    val authDeps = config.auth?.let { authCfg ->
        com.tneff.cyppieagents.auth.AuthDeps(
            tokens = booted.tokenRegistry,
            idp = com.tneff.cyppieagents.auth.KratosIdentityProvider(
                whoamiUrl = authCfg.kratosPublicUrl.trimEnd('/') + "/sessions/whoami",
                timeoutMs = authCfg.whoamiTimeoutMs,
            ),
            roles = com.tneff.cyppieagents.auth.SqliteRoleStore(gitRoot.toPath().resolve(authCfg.roleDbPath)),
            nowMs = { System.currentTimeMillis() },
            audit = com.tneff.cyppieagents.auth.InMemoryAuditSink(), // CYP-186 C.1: OPERATOR-only audit
            // CYP-186 C.2: the operator-token kill-switch — deploy-controlled at boot (env wins over config),
            // NEVER settable by any endpoint/UI/channel. Effective only once a role-OPERATOR exists (lockout-guard).
            operatorTokenDisabled = System.getenv("CYPPIE_OPERATOR_TOKEN_DISABLED")?.toBooleanStrictOrNull()
                ?: authCfg.operatorTokenDisabled,
        )
    } ?: com.tneff.cyppieagents.auth.AuthDeps(booted.tokenRegistry)
    // CYP-181 / P2.4: the Kratos settings shim, wired only when Kratos is configured (the human self-
    // management surface). Same public base URL as the whoami provider.
    val settingsClient = config.auth?.let {
        com.tneff.cyppieagents.auth.KratosSettingsClient(publicBaseUrl = it.kratosPublicUrl)
    }
    // CYP-179 / §B(b): the register-wrapper — wired only when the Kratos ADMIN URL is configured (loopback,
    // RC4). Its branch-divergent side-effects fire-and-forget on a dedicated supervised scope (off the response
    // path → timing parity, MUST-2); a failed side-effect is swallowed branch-blind inside the mediator.
    val registerMediator = config.auth?.let { authCfg -> authCfg.kratosAdminUrl?.let { adminUrl ->
        // Admin URL (:4434) for exists + create; public URL (:4433) for the verification/recovery flow triggers
        // that actually send the mail (CYP-179 C2 — admin-create alone sends none on v1.3.0).
        val backend = com.tneff.cyppieagents.auth.HttpKratosRegisterBackend(
            adminBaseUrl = adminUrl, publicBaseUrl = authCfg.kratosPublicUrl,
        )
        val sideEffectScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
        )
        com.tneff.cyppieagents.auth.RegisterMediator(backend, sideEffectScope)
    } }
    installPlatform(booted, authDeps, settingsClient, registerMediator)
    return booted
}
