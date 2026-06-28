package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.comm.MessageStore
import com.tneff.cyppieagents.connector.ClaudeCodeConnector
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.events.ContextUsageBander
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
) {
    private val log = LoggerFactory.getLogger("boot.orchestrator")

    fun boot(): BootedPlatform {
        worktrees.ensureClone(config.repo)

        val agents = config.agents.map { Agent(it.id, it.name, it.role, it.worktreeName) }
        // Operator is a privileged ACL participant (member of every channel) — the human/UI viewer.
        // S12 / CYP-81: single-source the active project from config into the hub (scopes channels/ACL/messages).
        val state = HubState.hubAndSpoke(agents, HubState.OPERATOR_ID, config.projectId)
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
        val eventProjector = EventProjector(bander, teamId = MVP_TEAM_ID)

        val router = MediationRouter(registry, hub, eventRecorder, eventProjector)

        val connector = ClaudeCodeConnector(
            spawner = spawner,
            worktreesRoot = worktrees.worktreesRoot,
            // S12 / CYP-82: resolve the API key PER PROJECT (single-sourced config.projectId), not a
            // global server constant — the seam CYP-96 backs with an operator-settable per-project key.
            apiKey = secrets.apiKeyFor(config.projectId),
            registry = registry,
            router = router,
            turnQueue = turnQueue,
            scope = scope,
            recorder = eventRecorder,
            projector = eventProjector,
        )

        // Hook spool tailing (CYP-38 reader + CYP-37 tailer, at-most-once). Started only when a path
        // is configured; bootPlatform supplies it, CYP-43 makes it a config knob.
        spoolPath?.let { SpoolTailer(SpoolReader(it), eventRecorder, scope).start() }

        // Mediator-Aufsicht Sense stage (07/S11). The Scanner consumes the same event bus via
        // `subscribe` and emits Signals back into it. Read-only on the stream + signal-emit only — it
        // gets no connector/session handle, so it cannot act on an agent (CYP-60).
        val signalSink = EventLogSignalSink(eventRecorder)
        // CYP-61 stall detector: rate-limit throttle ∧ silence>T → stall.suspected. onEvent senses
        // (arm/disarm) via the Scanner's fan-out; the timed decision is driven by the StallSweeper.
        val stallDetector = StallDetector()
        Scanner(eventSink, scannerDetectors + stallDetector, signalSink, scope).start()
        StallSweeper(stallDetector, signalSink, scope, clock = System::currentTimeMillis).start()

        // Mediator-Aufsicht Decide+Act stage (07/S11, CYP-62): the Warden is a separate bus consumer
        // that listens for signal Events and routes each to the Policy that handles it. Policies act
        // ONLY through the Actuator — the single write authority toward an agent (nudge = a user-turn
        // on the agent's session = the Mediator stdin).
        val actuator = MediatorActuator(sessions, signalSink, MVP_TEAM_ID)
        // CYP-63 stall policy: opens one incident/agent on stall.suspected, nudges with growing backoff,
        // escalates after N, recovers on activity. The runner feeds it activity + the backoff clock.
        val stallPolicy = StallPolicy(clock = System::currentTimeMillis, actuator = actuator, signals = signalSink)
        Warden(eventSink, wardenPolicies + stallPolicy, actuator, scope).start()
        StallPolicyRunner(stallPolicy, eventSink, scope).start()

        // Agent lifecycle (CYP-73): the spawn path is single-sourced here so boot-spawn and the runtime
        // start/restart controls can't drift. Boot fail-closed per agent (Reviewer #5): a failed spawn
        // → ERROR, no session, no /ws/agent; the hub and other agents are unaffected.
        val lifecycle = LifecycleManager(
            worktreeOf = config.agents.associate { it.id to it.worktreeName },
            sessions = sessions,
            ensureWorktree = { worktreeName -> worktrees.ensureWorktree(worktreeName, config.repo.branch) },
            spawn = { id, worktree -> connector.open(id, worktree) },
            recorder = eventRecorder,
            projector = eventProjector,
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

        return BootedPlatform(hub, state, registry, sessions, tokenRegistry, store, eventSink, booted, failed, lifecycle)
    }

    private companion object {
        // MVP is single-team (project token, 05 §3); CYP-43 can wire a real team id from config.
        const val MVP_TEAM_ID = "default"
    }
}
