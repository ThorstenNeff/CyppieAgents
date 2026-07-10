package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.comm.MessageStore
import com.tneff.cyppieagents.connector.CapabilityRegistry
import com.tneff.cyppieagents.connector.ClaudeCodeConnector
import com.tneff.cyppieagents.connector.Connector
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.connector.McpConnector
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.events.ContextUsageBander
import com.tneff.cyppieagents.events.EventDraft
import com.tneff.cyppieagents.events.EventProjector
import com.tneff.cyppieagents.events.EventRecorder
import com.tneff.cyppieagents.events.EventSink
import com.tneff.cyppieagents.events.InMemoryEventSink
import com.tneff.cyppieagents.events.SpoolReader
import com.tneff.cyppieagents.events.SpoolTailer
import com.tneff.cyppieagents.events.SystemTimeSource
import com.tneff.cyppieagents.mediation.MediationRouter
import com.tneff.cyppieagents.mediation.SessionRegistry
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.CapabilityGate
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.scanner.Detector
import com.tneff.cyppieagents.scanner.EventLogSignalSink
import com.tneff.cyppieagents.scanner.Scanner
import com.tneff.cyppieagents.scanner.StallDetector
import com.tneff.cyppieagents.scanner.StallSweeper
import com.tneff.cyppieagents.warden.MediatorActuator
import com.tneff.cyppieagents.warden.Policy
import com.tneff.cyppieagents.warden.StallPolicy
import com.tneff.cyppieagents.warden.StallPolicyRunner
import com.tneff.cyppieagents.warden.Warden
import kotlinx.coroutines.CoroutineScope
import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.CompactRunSummary
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/** Everything the wiring layer needs after a boot. */
class BootedPlatform(
    val hub: Hub,
    val state: HubState,
    val registry: SessionRegistry,
    val connectorSessions: ConnectorSessions,
    val tokenRegistry: TokenRegistry,
    val store: MessageStore,
    /** The Event-Log sink served by `/api/events` (CYP-39). The mediator tap (CYP-37) records into it. */
    val eventSink: EventSink,
    /** Agents whose session spawned and registered. */
    val bootedAgents: List<String>,
    /** Agents whose spawn failed — fail-closed: NO session, NO open /ws/agent for them. */
    val failedAgents: List<String>,
    /** Agent process lifecycle (CYP-73): the stop/start/restart controls + `/ws/lifecycle` status feed. */
    val lifecycle: LifecycleManager,
    /** Operator-settable per-project repo + API-key config store (S15 / CYP-96), served by `/api/config`. */
    val projectConfig: ProjectConfigStore,
    /** The active project (MVP = 1) the config endpoints resolve against. */
    val activeProjectId: String,
    /** Runtime agent CRUD (S14 / CYP-97): POST/PUT/DELETE `/api/agents` + the edit-prefill detail. */
    val agentManagement: AgentManagement,
    /** Product-Lead report snapshots (S16 / CYP-89), served operator-gated by `/api/reports`. */
    val reportStore: com.tneff.cyppieagents.report.ReportStore,
    /** Multi-project registry (S13 / CYP-91): N projects + active pointer, served by `/api/projects`. */
    val projectRegistry: ProjectRegistry,
    /** Project cascade-delete (S13 / CYP-91): fail-closed, strictly per-projectId teardown. */
    val projectDeleter: ProjectDeleter,
    /** Cross-project channel-share gate (S17 / CYP-93), served by `/api/channels/{id}/share`. */
    val channelShares: com.tneff.cyppieagents.comm.ChannelShareStore,
    /** Per-agent connector capabilities (CYP-121/122): fills `GET /api/agents` + gates the Mediator. */
    val capabilityRegistry: com.tneff.cyppieagents.connector.CapabilityRegistry,
    /** Per-agent connector provider (E2.1 / CYP-137): fills the `Agent.provider` of `GET /api/agents`. */
    val providerRegistry: com.tneff.cyppieagents.connector.ProviderRegistry,
    /** Mutable per-agent connector config incl. connectorKind (CYP-97/122): the connector-opt-in source. */
    val agentConfigs: AgentConfigRegistry,
    /** The Event-Log write tap (CYP-35): operator actions (e.g. the CYP-122 `connector.optin`) audit through it. */
    val eventRecorder: EventRecorder,
    /** Connector opt-in (CYP-122): the operator-gated, audited set-connector action served by `/api/agents/{id}/connector`. */
    val connectorOptIn: ConnectorOptIn,
    /** CYP-198: durable per-agent transcript store (reads: /ws/agent, REST, cascade-delete). */
    val agentEventStore: com.tneff.cyppieagents.agentevents.AgentEventStore,
    /** CYP-198: the transcript feeder (writes: the remote WireEvent path; local path feeds via the connector tap). */
    val agentEventRecorder: com.tneff.cyppieagents.agentevents.AgentEventRecorder,
    /**
     * CYP-247.1 (L) — the per-project agent-runtime seam. Scaffold: holds exactly one [ProjectRuntime] (the
     * boot project's), wrapping the SAME lifecycle instances exposed directly above, so behavior is
     * unchanged. Later stories make the runtime members per-project and instance/evict them on switch;
     * consumers migrate from the direct fields to `runtimeRegistry.active().*` in CYP-247.2/.3.
     */
    val runtimeRegistry: RuntimeRegistry,
    /**
     * CYP-255 (.4b) — mints a DISTINCT [ProjectRuntime] for a non-boot project on first activation. The
     * switch route feeds it to [RuntimeRegistry.getOrCreate] (so a switched-to project gets its own
     * lifecycle before any spawn), and the e2e harness seeds distinct per-project runtimes with it.
     */
    val projectRuntimeFactory: ProjectRuntimeFactory,
    /**
     * CYP-255 (.4b) / CYP-247.4 — the session-suspension teardown policy (LRU cap K on live sessions). The
     * switch route calls [RuntimeSuspensionPolicy.onActivated]; `GET /api/projects` reads
     * [RuntimeSuspensionPolicy.stateOf] to fill each [com.tneff.cyppieagents.model.Project.runtimeState].
     */
    val suspensionPolicy: RuntimeSuspensionPolicy,
    /**
     * CYP-256 (.5a) — rehydrate the ACTIVE project's runtime + HubState slice from the durable
     * [ProjectAgentStore]. The switch wiring calls it AFTER `getOrCreate` + `rescope`, so a non-boot project's
     * agents (empty in-memory slice after a restart) are refilled from the store. Idempotent.
     */
    val rehydrateActiveProject: () -> Unit,
    /** CYP-247 S2 — pending repo re-provisions (marked by the config route, consumed at agent (re)start). */
    val repoReprovision: RepoReprovision,
    /** CYP-247 S3 — drain (stop, awaited) a project's running agent sessions; the switch calls it on the
     *  OUTGOING project BEFORE `rescope` so `active()==owner` holds across the whole switch (r3 + r4). */
    val drainProject: suspend (projectId: String) -> Unit,
    // CYP-326 — compact-orchestration config store + a live status supplier (for /api/compact/*).
    val compactConfigStore: CompactConfigStore,
    val compactStatus: () -> com.tneff.cyppieagents.model.CompactStatus,
    val compactOnConfigUpdated: () -> Unit, // CYP-326 kill-switch: abort a run when "compact allowed" → false
    /** CYP-332 — the interactive-terminal PTY manager (one pty4j PTY per agent; `/ws/terminal`). */
    val ptyManager: com.tneff.cyppieagents.pty.PtyManager,
)

