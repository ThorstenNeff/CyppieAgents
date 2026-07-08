package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.events.EventProjector
import com.tneff.cyppieagents.events.EventRecorder
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.AgentRunStateEvent
import com.tneff.cyppieagents.routing.ConflictException
import com.tneff.cyppieagents.routing.NotFoundException
import com.tneff.cyppieagents.routing.ServiceUnavailableException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.slf4j.LoggerFactory

/**
 * Owns the agent **process lifecycle** (CYP-73): stop / start / restart, the per-agent [AgentRunState],
 * and a content-free status broadcast for `/ws/lifecycle`. The spawn path is **single-sourced** here
 * (Spec single-source rule): [BootOrchestrator] boots through [bootAgent], and the runtime controls go
 * through [start]/[restart], so boot-spawn and respawn can't drift.
 *
 * Security/correctness invariants the Reviewer gates:
 *  - **Stop confirms the process is gone before STOPPED** — [stop] awaits termination via
 *    [ConnectorSessions.removeAndAwait] (no zombie still writing to the bus).
 *  - **Restart leaves no orphan** — it removes+awaits the old session BEFORE respawning into the SAME
 *    worktree, as one server-side op (no orphaned session/process/worktree between).
 *  - The status broadcast carries only `{agentId, status}` (no event content) — the leak-free path.
 */
