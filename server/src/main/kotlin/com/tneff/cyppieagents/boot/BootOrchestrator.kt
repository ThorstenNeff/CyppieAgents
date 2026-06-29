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
import kotlinx.serialization.json.buildJsonObject
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
    /** Mutable per-agent connector config incl. connectorKind (CYP-97/122): the connector-opt-in source. */
    val agentConfigs: AgentConfigRegistry,
    /** The Event-Log write tap (CYP-35): operator actions (e.g. the CYP-122 `connector.optin`) audit through it. */
    val eventRecorder: EventRecorder,
    /** Connector opt-in (CYP-122): the operator-gated, audited set-connector action served by `/api/agents/{id}/connector`. */
    val connectorOptIn: ConnectorOptIn,
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
    // Default in-memory; CYP-43 swaps in a SqliteEventSink from the events config (sinkPath/WAL).
    private val eventSinkFactory: () -> EventSink = { InMemoryEventSink(SystemTimeSource()) },
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
    // CYP-120: the connector-injection seam. Default (null) builds the real Connector A
    // (stream-json [ClaudeCodeConnector]) — production boot is unchanged. A test/CYP-121 harness can
    // inject a `FakeConnector(caps)` with reduced tri-state capabilities to prove Mediator gating +
    // degradation events, and CYP-122 selects A-vs-B per agent here. The factory receives the
    // already-built [Connector] so a decorator can wrap it; ignore the arg to fully replace it.
    private val connectorFactory: ((default: Connector) -> Connector)? = null,
) {
    private val log = LoggerFactory.getLogger("boot.orchestrator")

    fun boot(): BootedPlatform {
        // S15 / CYP-96: operator overrides for repo + API key, per project, fall back to boot config.
        val projectConfig = ProjectConfigStore(projectConfigFile, config.repo, secrets)
        // Repo change takes effect at the next boot (design §3.2): clone the resolved (override→boot) repo.
        worktrees.ensureClone(projectConfig.resolvedRepo(config.projectId))

        val agents = config.agents.map { Agent(it.id, it.name, it.role, it.worktreeName) }
        // S17 / CYP-93: the cross-project share gate. The hub consults it for the AclMatrix permit
        // (channels authorized to reach into the active project); revoke → immediate fail-closed.
        val channelShares = com.tneff.cyppieagents.comm.ChannelShareStore(channelShareFile)
        // Operator is a privileged ACL participant (member of every channel) — the human/UI viewer.
        // S12 / CYP-81: single-source the active project from config into the hub (scopes channels/ACL/messages).
        val state = HubState.hubAndSpoke(agents, HubState.OPERATOR_ID, config.projectId) { pid ->
            channelShares.sharedInboundChannelIds(pid)
        }
        val store = storeFactory()
        val hub = Hub(state, store)
        val registry = SessionRegistry()
        val turnQueue = SessionTurnQueue()
        val sessions = ConnectorSessions()
        val tokenRegistry = TokenRegistry(secrets.agentTokens, secrets.operatorToken)

        // Observability ingestion (CYP-37): one EventRecorder feeds the shared sink; the projector
        // turns masked stream events into content-free drafts at the connector tap and in the router.
        // All knobs come from the events config (CYP-43).
        val ev = config.events
        val eventSink = eventSinkFactory()
        val eventRecorder = EventRecorder(eventSink, scope, capacity = ev.queueCapacity, batchSize = ev.batchSize)
            .also { it.start() }
        val bander = ContextUsageBander(
            contextWindowTokens = ev.contextWindowTokens,
            bandPctWidth = ev.bandPct,
            compactPct = ev.compactPct,
        )
        // CYP-121: per-agent connector capabilities, populated below once the connector is resolved.
        // Built empty here so the projector + stall detector can hold a live `::get` resolver; reads
        // happen at event-time (after boot populates it). Connector A = all-AVAILABLE → no gating.
        val capabilityRegistry = CapabilityRegistry()
        // S12 / CYP-83: events carry the active project, single-sourced from config (not a constant).
        // CYP-121: the projector gates tool.* (toolGranularity) and context.usage (structuredUsage).
        val eventProjector = EventProjector(bander, projectId = config.projectId, capabilities = capabilityRegistry::get)

        val router = MediationRouter(registry, hub, eventRecorder, eventProjector)

        // S14 / CYP-97: the mutable per-agent connector config (launch + persona), seeded from config.
        // The connector reads personaOf at open() to place CLAUDE.md; AgentManagement mutates it.
        // CYP-133: when an agent carries no explicit persona, seed the role default (PO = coordinator /
        // decompose+delegate, WORKER = work+report) so a spawned agent is never persona-less — the RB1
        // gap where the PO had no coordinator persona and did the task itself. Single-sourced in [Personas].
        val agentConfigs = AgentConfigRegistry(
            config.agents.map { it.copy(claudeMd = it.claudeMd?.ifBlank { null } ?: Personas.forRole(it.role)) },
        )

        // CYP-120: build the real Connector A by default, then pass it through the injection seam.
        // Prod leaves connectorFactory null → the stream-json connector is used verbatim.
        val defaultConnector = ClaudeCodeConnector(
            spawner = spawner,
            worktreesRoot = worktrees.worktreesRoot,
            // S15 / CYP-96: resolve the key AT SPAWN per project — operator override (store) → env
            // fallback (Secrets, CYP-82). Lazy so a CYP-73 restart picks up an operator key change.
            resolveApiKey = { projectConfig.resolvedApiKey(config.projectId) },
            registry = registry,
            router = router,
            turnQueue = turnQueue,
            scope = scope,
            recorder = eventRecorder,
            projector = eventProjector,
            personaOf = agentConfigs::personaOf,
        )
        // CYP-122: Connector B (MCP) + per-agent selection. The router picks A vs B by the agent's
        // declared connectorKind at spawn; it IS a Connector so the connectorFactory seam still wraps it.
        val mcpConnector = McpConnector(hub)
        val connectorRouter = ConnectorRouter(
            streamJson = defaultConnector,
            mcp = mcpConnector,
            kindOf = agentConfigs::connectorKindOf,
        )
        val connector: Connector = connectorFactory?.invoke(connectorRouter) ?: connectorRouter

        // CYP-121/122: record each agent's capabilities (per-agent via the router) and log a content-free
        // `capability.degraded` event for every non-AVAILABLE dimension, so the fidelity gap is visible in
        // the Event-Log ("ehrlich degradiert, nie vorgetäuscht", Doc 10 §3). Connector A is all-AVAILABLE →
        // emits nothing; a Connector-B agent emits its column-B degradations.
        //
        // Idempotency (deliberate): this is a **once-per-platform-boot** emit. A CYP-73 agent restart goes
        // through LifecycleManager.respawn (not BootOrchestrator.boot), and a restart does NOT change the
        // connector or its declared capabilities, so it must NOT re-emit — re-declaring would just be log
        // noise. A connector *change* (the CYP-122 opt-in) is the event that re-declares; that is the
        // `connector.optin` audit, which the opt-in path emits explicitly.
        for (agent in config.agents) {
            val caps = connector.capabilitiesFor(agent.id)
            capabilityRegistry.set(agent.id, caps)
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
        val stallDetector = StallDetector(capabilities = capabilityRegistry::get)
        Scanner(eventSink, scannerDetectors + stallDetector, signalSink, scope).start()
        StallSweeper(stallDetector, signalSink, scope, clock = System::currentTimeMillis).start()

        // Mediator-Aufsicht Decide+Act stage (07/S11, CYP-62): the Warden is a separate bus consumer
        // that listens for signal Events and routes each to the Policy that handles it. Policies act
        // ONLY through the Actuator — the single write authority toward an agent (nudge = a user-turn
        // on the agent's session = the Mediator stdin).
        val actuator = MediatorActuator(sessions, signalSink, config.projectId)
        // CYP-63 stall policy: opens one incident/agent on stall.suspected, nudges with growing backoff,
        // escalates after N, recovers on activity. The runner feeds it activity + the backoff clock.
        val stallPolicy = StallPolicy(clock = System::currentTimeMillis, actuator = actuator, signals = signalSink)
        Warden(eventSink, wardenPolicies + stallPolicy, actuator, scope).start()
        StallPolicyRunner(stallPolicy, eventSink, scope).start()

        // Agent lifecycle (CYP-73): the spawn path is single-sourced here so boot-spawn and the runtime
        // start/restart controls can't drift. Boot fail-closed per agent (Reviewer #5): a failed spawn
        // → ERROR, no session, no /ws/agent; the hub and other agents are unaffected.
        val lifecycle = LifecycleManager(
            initialWorktrees = config.agents.associate { it.id to it.worktreeName },
            sessions = sessions,
            ensureWorktree = { worktreeName -> worktrees.ensureWorktree(worktreeName, config.repo.branch) },
            spawn = { id, worktree -> connector.open(id, worktree) },
            recorder = eventRecorder,
            projector = eventProjector,
        )

        // CYP-122: the single, audited, server-enforced point that sets an agent's connector (opt-in).
        val connectorOptIn = ConnectorOptIn(agentConfigs, capabilityRegistry, eventRecorder, config.projectId)

        // S14 / CYP-97: runtime agent CRUD over the (now mutable) HubState topology + lifecycle + config.
        val agentManagement = AgentManagement(
            state = state,
            lifecycle = lifecycle,
            configs = agentConfigs,
            ensureWorktree = { worktreeName -> worktrees.ensureWorktree(worktreeName, config.repo.branch) },
            deleteWorktree = { worktreeName -> worktrees.deleteWorktree(worktreeName) },
            onConnectorOptIn = connectorOptIn::apply, // CYP-122: create-as-B audits like the dedicated opt-in
        )

        val booted = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (agent in config.agents) {
            if (lifecycle.bootAgent(agent.id)) {
                booted += agent.id
                log.info("agent '{}' booted in worktree '{}'", agent.id, agent.worktreeName)
            } else {
                failed += agent.id
            }
        }

        // S16 / CYP-89: Product-Lead reports fold READ sources (events/agents/channels/inbox) into
        // content-free, immutable snapshots — operator-gated at /api/reports.
        val reportStore = com.tneff.cyppieagents.report.ReportStore(
            generator = com.tneff.cyppieagents.report.ReportGenerator(eventSink, state, hub),
            projectId = config.projectId,
        )

        // S13 / CYP-91: the multi-project registry (seeded with the boot project) + the cascade deleter
        // composing the strictly-projectId-scoped teardown primitives — operator-gated at /api/projects.
        val projectRegistry = ProjectRegistry(projectRegistryFile, config.projectId)
        val projectDeleter = ProjectDeleter(projectRegistry, projectConfig, eventSink, worktrees)

        return BootedPlatform(
            hub, state, registry, sessions, tokenRegistry, store, eventSink, booted, failed, lifecycle,
            projectConfig, config.projectId, agentManagement, reportStore, projectRegistry, projectDeleter,
            channelShares, capabilityRegistry, agentConfigs, eventRecorder, connectorOptIn,
        )
    }
}