/**
 * Config-driven boot (Slice S8): `platform.config.json` → worktrees → per-agent
 * [ClaudeCodeConnector] spawn → hub-and-spoke channels + default ACL → production [TokenRegistry].
 *
 * Git and process spawning are injected so the whole boot is testable against fakes without a real
 * repo or a live `claude`. Reviewer enforcement is built in:
 *  - #2: the API key is injected into each session's ENV (never a CLI arg) and never logged.
 *  - #3: each agent's cwd is its own worktree; the spawner passes a minimal env whitelist.
 *  - #5: a per-agent spawn failure is isolated — it registers no session and never aborts the boot
 *        or the hub; the failed agent simply has no live `/ws/agent`.
 */
class BootOrchestrator(
    private val config: PlatformConfig,
    private val secrets: Secrets,
    private val worktrees: WorktreeManager,
    private val spawner: ProcessSpawner,
    private val scope: CoroutineScope,
    private val storeFactory: () -> MessageStore = { InMemoryMessageStore() },
    // CYP-132: durable per-recipient delivered-id log. Default in-memory (tests); bootPlatform supplies
    // a JsonFileDeliveryLog out-of-repo under the gitRoot (gitignored).
    private val deliveryLog: com.tneff.cyppieagents.comm.DeliveryLog = com.tneff.cyppieagents.comm.InMemoryDeliveryLog(),
    // Default in-memory; CYP-43 swaps in a SqliteEventSink from the events config (sinkPath/WAL).
    private val eventSinkFactory: () -> EventSink = { InMemoryEventSink(SystemTimeSource()) },
    // CYP-198: durable per-agent transcript store. Default in-memory (tests); bootPlatform supplies a
    // SqliteAgentEventStore out-of-repo under the gitRoot (WAL, gitignored).
    private val agentEventStoreFactory: () -> com.tneff.cyppieagents.agentevents.AgentEventStore =
        { com.tneff.cyppieagents.agentevents.InMemoryAgentEventStore() },
    // Hook spool path; null → no spool tailer (default in tests). bootPlatform/CYP-43 supply it.
    private val spoolPath: java.nio.file.Path? = null,
    // Mediator-Aufsicht (07/S11): the Scanner's detector set. Empty = the scaffold runs but finds
    // nothing yet; CYP-61 adds the stall detector here with no change to the frame.
    private val scannerDetectors: List<Detector> = emptyList(),
    // The Warden's policy set (07/S11, CYP-62). Empty = the Warden runs but routes nothing yet; the
    // stall policy (CYP-63) registers here with no change to the frame.
    private val wardenPolicies: List<Policy> = emptyList(),
    // Operator-settable per-project config store (S15 / CYP-96); null file → in-memory (tests).
    // bootPlatform supplies the out-of-repo, gitignored, 0600 file under the gitRoot.
    private val projectConfigFile: java.io.File? = null,
    // Multi-project registry persistence (S13 / CYP-91); null → in-memory (tests). bootPlatform
    // supplies the out-of-repo, gitignored, 0600 file under the gitRoot (seeded with config.projectId).
    private val projectRegistryFile: java.io.File? = null,
    // S17 / CYP-93: the cross-project channel-share gate persists here — out-of-repo, 0600, gitignored.
    private val channelShareFile: java.io.File? = null,
    // CYP-146: dir for per-agent --mcp-config files (token-bearing) — out-of-repo under the gitRoot, 0600.
    // Null (tests/dev) → no Hub MCP tools wired into the Connector-A spawn.
    private val mcpConfigDir: java.io.File? = null,
    // CYP-120: the connector-injection seam. Default (null) builds the real Connector A
    // (stream-json [ClaudeCodeConnector]) — production boot is unchanged. A test/CYP-121 harness can
    // inject a `FakeConnector(caps)` with reduced tri-state capabilities to prove Mediator gating +
    // degradation events, and CYP-122 selects A-vs-B per agent here. The factory receives the
    // already-built [Connector] so a decorator can wrap it; ignore the arg to fully replace it.
    private val connectorFactory: ((default: Connector) -> Connector)? = null,
    // CYP-210: durable per-agent name/color/persona/launch overlay (`.cyppie/agent-overrides.json`), out-of-
    // repo under the gitRoot. Null (tests) = in-memory off-switch (no restart durability).
    private val agentOverrideFile: java.io.File? = null,
    // CYP-325 (defect 2): durable per-agent last-context-token overlay (.cyppie/token-usage.json); null = in-memory.
    private val tokenUsageFile: java.io.File? = null,
    // CYP-326: persisted compact-orchestration config (.cyppie/compact-config.json); null = in-memory (tests).
    private val compactConfigFile: java.io.File? = null,
    // CYP-220 S6: durable report-snapshot store (`.cyppie/reports.json`), out-of-repo under the gitRoot. Null
    // (tests) = in-memory off-switch. Was in-memory-only before S6; now File-durable (reports survive a restart).
    private val reportFile: java.io.File? = null,
    // CYP-215: the on-disk root for re-encoded avatar PNGs (`.cyppie/avatars/<projectId>/<agentId>.png`) and
    // the self-hosted DiceBear preset asset root (`.cyppie/avatar-presets/<style>/*.png`). Null (tests) = off.
    private val avatarDir: java.io.File? = null,
    private val avatarPresetsDir: java.io.File? = null,
    // CYP-163: sandbox-ONLY `bypassPermissions` grant, threaded into the [ClaudeCodeConnector]. Default
    // (null) = production-sharp — the prod boot NEVER passes a grant, so the spawn stays Gate #4 fail-closed
    // (never bypass). Non-null ONLY on the RB1 throwaway-sandbox harness path (human + reviewer signed,
    // [SandboxBypassGrant.rb1Sandbox]); it lets the disposable-sandbox worker write/commit/push autonomously.
    private val sandboxBypassGrant: com.tneff.cyppieagents.connector.SandboxBypassGrant? = null,
    // CYP-167: durable session-resume store (`(projectId,agentId)→session_id`); null → feature off
    // (in-memory tests/dev, no `--resume`). bootPlatform supplies the out-of-repo, gitignored file
    // under the gitRoot, so an agent resumes its conversation after a server restart.
    private val sessionStoreFile: java.io.File? = null,
    // CYP-171 / E2.6 (S3): durable secret-at-rest store for runtime-minted remote-agent tokens; null →
    // in-memory (tests). bootPlatform supplies the out-of-repo, gitignored, 0600 file under the gitRoot.
    private val remoteTokensFile: java.io.File? = null,
    // CYP-256 (.5a): durable per-project agent-set store (`.cyppie/project-agents.json`), out-of-repo under the
    // gitRoot, gitignored. Null (tests) = in-memory off-switch (no cross-restart durability, pre-.5a behavior).
    // The single source for RUNTIME-ADDED agents → a non-boot project's agents survive a restart (rehydrated).
    private val projectAgentFile: java.io.File? = null,
    // CYP-255 (.4b) / CYP-247.4: the HARD LRU cap on projects whose agent SESSIONS run (the ratified
    // teardown — active + K-1 recent-hot stay live; beyond that the LRU project is session-suspended,
    // resumed via --resume on re-entry). Default 3 (ratified). ≤ 0 disables suspension (unbounded live).
    // CYP-247 S3 (D3=B): default cap = 1 → teardown-on-switch. Only the ACTIVE project has live agent
    // sessions, so `active() == owner` holds and the shared mouth/projector attribute correctly (no S5
    // background-live mouth-attribution needed). A policy value, not a new mechanism; cap>1 (background-live)
    // is a later opt-in (S5). cap<=0 still disables suspension entirely (unbounded).
    private val runtimeSuspensionCap: Int = 1,
    // CYP-247 S4 / D5: the residual-project prune is OPT-IN. Default false → the boot reconciler only LOGS which
    // orphaned `projects/<pid>/*` dirs it WOULD prune (never auto-deletes a tree that may hold unpushed work).
    private val pruneResidualProjects: Boolean = false,
    // CYP-348: the shared launch-command seam threaded into the terminal [PtyManager]. Null → the default
    // `["bash","-l"]` (resolved in [boot]) — the CYP-333 interim terminal is a bash login-shell in the
    // agent's worktree, NOT a second interactive `claude` (Auftraggeber 2026-07-10: two auto-approving agents
    // in one worktree = edit-conflict risk). Injectable so the full-boot E2E rides the real wiring with a fake
    // command; BE-2 (CYP-355) reuses the same seam per-open for `claude --resume <sid>`.
    private val terminalLaunchCommand: List<String>? = null,
) {
    private val log = LoggerFactory.getLogger("boot.orchestrator")

    fun boot(): BootedPlatform {
        // S15 / CYP-96: operator overrides for repo + API key, per project, fall back to boot config.
        val projectConfig = ProjectConfigStore(projectConfigFile, config.repo, secrets)
        // CYP-247 S4 (§6.1, live-box migration, Rule ①): ADOPT a legacy shared `gitRoot/repo` clone into the
        // boot project's `clones/<pid>` (move + `git worktree repair`), preserving the existing worktrees + the
        // Auftraggeber's CLAUDE.md — NEVER a re-clone-fresh. No-op on a fresh install / once already per-project.
        if (worktrees.adoptLegacyClone()) log.info("migrated legacy single-clone layout → per-project clones/{}", config.projectId)
        // Repo change takes effect at the next boot (design §3.2): clone the resolved (override→boot) repo.
        // Idempotent: a no-op after an adopt (the boot clone now exists at clones/<pid>).
        worktrees.ensureClone(projectConfig.resolvedRepo(config.projectId))

        val agents = config.agents.map { Agent(it.id, it.name, it.role, it.worktreeName, color = it.color?.ifBlank { null }) }
        // S17 / CYP-93: the cross-project share gate. The hub consults it for the AclMatrix permit
        // (channels authorized to reach into the active project); revoke → immediate fail-closed.
        val channelShares = com.tneff.cyppieagents.comm.ChannelShareStore(channelShareFile)
        // CYP-308 (Auftraggeber-confirmed OWNERSHIP model, supersedes the CYP-305 adopt-heuristic): the
        // config-seeded bootstrap agents belong PERMANENTLY to config.projectId — re-seeded from
        // platform.config.json EVERY boot (config.json is their durable source; NO store-persistence → no
        // config↔store drift, the CYP-220 lesson). Every OTHER project owns its own agents in its store. The
        // durable ProjectRegistry active pointer is pure VIEW (which project you see at boot), NOT ownership: it
        // is synced onto HubState AFTER the config seed (see the boot-sync below the spawn loop), never seeded
        // under. So a durable active ≠ config.projectId shows ITS OWN roster (empty for a fresh project); the
        // config agents stay owned by — and visible under — config.projectId. Restart-stable by construction.
        val projectAgents = ProjectAgentStore(projectAgentFile)
        val projectRegistry = ProjectRegistry(projectRegistryFile, config.projectId)
        val durableActive = projectRegistry.activeProjectId()
        // Operator is a privileged ACL participant (member of every channel) — the human/UI viewer.
        // S12 / CYP-81: single-source the active project into the hub (scopes channels/ACL/messages). The config
        // agents ALWAYS seed under config.projectId (ownership); the view is switched to durableActive afterwards.
        val state = HubState.hubAndSpoke(agents, HubState.OPERATOR_ID, config.projectId) { pid ->
            channelShares.sharedInboundChannelIds(pid)
        }
        // CYP-247.1/.2: the per-project runtime seam. Declared HERE (before the connector + the worktree
        // lambdas below) so those consumers capture it and resolve `active().worktrees` LAZILY — the boot
        // project's ProjectRuntime is registered further down (once lifecycle/agentManagement exist) but
        // still BEFORE the spawn loop, so every worktree op / spawn resolves through a live-registered runtime.
        val runtimeRegistry = RuntimeRegistry { state.activeProjectId }
        val store = storeFactory()
        val hub = Hub(state, store)
        val registry = SessionRegistry()
        val turnQueue = SessionTurnQueue()
        val sessions = ConnectorSessions()
        val tokenRegistry = TokenRegistry(secrets.agentTokens, secrets.operatorToken)
        // CYP-171: restore persisted remote-agent tokens into the registry (a pre-provisioned remote agent
        // reconnects after a restart), and compose the mint+persist / revoke issuer for AgentManagement.
        val remoteTokenStore = RemoteTokenStore(remoteTokensFile)
        remoteTokenStore.all().forEach { (agentId, token) -> tokenRegistry.bind(token, agentId) }
        val remoteTokenIssuer = RemoteTokenIssuer(tokenRegistry, remoteTokenStore)

        // Observability ingestion (CYP-37): one EventRecorder feeds the shared sink; the projector
        // turns masked stream events into content-free drafts at the connector tap and in the router.
        // All knobs come from the events config (CYP-43).
        val ev = config.events
        val eventSink = eventSinkFactory()
        val eventRecorder = EventRecorder(eventSink, scope, capacity = ev.queueCapacity, batchSize = ev.batchSize)
            .also { it.start() }
        // CYP-198: the durable per-agent transcript store + its ordered non-blocking feeder (local connector
        // events via the observer tap; remote WireEvents via hubWireRoutes).
        val agentEventStore = agentEventStoreFactory()
        val agentEventRecorder = com.tneff.cyppieagents.agentevents.AgentEventRecorder(agentEventStore, scope)
        val bander = ContextUsageBander(
            contextWindowTokens = ev.contextWindowTokens,
            bandPctWidth = ev.bandPct,
            compactPct = ev.compactPct,
        )
        // CYP-121: per-agent connector capabilities, populated below once the connector is resolved.
        // Built empty here so the projector + stall detector can hold a live `::get` resolver; reads
        // happen at event-time (after boot populates it). Connector A = all-AVAILABLE → no gating.
        val capabilityRegistry = CapabilityRegistry()
        // E2.1 / CYP-137: per-agent provider, populated below from the connector (like capabilities).
        val providerRegistry = com.tneff.cyppieagents.connector.ProviderRegistry()
        // S12 / CYP-83: events carry the active project, single-sourced from config (not a constant).
        // CYP-121: the projector gates tool.* (toolGranularity) and context.usage (structuredUsage).
        // CYP-255 (.4b): the projector is SHARED (one instance); it gates tool.*/context.usage against the
        // ACTIVE project's capabilities. Resolver, not the boot registry — a switch re-targets it.
        val eventProjector = EventProjector(
            bander,
            projectId = config.projectId,
            capabilities = { runtimeRegistry.active().capabilityRegistry.get(it) },
            // CYP-316: feed the live context-token value to the ACTIVE project's tracker (the /ws/token-usage
            // source) — same active()-routing as the capabilities resolver, since the projector is shared.
            onContextTokens = { agentId, tokens -> runtimeRegistry.active().tokenUsage.onResult(agentId, tokens) },
            // CYP-324: feed busy/idle to the ACTIVE project's tracker (the /ws/busy-state source) — same
            // active()-routing (shared projector); turn.start→true, result/exit/stop→false.
            onBusy = { agentId, busy -> runtimeRegistry.active().busyState.set(agentId, busy) },
            // CYP-326: route a compaction-completed system event to the ACTIVE project's signal (same active()-routing).
            onCompactCompleted = { agentId -> runtimeRegistry.active().compactSignal.onCompleted(agentId) },
        )

        val router = MediationRouter(registry, hub, eventRecorder, eventProjector)

        // CYP-132: durable inbound delivery — the mediator's "ear". Wired to the SINGLE write funnel
        // (hub.onPosted, called after persist) and to session (re)attach, so a PO→worker TASK (and a
        // worker→PO STATUS, watch-as-inbound) is injected into the recipient's session, surviving a
        // down/not-yet-attached recipient (replayed on attach). Reads through the active HubState
        // (rescope-aware) + visibleMessages gate (project-scope + canRead, R1); dedups over message.id
        // (R3). The two wirings below are the production path the boot-wiring test (R2) pins.
        val deliverer = com.tneff.cyppieagents.mediation.MessageDeliverer(
            state = { hub.state },
            projectId = { hub.state.activeProjectId },
            // CYP-255 (.4b): the deliverer is the SHARED "ear"; it injects into the ACTIVE project's sessions.
            // Each per-project runtime wires its OWN sessions to onSessionAttached (below for boot; the factory
            // for the rest), so replay-on-attach fires per project while live delivery targets the active one.
            sessions = { runtimeRegistry.active().connectorSessions },
            store = store,
            log = deliveryLog,
            scope = scope,
            recorder = eventRecorder,
            projector = eventProjector,
        )
        hub.onPosted = deliverer::onPosted
        sessions.addRegisterListener(deliverer::onSessionAttached)

        // S14 / CYP-97: the mutable per-agent connector config (launch + persona), seeded from config.
        // The connector reads personaOf at open() to place CLAUDE.md; AgentManagement mutates it.
        // CYP-133: when an agent carries no explicit persona, seed the role default (PO = coordinator /
        // decompose+delegate, WORKER = work+report) so a spawned agent is never persona-less — the RB1
        // gap where the PO had no coordinator persona and did the task itself. Single-sourced in [Personas].
        val agentConfigs = AgentConfigRegistry(
            config.agents.map { it.copy(claudeMd = it.claudeMd?.ifBlank { null } ?: Personas.forRole(it.role)) },
        )

        // CYP-210: apply the durable overlay OVER the platform.config.json seed (overlay wins per-field), so
        // operator edits of name/color/persona/launch survive a restart. Scoped to the active project.
        val agentOverrides = AgentOverrideStore(agentOverrideFile)
        val tokenUsageStore = TokenUsageStore(tokenUsageFile) // CYP-325 (defect 2): shared, keyed by projectId
        // CYP-256 (.5a): the durable per-project agent-set store — constructed EARLY (above, for the CYP-305
        // effective-active seam); single source for runtime-added agents.
        // CYP-215: the avatar stores (blob bytes on disk + the self-hosted DiceBear preset resolver).
        val avatarBlobs = com.tneff.cyppieagents.avatar.AvatarBlobStore(avatarDir)
        val avatarPresets = com.tneff.cyppieagents.avatar.AvatarPresetResolver(avatarPresetsDir)
        agentOverrides.allFor(config.projectId).forEach { (agentId, ov) ->
            if (ov.name != null || ov.color != null) state.editAgent(agentId, ov.name, ov.color)
            // CYP-215: apply the durable avatar overlay too (Preset or the stored Upload ref) so it survives a restart.
            if (ov.avatar != null) state.setAvatar(agentId, ov.avatar)
            if (ov.persona != null || ov.launch != null) {
                val curCfg = agentConfigs.configOf(agentId)
                agentConfigs.put(agentId, ov.launch ?: curCfg?.launch ?: "claude", ov.persona ?: curCfg?.persona)
            }
        }

        // CYP-120: build the real Connector A by default, then pass it through the injection seam.
        // Prod leaves connectorFactory null → the stream-json connector is used verbatim.
        // CYP-146: the Hub MCP config writer (token-bearing mcp-config, out-of-repo 0600) + the per-agent
        // token resolver, so a Connector-A spawn exposes `hub_send`. Localhost-bound URL derived from the
        // hub config. Null mcpConfigDir (tests) → no hub tools wired.
        val hubMcpUrl = config.hub.url.replace("localhost", "127.0.0.1").trimEnd('/') + "/mcp/hub"
        val mcpConfigWriter = mcpConfigDir?.let {
            com.tneff.cyppieagents.connector.HubMcpConfigWriter(it, hubMcpUrl)
        }
        val tokenByAgent = secrets.agentTokens.entries.associate { (token, agent) -> agent to token }
        // CYP-167: durable session-resume binding store. Null file → in-memory off-switch (tests/dev).
        val sessionStore = sessionStoreFile?.let { com.tneff.cyppieagents.connector.JsonFileSessionStore(it) }
        val defaultConnector = ClaudeCodeConnector(
            spawner = spawner,
            // CYP-247 S1b: resolve the spawn cwd root for the SPAWNING project (threaded pid), not active() —
            // so a non-boot agent lands in ITS `projects/<pid>/` root even when another project is active.
            worktreesRoot = { pid -> worktrees.forProject(pid).worktreesRoot },
            // S15 / CYP-96 + CYP-247 S1b: resolve the key AT SPAWN for the SPAWNING project (threaded pid) —
            // operator override (store) → env fallback (Secrets) — never a boot-frozen config.projectId.
            resolveApiKey = { pid -> projectConfig.resolvedApiKey(pid) },
            registry = registry,
            router = router,
            turnQueue = turnQueue,
            scope = scope,
            recorder = eventRecorder,
            projector = eventProjector,
            agentEvents = agentEventRecorder, // CYP-198: persist the local agent's stream-json transcript
            // CYP-310: no personaOf — the connector no longer auto-writes CLAUDE.md at spawn (managed via
            // the claude-md endpoints). The stored persona config field is deprecated (removal = CYP-311).
            mcpConfigWriter = mcpConfigWriter, // CYP-146: expose hub_send to the Connector-A spawn
            tokenFor = { tokenByAgent[it] },
            // CYP-163: null in prod (sharp, Gate #4); non-null ONLY via the RB1 sandbox harness path.
            sandboxBypassGrant = sandboxBypassGrant,
            // CYP-167 + CYP-247 S1b: read-before-spawn / write-after-init keyed by the THREADED owning projectId
            // (passed to open(agentId, worktree, projectId) by the per-project spawn lambda), not a boot constant.
            sessionStore = sessionStore,
        )
        // CYP-122: Connector B (MCP) + per-agent selection. The router picks A vs B by the agent's
        // declared connectorKind at spawn; it IS a Connector so the connectorFactory seam still wraps it.
        val mcpConnector = McpConnector(hub)
        val connectorRouter = ConnectorRouter(
            streamJson = defaultConnector,
            mcp = mcpConnector,
            // CYP-255 (.4b): resolve the serving kind from the ACTIVE project's config (a switched-to project's
            // agent uses ITS declared kind). Reads active() — so the caps-intake loop below runs AFTER the boot
            // runtime is registered (the reorder), else active() would throw during boot.
            kindOf = { runtimeRegistry.active().agentConfigs.connectorKindOf(it) },
        )
        val connector: Connector = connectorFactory?.invoke(connectorRouter) ?: connectorRouter

        // Hook spool tailing (CYP-38 reader + CYP-37 tailer, at-most-once). Started only when a path
        // is configured; bootPlatform supplies it, CYP-43 makes it a config knob.
        spoolPath?.let { SpoolTailer(SpoolReader(it), eventRecorder, scope).start() }

        // Mediator-Aufsicht Sense stage (07/S11). The Scanner consumes the same event bus via
        // `subscribe` and emits Signals back into it. Read-only on the stream + signal-emit only — it
        // gets no connector/session handle, so it cannot act on an agent (CYP-60).
        val signalSink = EventLogSignalSink(eventRecorder)
        // CYP-61 stall detector: rate-limit throttle ∧ silence>T → stall.suspected. onEvent senses
        // (arm/disarm) via the Scanner's fan-out; the timed decision is driven by the StallSweeper.
        // CYP-121: the detector won't arm for an agent whose connector lacks a trusted rateLimitSignal.
        val stallDetector = StallDetector(capabilities = { runtimeRegistry.active().capabilityRegistry.get(it) })
        Scanner(eventSink, scannerDetectors + stallDetector, signalSink, scope).start()
        StallSweeper(stallDetector, signalSink, scope, clock = System::currentTimeMillis).start()

        // Mediator-Aufsicht Decide+Act stage (07/S11, CYP-62): the Warden is a separate bus consumer
        // that listens for signal Events and routes each to the Policy that handles it. Policies act
        // ONLY through the Actuator — the single write authority toward an agent (nudge = a user-turn
        // on the agent's session = the Mediator stdin).
        val actuator = MediatorActuator({ runtimeRegistry.active().connectorSessions }, signalSink, config.projectId)
        // CYP-63 stall policy: opens one incident/agent on stall.suspected, nudges with growing backoff,
        // escalates after N, recovers on activity. The runner feeds it activity + the backoff clock.
        val stallPolicy = StallPolicy(clock = System::currentTimeMillis, actuator = actuator, signals = signalSink)
        Warden(eventSink, wardenPolicies + stallPolicy, actuator, scope).start()
        StallPolicyRunner(stallPolicy, eventSink, scope).start()

        // Agent lifecycle (CYP-73): the spawn path is single-sourced here so boot-spawn and the runtime
        // start/restart controls can't drift. Boot fail-closed per agent (Reviewer #5): a failed spawn
        // → ERROR, no session, no /ws/agent; the hub and other agents are unaffected.
        // CYP-316: the boot project's per-agent context-token feed (source of /ws/token-usage). Fed by the
        // shared projector's onContextTokens (active-routed) and reset by this project's lifecycle stop/restart.
        val tokenUsageTracker = AgentTokenUsageTracker(config.projectId, tokenUsageStore) // CYP-325: rehydrate on boot
        val busyStateTracker = AgentBusyStateTracker() // CYP-324: boot project's /ws/busy-state source
        val terminalControlTracker = TerminalControlStateTracker() // CYP-354 (BE-1): boot project's /ws/terminal-state source
        val compactSignal = CompactCompletionSignal() // CYP-326: boot project's compaction-completed source
        val repoReprovision = RepoReprovision() // CYP-247 S2: pending repo-change → re-provision on next (re)start.
        // CYP-247 S4 (§6.2) — boot RECONCILER (idempotent, logged): for each registered project, a clone whose
        // remote no longer matches `resolvedRepo(pid)` is marked STALE → re-provisioned on the next agent
        // (re)start (S2 §2c, §2d work-guarded, never a silent wipe). Residual `projects/<pid>/*` dirs NOT in the
        // registry are pruned ONLY on the opt-in (D5); by default they are PRESERVED + logged ("what WOULD prune").
        run {
            // CYP-247 S4 (Rule ① defense-in-depth): HARD-exclude config.projectId from the residual set. The boot
            // project owns projects/<config.projectId> + the Auftraggeber's CLAUDE.md; if a loaded projects.json
            // ever omits it (config drift / a deleted registry row) it must NEVER be pruned — not even on the opt-in.
            val known = projectRegistry.projects().map { it.id }.toSet() + config.projectId
            for (pid in known) {
                val current = worktrees.forProject(pid).cloneRemoteUrl() ?: continue // no clone yet (lazy) → nothing to reconcile
                val wanted = projectConfig.resolvedRepo(pid).url
                if (current != wanted) {
                    log.info("reconciler: project '{}' clone remote != configured repo → marked for re-provision on next (re)start", pid)
                    repoReprovision.markStale(pid, discardUnpushed = false)
                }
            }
            val residual = worktrees.residualProjectDirs(known)
            if (residual.isNotEmpty()) {
                if (pruneResidualProjects) {
                    residual.forEach { worktrees.deleteProject(it) }
                    log.info("reconciler: pruned {} residual project dir(s) (opt-in): {}", residual.size, residual)
                } else {
                    log.warn("reconciler: {} residual project dir(s) PRESERVED — would prune (opt-in pruneResidualProjects): {}", residual.size, residual)
                }
            }
        }
        // CYP-247 S1: ensure the ACTIVE project's clone (LAZY, D6) THEN the agent worktree — single-sourced from
        // `resolvedRepo(activePid)` so the clone URL/branch and the worktree base-branch cannot drift. Reused by
        // every ensureWorktree seam (boot lifecycle + agent-CRUD + factory + rehydrate). ensureClone is idempotent
        // (no-op once the per-project clone exists), so this is the lazy first-need clone for a non-boot project.
        val ensureActiveWorktree: (String) -> Unit = { worktreeName ->
            val pid = state.activeProjectId
            val wt = runtimeRegistry.active().worktrees
            // CYP-247 S2 (D4): consume a pending repo re-provision BEFORE (re)creating the worktree. The §2d
            // work-guard runs first: if any agent worktree has uncommitted/unpushed work AND there is no
            // discard opt-in, BLOCK the teardown (leave the mark + the old clone; the (re)start proceeds on the
            // current repo) — never a silent loss. Otherwise tear the old clone down (S1 deleteProject partition)
            // and fall through to a FRESH clone from the new repo.
            repoReprovision.pending(pid)?.let { req ->
                val atRisk = if (req.discardUnpushed) emptyList() else wt.unpushedWork()
                if (atRisk.isNotEmpty()) {
                    log.warn(
                        "repo re-provision of project '{}' BLOCKED — would destroy work in {}; push it or re-PUT the repo with discardUnpushed=true",
                        pid, atRisk,
                    )
                } else {
                    log.info("re-provisioning project '{}' onto its new repo (tearing down the old clone + worktrees)", pid)
                    worktrees.deleteProject(pid) // reuse the S1 teardown: the project's worktrees + its OWN old clone
                    repoReprovision.clear(pid)
                }
            }
            val repo = projectConfig.resolvedRepo(pid)
            wt.ensureClone(repo)                         // fresh clone from the NEW repo if torn down; else idempotent
            wt.ensureWorktree(worktreeName, repo.branch)
        }
        val lifecycle = LifecycleManager(
            initialWorktrees = config.agents.associate { it.id to it.worktreeName },
            sessions = sessions,
            // CYP-247.2: ensure the worktree under the ACTIVE project's root (lazy via the runtime seam).
            ensureWorktree = ensureActiveWorktree, // CYP-247 S1: lazy per-project clone + worktree off resolvedRepo(pid)
            // CYP-247 S1b: thread the boot project's pid into the spawn (key/cwd/stamp from config.projectId, explicit).
            spawn = { id, worktree -> connector.open(id, worktree, config.projectId) },
            // CYP-330: the fresh (context-free) rollback — clear the durable resume entry, then open WITHOUT
            // `--resume` so a failed resume-respawn still reaches RUNNING (never dead in ERROR).
            spawnFresh = { id, worktree -> sessionStore?.clear(config.projectId, id); connector.open(id, worktree, config.projectId) },
            recorder = eventRecorder,
            projector = eventProjector,
            onContextReset = { tokenUsageTracker.reset(it) },   // CYP-316: stop/restart → fresh context → null
            onContextForget = { tokenUsageTracker.forget(it) }, // CYP-316: remove → drop the token entry
            // CYP-354 (BE-1): fan out the SAME lifecycle callbacks to the terminal-control tracker — reusing the
            // existing seam (no new LifecycleManager param). Stop/restart ends any hand-off → back to MEDIATED;
            // remove → drop the entry. This deliberately avoids touching LifecycleManager (CYP-351 rebuilds it).
            onBusyReset = { busyStateTracker.reset(it); terminalControlTracker.reset(it) },   // CYP-324/354: stop/restart → clear `*` + mode→MEDIATED
            onBusyForget = { busyStateTracker.forget(it); terminalControlTracker.forget(it) }, // CYP-324/354: remove → drop both entries
        )

        // CYP-122: the single, audited, server-enforced point that sets an agent's connector (opt-in).
        val connectorOptIn = ConnectorOptIn(
            agentConfigs = { runtimeRegistry.active().agentConfigs },
            capabilityRegistry = { runtimeRegistry.active().capabilityRegistry },
            eventRecorder = eventRecorder,
            projectId = { state.activeProjectId },
        )

        // S14 / CYP-97: runtime agent CRUD over the (now mutable) HubState topology + lifecycle + config.
        val agentManagement = AgentManagement(
            state = state,
            lifecycle = lifecycle,
            configs = agentConfigs,
            // CYP-247.2: worktree create/delete on the ACTIVE project's manager (lazy via the runtime seam).
            ensureWorktree = ensureActiveWorktree, // CYP-247 S1: lazy per-project clone + worktree off resolvedRepo(pid)
            deleteWorktree = { worktreeName -> runtimeRegistry.active().worktrees.deleteWorktree(worktreeName) },
            onConnectorOptIn = connectorOptIn::apply, // CYP-122: create-as-B audits like the dedicated opt-in
            remoteToken = remoteTokenIssuer, // CYP-171: mint/revoke the per-agent token for a remote create/remove
            overrides = agentOverrides, // CYP-210: persist name/color/persona/launch edits (restart-durable)
            // CYP-246: CRUD writes (override/avatar-blob) follow the ACTIVE project, not a boot-frozen constant,
            // so an edit made after a switch lands in the switched project's overlay — parity with the per-project
            // agent slice. Reads the live pointer HubState.rescope updates.
            activeProjectId = { state.activeProjectId },
            avatarBlobs = avatarBlobs,   // CYP-215: re-encoded avatar PNG store
            avatarPresets = avatarPresets, // CYP-215: self-hosted DiceBear preset resolver
            projectAgents = projectAgents, // CYP-256 (.5a): durable single source for runtime-added agents
            // CYP-310: resolve the ACTIVE project's worktree dir for CLAUDE.md read/write; seed the remote-agent
            // set from config (a remote/BYOA agent has no local worktree → agent_not_local).
            worktreeDirOf = { runtimeRegistry.active().worktrees.worktreeDir(it) },
            initialRemoteAgents = config.agents.filter { it.remote }.map { it.id }.toSet(),
        )

        // CYP-247.1/.2 (L): register the boot project's runtime in the per-project seam L de-singletonizes
        // (the registry itself was declared above so the connector + worktree lambdas could capture it).
        // Registered HERE — once lifecycle/agentManagement exist, but still BEFORE the spawn loop below — so
        // the lazily-resolving worktree ops / spawns always find a live runtime. Scaffold: one runtime holding
        // the SAME instances built above (incl. the boot `worktrees`), so behavior is unchanged.
        runtimeRegistry.register(
            ProjectRuntime(
                projectId = config.projectId, // CYP-308: the boot runtime backs config.projectId (owns config agents)
                lifecycle = lifecycle,
                connectorSessions = sessions,
                agentConfigs = agentConfigs,
                capabilityRegistry = capabilityRegistry,
                providerRegistry = providerRegistry,
                agentManagement = agentManagement,
                worktrees = worktrees,
                tokenUsage = tokenUsageTracker, // CYP-316
                busyState = busyStateTracker, // CYP-324
                terminalControl = terminalControlTracker, // CYP-354 (BE-1)
                compactSignal = compactSignal, // CYP-326
            ),
        )

        // CYP-255 (.4b) — the per-project runtime FACTORY: mints DISTINCT lifecycle machinery for a
        // NON-boot project the first time it is activated (RuntimeRegistry.getOrCreate on switch), so two
        // projects with an agent id `backend` get SEPARATE sessions / worktrees / configs / registries — the
        // collision the money-tooth pins. It closes over the SHARED spine (state, connector, deliverer,
        // eventRecorder/projector, worktrees-base, the CRUD/override/token deps): the spine resolves
        // `runtimeRegistry.active().*`, so a switch re-targets it onto whichever project the factory minted.
        //
        // The BOOT project keeps its pre-built runtime (registered above) rather than being minted here — its
        // instances are already wired into boot (the deliverer ear on `sessions`, the intake loop's caps
        // writes, the override overlay on `agentConfigs`), so re-minting would orphan those. A non-boot
        // project has none of that history, so a fresh empty runtime is correct: it starts with NO agents and
        // the operator adds them (AgentManagement.add), exactly like the boot project got its from config.
        val projectRuntimeFactory = ProjectRuntimeFactory { pid ->
            val pSessions = ConnectorSessions()
            // Wire THIS project's sessions to the shared mediator ear, so replay-on-attach fires for its
            // agents too (the deliverer targets active().connectorSessions for live delivery).
            pSessions.addRegisterListener(deliverer::onSessionAttached)
            val pConfigs = AgentConfigRegistry(emptyList()) // empty: agents are added at runtime, not from config
            val pCaps = CapabilityRegistry()
            val pProvider = com.tneff.cyppieagents.connector.ProviderRegistry()
            val pWorktrees = worktrees.forProject(pid) // CYP-247 S1: this project's OWN clone (clones/<pid>) + worktrees
            val pTokenUsage = AgentTokenUsageTracker(pid, tokenUsageStore) // CYP-316/325: per-project feed, persisted
            val pBusyState = AgentBusyStateTracker() // CYP-324: this project's own busy feed (per-runtime)
            val pTerminalControl = TerminalControlStateTracker() // CYP-354 (BE-1): this project's own terminal-state feed
            val pCompactSignal = CompactCompletionSignal() // CYP-326: this project's own compaction-completed signal
            val pLifecycle = LifecycleManager(
                initialWorktrees = emptyMap(),
                sessions = pSessions,
                ensureWorktree = ensureActiveWorktree, // CYP-247 S1: lazy per-project clone + worktree off resolvedRepo(pid)
                // CYP-247 S1b: thread THIS project's pid into the spawn so key/cwd/stamp come from `pid`, not active()/boot.
                spawn = { id, worktree -> connector.open(id, worktree, pid) },
                // CYP-330: fresh (context-free) rollback for this project — clear the resume entry, open without --resume.
                spawnFresh = { id, worktree -> sessionStore?.clear(pid, id); connector.open(id, worktree, pid) },
                recorder = eventRecorder,
                projector = eventProjector,
                onContextReset = { pTokenUsage.reset(it) },   // CYP-316: this project's lifecycle → its own tracker
                onContextForget = { pTokenUsage.forget(it) },
                onBusyReset = { pBusyState.reset(it); pTerminalControl.reset(it) },   // CYP-324/354: this project's lifecycle → its own busy + terminal-state trackers
                onBusyForget = { pBusyState.forget(it); pTerminalControl.forget(it) },
            )
            val pAgentManagement = AgentManagement(
                state = state,
                lifecycle = pLifecycle,
                configs = pConfigs,
                ensureWorktree = ensureActiveWorktree, // CYP-247 S1: lazy per-project clone + worktree off resolvedRepo(pid)
                deleteWorktree = { worktreeName -> runtimeRegistry.active().worktrees.deleteWorktree(worktreeName) },
                onConnectorOptIn = connectorOptIn::apply,
                remoteToken = remoteTokenIssuer,
                overrides = agentOverrides,
                activeProjectId = { state.activeProjectId },
                avatarBlobs = avatarBlobs,
                avatarPresets = avatarPresets,
                projectAgents = projectAgents, // CYP-256 (.5a): same durable single source for this project
                worktreeDirOf = { runtimeRegistry.active().worktrees.worktreeDir(it) }, // CYP-310
                // CYP-310: a non-boot project's agents are all runtime-added → remote ones are tracked on add().
            )
            ProjectRuntime(pid, pLifecycle, pSessions, pConfigs, pCaps, pProvider, pAgentManagement, pWorktrees, pTokenUsage, pBusyState, pTerminalControl, pCompactSignal)
        }

        // CYP-255 (.4b) / CYP-247.4: the session-suspension teardown policy. suspend = stop a project's
        // RUNNING agents (kill the `claude` processes; session ids are already persisted by the connector,
        // CYP-167 — so start() re-spawns with --resume); resume = start exactly those agents again. The
        // runtime OBJECT is kept in memory either way (full reclaim = CYP-247.5). Runs on the boot scope.
        // CYP-247 S3 — drain (stop, awaited) all of a project's RUNNING agent sessions; returns the set stopped.
        // `lifecycle.stop` is removeAndAwait → `ClaudeCodeSession.closeAndAwait`, which destroys the process then
        // JOINS the reader (r4; CYP-371 — not the old cancelAndJoin), so on return NO in-flight `active()`-read
        // outlives this call (bar the 5 s flush-timeout backstop, CYP-374). Reused by BOTH the async LRU
        // eviction (a cap>1 background victim) AND the synchronous drain-before-rescope on the switch (r3).
        suspend fun drainProject(pid: String): Set<String> {
            val rt = runtimeRegistry.of(pid) ?: return emptySet()
            val running = rt.lifecycle.snapshot()
                .filter { it.runState == com.tneff.cyppieagents.model.AgentRunState.RUNNING }
                .map { it.agentId }.toSet()
            running.forEach { rt.lifecycle.stop(it) }
            return running
        }
        val suspensionPolicy = RuntimeSuspensionPolicy(
            cap = runtimeSuspensionCap,
            scope = scope,
            suspendProject = { pid -> drainProject(pid) },
            resumeProject = { pid, agents ->
                val rt = runtimeRegistry.of(pid)
                if (rt != null) agents.forEach { runCatching { rt.lifecycle.start(it) } } // start → open --resume
            },
        )

        // CYP-256 (.5a) — LAZY rehydration (CR2): repopulate the ACTIVE project's runtime + HubState slice from
        // the durable [ProjectAgentStore]. Called at boot for the boot project (its runtime-added agents from a
        // prior session) and, per switch, AFTER rescope for the just-activated project (a non-boot project's
        // slice is empty after a restart → the store refills it). LAZY, consistent with .4b — no eager
        // repopulate-every-project, no new boot-stash mechanism. Idempotent: skips an agent already in the slice
        // (config-seeded or previously rehydrated). Rehydrated agents are STOPPED; start is the CYP-73 lifecycle.
        // Bypasses AgentManagement.add so it does NOT write back to the store (no rehydrate→persist loop).
        val rehydrateActiveProject: () -> Unit = {
            val pid = state.activeProjectId
            val rt = runtimeRegistry.active()
            for (stored in projectAgents.agentsFor(pid)) {
                if (state.agent(stored.id) == null) {
                    // D3 (ratified): idempotent worktree reuse — the dir already exists from the original add.
                    // CYP-247 S1: via the shared seam so the project's clone is (lazily) present + base-branch is
                    // single-sourced from resolvedRepo(pid); idempotent, so an existing clone/worktree is a no-op.
                    ensureActiveWorktree(stored.worktree)
                    rt.agentConfigs.put(stored.id, stored.launch, stored.persona, stored.connectorKind)
                    state.addAgent(stored.toAgent()) // HubState slice + spoke + ACL (projectId-stamped)
                    rt.lifecycle.register(stored.id, stored.worktree) // known + STOPPED
                }
            }
        }

        // CYP-121/122: record each agent's capabilities (per-agent via the router) and log a content-free
        // `capability.degraded` event for every non-AVAILABLE dimension, so the fidelity gap is visible in
        // the Event-Log ("ehrlich degradiert, nie vorgetäuscht", Doc 10 §3). Connector A is all-AVAILABLE →
        // emits nothing; a Connector-B agent emits its column-B degradations.
        //
        // CYP-255 (.4b) — REORDER: this intake loop runs AFTER the boot runtime is registered (above),
        // because `connector.capabilitiesFor/trustFor/providerFor` route through the router's `kindOf`, which
        // now resolves `runtimeRegistry.active()` — that would throw if the boot runtime weren't yet live. The
        // caps/provider are still written to the boot project's registries (they ARE `active().*` for the boot
        // project here), so `GET /api/agents` reads them exactly as before — behavior-preserving.
        //
        // Idempotency (deliberate): this is a **once-per-platform-boot** emit. A CYP-73 agent restart goes
        // through LifecycleManager.respawn (not BootOrchestrator.boot), and a restart does NOT change the
        // connector or its declared capabilities, so it must NOT re-emit — re-declaring would just be log
        // noise. A connector *change* (the CYP-122 opt-in) is the event that re-declares; that is the
        // `connector.optin` audit, which the opt-in path emits explicitly.
        for (agent in config.agents) {
            // E2.4 / CYP-140: clamp the connector's SELF-DECLARED caps to the server trust ceiling for its
            // source (reducing-only) — the SINGLE application point, so every downstream consumer (gate,
            // degradation event, Agent DTO) sees the clamped, server-authoritative caps. trustOf is LOCAL
            // for every MVP-spawned agent ⇒ ceiling all-AVAILABLE ⇒ clamp is identity ⇒ local byte-unchanged
            // (E2-S2 regression guard). REMOTE caps (E2.2 wire) get the §1 ceiling and degrade, never escalate.
            // CYP-169: a remote/BYOA agent is untrusted from birth — clamp its boot caps to the REMOTE
            // ceiling even before it connects (fail-closed), so a not-yet-connected remote can never sit
            // with stale LOCAL all-AVAILABLE caps. The wire WireHello later REFINES (still REMOTE-clamped,
            // E2.4) with the bridge's honest self-declaration.
            val trust =
                if (agent.remote) com.tneff.cyppieagents.model.ConnectorTrust.REMOTE else connector.trustFor(agent.id)
            val caps = com.tneff.cyppieagents.model.CapabilityCeiling.clamp(
                connector.capabilitiesFor(agent.id),
                com.tneff.cyppieagents.model.CapabilityCeiling.ceilingFor(trust),
            )
            capabilityRegistry.set(agent.id, caps)
            providerRegistry.set(agent.id, connector.providerFor(agent.id)) // CYP-137: per-agent provider
            for (dim in CapabilityGate.degraded(caps)) {
                eventRecorder.record(
                    EventDraft(
                        agentId = agent.id,
                        projectId = config.projectId,
                        type = EventType.CAPABILITY_DEGRADED,
                        severity = Severity.WARN,
                        detail = buildJsonObject {
                            put("dimension", dim.dimension)
                            put("status", dim.status.name) // content-free: dimension + declared status
                        },
                    ),
                )
            }
        }

        val booted = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (agent in config.agents) {
            if (agent.remote) {
                // CYP-169: a remote/BYOA agent is NOT spawned locally — it joins over the wire (/ws/hub).
                // It is already in the topology/ACL (HubState.hubAndSpoke) and token registry; register it
                // as known + STOPPED so a WireHello can bind its [WireConnectorSession] (E2.5). The real
                // no-spawn flag CYP-169 needs — `launch:""` did NOT skip the spawn.
                lifecycle.register(agent.id, agent.worktreeName)
                log.info("agent '{}' is remote (no local spawn); awaiting /ws/hub connection", agent.id)
            } else if (lifecycle.bootAgent(agent.id)) {
                booted += agent.id
                log.info("agent '{}' booted in worktree '{}'", agent.id, agent.worktreeName)
            } else {
                failed += agent.id
            }
        }

        // CYP-255 (.4b): seed the boot project as the first HOT project in the suspension policy's LRU (it
        // is the active project at boot). With one project this never suspends anything; the switch feeds
        // subsequent activations. Placed after the spawn loop so the boot project is genuinely live.
        suspensionPolicy.onActivated(config.projectId) // CYP-308: config.projectId is active during the config seed
        // CYP-256 (.5a): rehydrate the boot project's runtime-added agents (from a prior session) — config.projectId
        // is active here, so this fills its slice/runtime from the store, on top of the config seed (config.projectId
        // owns config agents ∪ its own runtime-added agents).
        rehydrateActiveProject()

        // CYP-308: the durable active pointer is pure VIEW. Now that config.projectId is fully seeded (config agents
        // + its own store agents), switch the active view to the durable-active project — the SAME orchestration as a
        // runtime switch (PlatformWiring.onActiveSwitch): mint its runtime, rescope the hub (the config agents stash
        // under config.projectId → NO wandering), rehydrate ITS own store, mark it HOT. A fresh durable-active thus
        // shows 0 (its own); config.projectId keeps the config agents. Skipped when the view already is
        // config.projectId (the common no-mismatch boot). Restart-stable: every boot re-derives this deterministically.
        if (durableActive != config.projectId) {
            runtimeRegistry.getOrCreate(durableActive, projectRuntimeFactory)
            state.rescope(durableActive)
            rehydrateActiveProject()
            suspensionPolicy.onActivated(durableActive)
        }

        // S16 / CYP-89: Product-Lead reports fold READ sources (events/agents/channels/inbox) into
        // content-free, immutable snapshots — operator-gated at /api/reports.
        val reportStore = com.tneff.cyppieagents.report.ReportStore(
            generator = com.tneff.cyppieagents.report.ReportGenerator(eventSink, state, hub),
            projectId = config.projectId,
            file = reportFile, // CYP-220 S6: File-durable when supplied (prod), in-memory when null (tests)
        )

        // S13 / CYP-91: the multi-project registry (loaded early, above, for the CYP-305 effective-active seam)
        // + the cascade deleter composing the strictly-projectId-scoped teardown primitives — /api/projects.
        val projectDeleter = ProjectDeleter(projectRegistry, projectConfig, eventSink, worktrees, agentEventStore, avatarBlobs, agentOverrides, projectAgents, tokenUsageStore)

        // CYP-326 — the platform-side compact orchestrator (boot project; MVP single-project). Watches the PO's
        // context (CYP-325 feed); on a >threshold up-crossing + "compact allowed" it runs the staggered team
        // compaction. Idle-gate via CYP-324 busy; completion via the CYP-326 compact_result signal; honest X/N.
        val compactConfigStore = CompactConfigStore(compactConfigFile)
        val poId = config.agents.firstOrNull { it.role == com.tneff.cyppieagents.model.Role.PO }?.id
        val compactOrchestrator = CompactOrchestrator(
            scope = scope,
            poContext = tokenUsageTracker.events
                .filter { it.agentId == poId }
                .map { it.contextTokens },
            compactCompletions = compactSignal.events,
            isBusy = { id -> busyStateTracker.snapshot().firstOrNull { it.agentId == id }?.busy ?: false },
            send = { id, text ->
                // CYP-326 #1 (visibility): record the platform-injected message into the transcript FIRST —
                // chronologically before the reaction — marked injectedSource so the client renders it as an
                // incoming/system row (the trigger the operator must see); no CYP-323 double-echo (this path has
                // no client composer echo). Then inject on stdin.
                agentEventRecorder.record(id, config.projectId, CompactOrchestrator.injectedUserEvent(text))
                sessions.session(id)?.let { it.sendTurn(com.tneff.cyppieagents.model.UserTurn(text)); true } ?: false
            },
            emit = { ev -> eventSink.append(compactDraft(ev, poId, config.projectId)) },
            agentsInOrder = { config.agents.sortedBy { if (it.role == com.tneff.cyppieagents.model.Role.PO) 0 else 1 }.map { it.id } },
            config = { compactConfigStore.get(config.projectId) },
        ).also { it.start() }
        val compactStatus: () -> com.tneff.cyppieagents.model.CompactStatus = { compactOrchestrator.status() }
        val compactOnConfigUpdated: () -> Unit = { compactOrchestrator.onConfigUpdated() } // CYP-326 kill-switch

        // CYP-332/CYP-348 — the terminal PTY manager (boot project; MVP single-project). One pty4j PTY per
        // agent, cwd = the agent's worktree, key/env like the connector. The launch command is the CYP-348
        // seam: [terminalLaunchCommand] (default `bash -l` interim worktree-shell). Single-flight per agent
        // (§4.1). Multi-project + PTY-survives-reconnect + the BE-2 `claude --resume` per-open mode = follow-ups.
        val ptyManager = com.tneff.cyppieagents.pty.PtyManager(
            worktreeDirOf = { agentId ->
                val wtName = state.agents.firstOrNull { it.id == agentId }?.worktree ?: agentId
                runtimeRegistry.active().worktrees.worktreeDir(wtName)
            },
            resolveApiKey = { projectConfig.resolvedApiKey(state.activeProjectId) },
            scope = scope,
            command = terminalLaunchCommand ?: listOf("bash", "-l"), // CYP-348: bash interim (default); injectable for E2E/BE-2
        )

        return BootedPlatform(
            hub, state, registry, sessions, tokenRegistry, store, eventSink, booted, failed, lifecycle,
            projectConfig, durableActive, agentManagement, reportStore, projectRegistry, projectDeleter,
            channelShares, capabilityRegistry, providerRegistry, agentConfigs, eventRecorder, connectorOptIn,
            agentEventStore, agentEventRecorder, runtimeRegistry, projectRuntimeFactory, suspensionPolicy,
            rehydrateActiveProject, repoReprovision,
            drainProject = { pid -> drainProject(pid) },
            compactConfigStore = compactConfigStore,
            compactStatus = compactStatus,
            compactOnConfigUpdated = compactOnConfigUpdated,
            ptyManager = ptyManager, // CYP-332
        )
    }
}