class LifecycleManager(
    /** agentId → worktree sub-folder name (the SAME folder a respawn reuses; no orphan). Mutable at
        runtime via [register]/[forget] (CYP-97 agent add/remove). */
    initialWorktrees: Map<String, String>,
    private val sessions: ConnectorSessions,
    /** Idempotently ensure the agent's worktree exists before a (re)spawn (boot wires the real one). */
    private val ensureWorktree: (worktreeName: String) -> Unit,
    /** The connector's spawn (`connector.open(id, worktree)`) — injected so it's testable with a fake. */
    private val spawn: (agentId: String, worktreeName: String) -> ConnectorSession,
    private val recorder: EventRecorder? = null,
    private val projector: EventProjector? = null,
    /**
     * CYP-316 — invoked when an agent's standing context is cleared (stop / restart), so the token-usage
     * feed drops that agent back to "unknown" (null) until its next turn. Wired to
     * [AgentTokenUsageTracker.reset]; null = not wired (legacy/tests).
     */
    private val onContextReset: ((agentId: String) -> Unit)? = null,
    /** CYP-316 — invoked when an agent is removed (CYP-97), so the token feed drops it entirely (no ghost
     *  snapshot entry). Wired to [AgentTokenUsageTracker.forget]; null = not wired. */
    private val onContextForget: ((agentId: String) -> Unit)? = null,
    /** CYP-324 — invoked on stop/restart so the busy feed drops the `*` even if the session died without a
     *  final `result`. Wired to [AgentBusyStateTracker.reset]; null = not wired (legacy/tests). */
    private val onBusyReset: ((agentId: String) -> Unit)? = null,
    /** CYP-324 — invoked on remove (CYP-97), so the busy feed drops the agent entirely (no ghost snapshot).
     *  Wired to [AgentBusyStateTracker.forget]; null = not wired. */
    private val onBusyForget: ((agentId: String) -> Unit)? = null,
) {
    private val log = LoggerFactory.getLogger("lifecycle")
    private val lock = Any()
    private val worktreeOf = java.util.concurrent.ConcurrentHashMap(initialWorktrees)
    private val status = HashMap<String, AgentRunState>()
    private val _events = MutableSharedFlow<AgentRunStateEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST, // never block a control op on a slow WS client
    )

    /** Live status deltas for `/ws/lifecycle`; the socket prepends a snapshot via [snapshot]. */
    val events: Flow<AgentRunStateEvent> = _events.asSharedFlow()

    fun knows(agentId: String): Boolean = worktreeOf.containsKey(agentId)

    /** Register a newly-added agent (CYP-97) as known + STOPPED, without spawning (start is a separate op). */
    fun register(agentId: String, worktreeName: String): Unit = synchronized(lock) {
        worktreeOf[agentId] = worktreeName
        status[agentId] = AgentRunState.STOPPED
        _events.tryEmit(AgentRunStateEvent(agentId, AgentRunState.STOPPED))
    }

    /** Forget a removed agent (CYP-97) — caller has already stopped its session. */
    fun forget(agentId: String): Unit = synchronized(lock) {
        worktreeOf.remove(agentId)
        status.remove(agentId)
        onContextForget?.invoke(agentId) // CYP-316: drop the token-usage entry too (no ghost snapshot)
        onBusyForget?.invoke(agentId) // CYP-324: drop the busy entry too
    }

    fun runStateOf(agentId: String): AgentRunState? = synchronized(lock) { status[agentId] }

    /** Current status of every known agent (the WS connect snapshot + `GET /api/agents` fill). */
    fun snapshot(): List<AgentRunStateEvent> = synchronized(lock) {
        worktreeOf.keys.map { AgentRunStateEvent(it, status[it] ?: AgentRunState.STOPPED) }
    }

    /** Boot path: spawn fail-closed (a failure → ERROR, never aborts the boot). Returns true if RUNNING. */
    fun bootAgent(agentId: String): Boolean = try {
        doSpawn(agentId, restart = false)
        true
    } catch (e: Exception) {
        log.error("agent '{}' failed to boot ({})", agentId, e.message)
        setRunState(agentId, AgentRunState.ERROR)
        false
    }

    /** Stop the agent and **confirm its process is gone** before reporting STOPPED (no zombie). */
    suspend fun stop(agentId: String): AgentRunStateEvent {
        ensureKnown(agentId)
        sessions.removeAndAwait(agentId) // remove from registry + await real termination
        onContextReset?.invoke(agentId) // CYP-316: a stopped agent has no standing context → token feed → null
        onBusyReset?.invoke(agentId) // CYP-324: a stopped agent is not processing → clear the `*`
        return setRunState(agentId, AgentRunState.STOPPED)
    }

    /** Start a stopped agent in its SAME worktree. 409 if already running; 503 + ERROR on spawn failure. */
    suspend fun start(agentId: String): AgentRunStateEvent {
        ensureKnown(agentId)
        if (runStateOf(agentId) == AgentRunState.RUNNING) {
            throw ConflictException("agent '$agentId' is already running", code = "already_running")
        }
        return spawnOrError(agentId, restart = false)
    }

    /** Restart = stop→start as ONE op: await the old process's death, then respawn into the same worktree. */
    suspend fun restart(agentId: String): AgentRunStateEvent {
        ensureKnown(agentId)
        sessions.removeAndAwait(agentId) // no orphan: old session fully gone before respawn
        onContextReset?.invoke(agentId) // CYP-316: respawn = fresh context → token feed resets to null until turn 1
        onBusyReset?.invoke(agentId) // CYP-324: respawn = idle until its next turn
        return spawnOrError(agentId, restart = true)
    }

    private fun ensureKnown(agentId: String) {
        if (!knows(agentId)) throw NotFoundException("unknown agent '$agentId'", code = "agent_not_found")
    }

    private fun spawnOrError(agentId: String, restart: Boolean): AgentRunStateEvent = try {
        doSpawn(agentId, restart)
    } catch (e: Exception) {
        setRunState(agentId, AgentRunState.ERROR)
        log.error("agent '{}' failed to {} ({})", agentId, if (restart) "restart" else "start", e.message)
        throw ServiceUnavailableException("agent '$agentId' failed to spawn", code = "spawn_failed")
    }

    private fun doSpawn(agentId: String, restart: Boolean): AgentRunStateEvent {
        val worktreeName = worktreeOf.getValue(agentId)
        ensureWorktree(worktreeName) // idempotent — reuses the existing worktree
        sessions.register(spawn(agentId, worktreeName))
        if (recorder != null && projector != null) {
            recorder.record(
                if (restart) projector.agentRestarted(agentId) else projector.agentSpawned(agentId, worktreeName),
            )
        }
        return setRunState(agentId, AgentRunState.RUNNING)
    }

    private fun setRunState(agentId: String, next: AgentRunState): AgentRunStateEvent {
        synchronized(lock) { status[agentId] = next }
        val event = AgentRunStateEvent(agentId, next)
        _events.tryEmit(event)
        return event
    }
}
