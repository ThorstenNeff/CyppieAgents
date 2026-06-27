package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.comm.MessageStore
import com.tneff.cyppieagents.connector.ClaudeCodeConnector
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.mediation.MediationRouter
import com.tneff.cyppieagents.mediation.SessionRegistry
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.routing.TokenRegistry
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
    /** Agents whose session spawned and registered. */
    val bootedAgents: List<String>,
    /** Agents whose spawn failed — fail-closed: NO session, NO open /ws/agent for them. */
    val failedAgents: List<String>,
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
) {
    private val log = LoggerFactory.getLogger("boot.orchestrator")

    fun boot(): BootedPlatform {
        worktrees.ensureClone(config.repo)

        val agents = config.agents.map { Agent(it.id, it.name, it.role, it.worktreeName) }
        // Operator is a privileged ACL participant (member of every channel) — the human/UI viewer.
        val state = HubState.hubAndSpoke(agents, HubState.OPERATOR_ID)
        val store = storeFactory()
        val hub = Hub(state, store)
        val registry = SessionRegistry()
        val router = MediationRouter(registry, hub)
        val turnQueue = SessionTurnQueue()
        val sessions = ConnectorSessions()
        val tokenRegistry = TokenRegistry(secrets.agentTokens, secrets.operatorToken)

        val connector = ClaudeCodeConnector(
            spawner = spawner,
            worktreesRoot = worktrees.worktreesRoot,
            apiKey = secrets.apiKey,
            registry = registry,
            router = router,
            turnQueue = turnQueue,
            scope = scope,
        )

        val booted = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (agent in config.agents) {
            try {
                worktrees.ensureWorktree(agent.worktreeName, config.repo.branch)
                val session = connector.open(agent.id, agent.worktreeName)
                sessions.register(session)
                booted += agent.id
                log.info("agent '{}' booted in worktree '{}'", agent.id, agent.worktreeName)
            } catch (e: Exception) {
                // Fail-closed per agent (Reviewer #5): no session → no /ws/agent; hub stays up.
                log.error("agent '{}' failed to boot ({}); other agents unaffected", agent.id, e.message)
                failed += agent.id
            }
        }

        return BootedPlatform(hub, state, registry, sessions, tokenRegistry, store, booted, failed)
    }
}