/**
 * CYP-326 — map an orchestrator [CompactOrchestrator.CompactEvent] to a content-free [EventDraft]. The
 * `orchestration.done` detail is the serialized [CompactRunSummary] (Dev renders the X/N summary against it):
 * keys `completed`, `total`, `pendingAgentIds`, `startedTs`, `finishedTs`.
 */
private fun compactDraft(ev: CompactOrchestrator.CompactEvent, poId: String?, projectId: String): EventDraft = when (ev) {
    // CYP-327: every event carries the run's correlationId — the UI's authoritative join key for the sequence list.
    is CompactOrchestrator.CompactEvent.PrepareSent -> EventDraft(ev.agentId, projectId, EventType.COMPACT_PREPARE_SENT, Severity.INFO, correlationId = ev.correlationId)
    is CompactOrchestrator.CompactEvent.RequestSent -> EventDraft(ev.agentId, projectId, EventType.COMPACT_REQUEST_SENT, Severity.INFO, correlationId = ev.correlationId)
    is CompactOrchestrator.CompactEvent.Completed -> EventDraft(ev.agentId, projectId, EventType.COMPACT_COMPLETED, Severity.INFO, correlationId = ev.correlationId)
    is CompactOrchestrator.CompactEvent.OrchestrationDone -> EventDraft(
        agentId = poId ?: "",
        projectId = projectId,
        type = EventType.COMPACT_ORCHESTRATION_DONE,
        severity = if (ev.summary.pendingAgentIds.isEmpty()) Severity.INFO else Severity.WARN,
        correlationId = ev.correlationId,
        detail = CommJson.encodeToJsonElement(CompactRunSummary.serializer(), ev.summary).jsonObject,
    )
}
