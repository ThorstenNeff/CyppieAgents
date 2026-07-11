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
    // CYP-410 (S-A): the transport-agnostic Session Manager seam. The Local-API routes below are ONE transport
    // driving it; the Phase-2 Control-Plane transport mounts behind the same facade. Holds no Ktor type.
    val sessionManager = com.tneff.cyppieagents.boot.SessionManager(booted, authDeps)
    routing {
        // ── WS + MCP transports — NOT versioned; single-mount, OUTSIDE the /api-prefix loop (they are not
        //    `/api` REST resources: `/ws/*` are sockets, `/mcp/hub` is the connector wire, design §2.5). ──
        // CYP-255 ②: resolve the session through the ACTIVE project's runtime + require the agentId in the
        // active project's slice (fail-closed) — a same-id agent in another project can't attach cross-project.
        agentSocket(
            { booted.runtimeRegistry.active().connectorSessions },
            tokenAuthorize(booted.tokenRegistry, authDeps),
            booted.agentEventStore,
            activeAgentIds = { booted.state.agents.map { it.id }.toSet() },
        )
        // CYP-146: the in-process Hub MCP server (`POST /mcp/hub`) — `hub_send` for a Connector-A agent.
        hubMcpRoutes(booted.hub, booted.tokenRegistry)
        // CYP-138 / E2.2: the external Hub-Wire-Protocol (`/ws/hub`) for remote / BYOA connectors (auth-first,
        // caps clamped to the remote ceiling; CYP-161 WireRateLimiter; CYP-141 wire-backed ConnectorSession).
        hubWireRoutes(booted.hub, booted.tokenRegistry, booted.capabilityRegistry, booted.providerRegistry, WireRateLimiter(), booted.connectorSessions, booted.eventRecorder, { booted.hub.state.activeProjectId }, agentEvents = booted.agentEventRecorder)
        // /ws/events — CYP-188 B: MEMBER-tier live-tail, fail-closed; CYP-102 active-scoped, CYP-94 override.
        eventSocket(
            booted.eventSink, booted.tokenRegistry, booted.projectRegistry::activeProjectId,
            authorizedProjects = { booted.projectRegistry.projects().map { it.id }.toSet() },
            deps = authDeps,
        )
        // /ws/lifecycle — CYP-73/CYP-188 B: content-free status feed, read-tier. CYP-255 (.4b): active runtime.
        lifecycleSocket({ booted.runtimeRegistry.active().lifecycle }, booted.tokenRegistry, authDeps)
        // /ws/token-usage — CYP-316: content-free per-agent context-token feed, read-tier, active runtime.
        tokenUsageSocket({ booted.runtimeRegistry.active().tokenUsage }, booted.tokenRegistry, authDeps)
        // /ws/busy-state — CYP-324: content-free per-agent busy/idle feed (title-bar `*`), read-tier, active runtime.
        busyStateSocket({ booted.runtimeRegistry.active().busyState }, booted.tokenRegistry, authDeps)
        // /ws/terminal-state — CYP-354 (BE-1): content-free per-agent terminal-control-mode feed, read-tier, active runtime.
        terminalControlSocket({ booted.runtimeRegistry.active().terminalControl }, booted.tokenRegistry, authDeps)
        // /ws/terminal — CYP-332: PTY-over-WS transport for the Desktop interactive terminal (one pty4j PTY per
        // agent; single-flight §4.1). CYP-394: WRITE-tier gated (code-exec in the worktree) → operator-only today
        // via the empty [NoTerminalGrants] store; a per-agent member-grant slots in here additively (a real store
        // + operator-only grant endpoint), no gate rewrite. agentId must be in the active project.
        terminalSocket(
            { booted.ptyManager },
            knowsAgent = { id -> booted.state.agents.any { it.id == id } },
            registry = booted.tokenRegistry,
            deps = authDeps,
            grants = NoTerminalGrants,
        )
        // CYP-234a-3: the hosted API docs (`/docs*`) — Redoc(REST)+AsyncAPI(WS) rendered from the generators,
        // Bearer-only hosted spec, fail-closed authenticated. NOT versioned (docs are not an /api resource) →
        // OUTSIDE the /api+/api/v1 loop, single-mount like the sockets.
        docsRoutes(authDeps, booted.tokenRegistry)

        // ── REST — CYP-234a-2b: dual-mounted under `/api` (the live surface, byte-identical) AND `/api/v1`.
        //    The single loop body mounts the SAME sub-tree under each base, so BOTH prefixes are identical BY
        //    CONSTRUCTION (same route fns, same authenticatedApi groups, same handlers). apiBase threads the
        //    prefix into each route fn (default `/api`, so every direct-call test is unaffected). ──
        for (apiBase in listOf("/api", "/api/v1")) {
            // CYP-255 (.4b): GET /api/agents fills each per-agent read from the ACTIVE project's runtime.
            commRoutes(
                booted.hub, booted.state, booted.tokenRegistry,
                runStateOf = { booted.runtimeRegistry.active().lifecycle.runStateOf(it) },
                capabilitiesOf = { booted.runtimeRegistry.active().capabilityRegistry.get(it) },
                connectorKindOf = { booted.runtimeRegistry.active().agentConfigs.connectorKindOf(it) },
                providerOf = { booted.runtimeRegistry.active().providerRegistry.get(it) }, // CYP-137
                deps = authDeps, // CYP-178: operator gate for PUT /api/acl
                apiBase = apiBase,
            )
            // /api/events — CYP-186 BE2 MEMBER-tier metadata read; CYP-102 active-scoped; CYP-94 operator override.
            eventRoutes(
                booted.eventSink, booted.tokenRegistry, booted.projectRegistry::activeProjectId,
                authorizedProjects = { booted.projectRegistry.projects().map { it.id }.toSet() },
                deps = authDeps,
                apiBase = apiBase,
            )
            // CYP-417 (S-G): GET /api/capacity — server-authoritative capacity read (MEMBER). Same source as the
            // capacity.changed event: the ACTIVE runtime's RUNNING count + the governor estimate.
            capacityRoutes(
                governor = { booted.resourceGovernor },
                runningCount = {
                    booted.runtimeRegistry.active().lifecycle.snapshot()
                        .count { it.runState == com.tneff.cyppieagents.model.AgentRunState.RUNNING }
                },
                registry = booted.tokenRegistry,
                deps = authDeps,
                apiBase = apiBase,
            )
            // CYP-73/CYP-255 (.4b): agent lifecycle controls act on the ACTIVE project's runtime (resolver).
            lifecycleRoutes({ booted.runtimeRegistry.active().lifecycle }, booted.tokenRegistry, authDeps, apiBase = apiBase)
            // CYP-355 (BE-2): the hand-off trigger — POST /api/agents/{id}/mode on the ACTIVE project's motor.
            modeRoutes({ booted.runtimeRegistry.active().handoff }, booted.tokenRegistry, authDeps, apiBase = apiBase)
            // CYP-96/CYP-102: project-settings config — GET participant (masked key), PUT operator; live pointer.
            configRoutes(booted.projectConfig, booted.tokenRegistry, booted.projectRegistry::activeProjectId, authDeps, apiBase = apiBase, reprovision = booted.repoReprovision)
            // CYP-326: compact-orchestration config (operator) + status (read-tier).
            compactRoutes(booted.compactConfigStore, booted.compactStatus, booted.tokenRegistry, booted.projectRegistry::activeProjectId, booted.compactOnConfigUpdated, authDeps, apiBase = apiBase)
            // CYP-97/CYP-255 (.4b): agent CRUD lands in the ACTIVE project's runtime (resolver).
            agentMgmtRoutes({ booted.runtimeRegistry.active().agentManagement }, booted.tokenRegistry, authDeps, apiBase = apiBase)
            // CYP-122: connector opt-in (operator-gated, audited); CYP-255 (.4b) caps from the ACTIVE project.
            connectorRoutes(booted.state, booted.tokenRegistry, { booted.runtimeRegistry.active().capabilityRegistry }, booted.connectorOptIn, authDeps, apiBase = apiBase)
            // CYP-89: Product-Lead reports — operator-gated/fail-closed, content-free.
            reportRoutes(booted.reportStore, booted.tokenRegistry, authDeps, apiBase = apiBase)
            // CYP-91/CYP-102/CYP-255/CYP-259 (.4b): multi-project lifecycle + the switch ORCHESTRATION
            // (getOrCreate the target runtime BEFORE it becomes active, rescope the hub, rehydrate, LRU cap).
            projectRoutes(
                booted.projectRegistry, booted.projectDeleter, booted.tokenRegistry,
                // CYP-410 (S-A): the switch orchestration moved into the transport-agnostic SessionManager seam
                // (single-sourced in ProjectSwitcher.switch — the SAME steps the boot view-switch drives): mint the
                // target runtime → synchronously DRAIN+STOP the outgoing project's sessions (awaited, cap==1) BEFORE
                // the flip so any in-flight ResultEvent is attributed under active()==outgoing → rescope → rehydrate
                // → mark HOT. The route stays thin; the sequence lives in one place now.
                onActiveSwitch = { pid -> sessionManager.switchProject(pid) },
                runtimeStateOf = { booted.suspensionPolicy.stateOf(it, booted.state.activeProjectId) }, // CYP-255 (.4b)
                deps = authDeps, // CYP-178
                apiBase = apiBase,
            )
            // CYP-93: cross-project channel-share — GET participant, PUT/DELETE operator/owner (fail-closed).
            channelShareRoutes(booted.state, booted.channelShares, booted.tokenRegistry, authDeps, apiBase = apiBase)
            // CYP-181 / P2.4: authenticated self-management (change pw/email) — MEMBER-guarded Kratos shim.
            settingsClient?.let { settingsRoutes(authDeps, it, apiBase = apiBase) }
            // CYP-179 / §B(b): the platform register-wrapper — PUBLIC, branch-invariant (Kratos-admin-gated).
            registerMediator?.let { registerRoutes(it, apiBase = apiBase) }
            // CYP-182 / P3: the content-free client whoami read — PUBLIC (never a 401), no id/email/secrets.
            authMeRoutes(authDeps, apiBase = apiBase)
            workspaceRoutes(authDeps, apiBase = apiBase) // CYP-186 BE3a: OPERATOR-only workspace roster
            // CYP-234b-3 (#8): operator-gated mint/revoke of the participant-token class (uses the shared
            // authDeps.participantTokens the read-tier resolvers resolve from).
            participantTokenRoutes(
                authDeps,
                apiBase = apiBase,
                // CYP-297 Zahn 1 — the active project's agent ids for the mint collision guard.
                activeAgentIds = { booted.state.agents.map { it.id }.toSet() },
            )
        }
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
        // CYP-415 (D4): embedded-SQLite message store — messages survive restart (was InMemory-hardcoded → lost
        // on every boot). Out-of-repo under the gitRoot, WAL, gitignored; one local impl behind the store seam.
        storeFactory = {
            com.tneff.cyppieagents.comm.SqliteMessageStore(gitRoot.toPath().resolve(".cyppie/messages.db"))
        },
        // CYP-132: durable per-recipient delivered-id log — out-of-repo under the gitRoot, gitignored
        // (newline-separated keys, atomic-move flush). Survives restart so re-attach replays correctly.
        // CYP-415 (D2): embedded-SQLite delivered-id set (composite-PK, set-idempotent). Benign switch — dedup
        // is over message.id, so a fresh log never skips a new message (CYP-132 R3).
        deliveryLog = com.tneff.cyppieagents.comm.SqliteDeliveryLog(
            gitRoot.toPath().resolve(".cyppie/delivery-log.db"),
        ),
        // CYP-43: persistent SQLite sink (WAL/batch) + hook spool, both resolved from the events
        // config under the git root (not in the repo). Default in-memory only for tests.
        eventSinkFactory = {
            com.tneff.cyppieagents.events.SqliteEventSink(
                gitRoot.toPath().resolve(config.events.sinkPath),
                com.tneff.cyppieagents.events.SystemTimeSource(),
            )
        },
        // CYP-198: durable per-agent transcript store — out-of-repo under the gitRoot (WAL), so the
        // agent-window survives a restart. Retention default (last-N per agent) keeps growth bounded.
        agentEventStoreFactory = {
            com.tneff.cyppieagents.agentevents.SqliteAgentEventStore(
                gitRoot.toPath().resolve(".cyppie/agent-events.db"),
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
        remoteTokensFile = gitRoot.toPath().resolve(".cyppie/remote-tokens.db").toFile(), // CYP-415 (D2)
        // CYP-210: durable per-agent name/color/persona/launch overlay — out-of-repo under the gitRoot,
        // gitignored; overlaid over the platform.config.json seed at boot (operator edits survive restart).
        agentOverrideFile = gitRoot.toPath().resolve(".cyppie/agent-overrides.json").toFile(),
        tokenUsageFile = gitRoot.toPath().resolve(".cyppie/token-usage.db").toFile(), // CYP-325 → CYP-415 (D2)
        compactConfigFile = gitRoot.toPath().resolve(".cyppie/compact-config.db").toFile(), // CYP-326 → CYP-415 (D2)
        // CYP-256 (.5a): the durable per-project agent-set store — out-of-repo under the gitRoot, gitignored,
        // next to the other .cyppie stores. Single source for runtime-added agents (non-boot agents survive restart).
        projectAgentFile = gitRoot.toPath().resolve(".cyppie/project-agents.json").toFile(),
        // CYP-220 S6: durable report snapshots — out-of-repo under the gitRoot, gitignored (report was
        // in-memory-only before; File-durable now so reports survive a restart).
        reportFile = gitRoot.toPath().resolve(".cyppie/reports.json").toFile(),
        // CYP-215: re-encoded avatar PNGs (`<projectId>/<agentId>.png`) + the self-hosted DiceBear preset
        // asset set (`<style>/*.png`, populated offline via the DiceBear CLI) — out-of-repo, gitignored.
        avatarDir = gitRoot.toPath().resolve(".cyppie/avatars").toFile(),
        avatarPresetsDir = gitRoot.toPath().resolve(".cyppie/avatar-presets").toFile(),
        // CYP-417 (S-G): the fail-closed capacity gate — prod estimates from this JVM's -Xmx/CPUs. Tests
        // construct BootOrchestrator without it (null → ungated), so only prod respects the estimate.
        resourceGovernor = com.tneff.cyppieagents.boot.ResourceGovernor(),
    ).boot()
    installRestrictedCors(config.web.allowedOrigins) // CORS for the web client (Spec §14, CYP-30)
    // CYP-178: build the real AuthDeps — the verified-human OPERATOR path — when Kratos is configured;
    // otherwise fail-closed to token-only (human path deny-all). The role store is durable + out-of-repo.
    val authDeps = config.auth?.let { authCfg ->
        com.tneff.cyppieagents.auth.AuthDeps(
            tokens = booted.tokenRegistry,
            // CYP-240 (B): coalesce the ~N concurrent whoami a tokenless-SPA shell load fires for the SAME
            // session into ONE in-flight resolution (no cross-request cache → posture unchanged). The shared
            // resolution runs on an app-lifetime supervised scope, NOT a request coroutine, so a client
            // disconnect can't kill it for the other awaiters. D-lite (retry-once on transient) lives inside
            // the Kratos provider it wraps.
            idp = com.tneff.cyppieagents.auth.CoalescingIdentityProvider(
                delegate = com.tneff.cyppieagents.auth.KratosIdentityProvider(
                    whoamiUrl = authCfg.kratosPublicUrl.trimEnd('/') + "/sessions/whoami",
                    timeoutMs = authCfg.whoamiTimeoutMs,
                ),
                scope = kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
                ),
            ),
            roles = com.tneff.cyppieagents.auth.SqliteRoleStore(
                gitRoot.toPath().resolve(authCfg.roleDbPath),
                bootstrapOperatorId = authCfg.bootstrapOperatorIdentityId,
            ),
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
        // CYP-179 (stage-2): the constant-time floor (which also CAPS the existence check at the same value)
        // masks the Aiven-Postgres existence-check found/miss timing tell. Value single-sourced from deploy's
        // Aiven N≥40 found-path p99/max + jitter margin (config, no rebuild).
        com.tneff.cyppieagents.auth.RegisterMediator(
            backend, sideEffectScope, floorMs = authCfg.registerFloorMs,
        )
    } }
    installPlatform(booted, authDeps, settingsClient, registerMediator)
    return booted
}
