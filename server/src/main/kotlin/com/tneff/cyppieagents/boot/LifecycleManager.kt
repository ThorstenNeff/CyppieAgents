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
    /**
     * CYP-330 — a FRESH (context-free) respawn seam: clears the durable resume entry, then spawns WITHOUT
     * `--resume`. Used as the start/restart **rollback** so a respawn that throws (e.g. a wedged resume path)
     * can't leave the agent dead in ERROR — it retries fresh ONCE and reaches RUNNING. Null (legacy/tests /
     * no resume store) → no fallback, a spawn failure stays ERROR as before. Complements the connector-side
     * proactive stale-resume heal ([com.tneff.cyppieagents.connector.ResumingSession]).
     */
    private val spawnFresh: ((agentId: String, worktreeName: String) -> ConnectorSession)? = null,
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
    /**
     * CYP-355 — invoked on stop/restart so a lifecycle op that lands while the agent is **INTERACTIVE** also
     * tears down its interactive PTY (else a stop would leave an orphaned `claude --resume` alive — the very
     * two-process violation the hand-off exists to prevent). Wired to `PtyManager.close`; runs under the SAME
     * shared [transitions] lock as the hand-off motor, and `close` is non-blocking (destroy-only, it never
     * takes the lock), so it respects [AgentTransitionLock]'s deadlock rule. Null = not wired (legacy/tests).
     */
    private val onTeardown: ((agentId: String) -> Unit)? = null,
    /**
     * CYP-368 — the per-agent transition lock, **shared** with every other component that participates in an
     * agent's transition (BE-2: `PtyManager`). Owned by neither: see [AgentTransitionLock], which also carries
     * the deadlock rule this class depends on (the reader's exit tail must never take it).
     *
     * Defaulted so existing construction sites and tests are unchanged; the wiring passes one instance per
     * runtime, because an `agentId` is only unique within a project.
     */
    private val transitions: AgentTransitionLock = AgentTransitionLock(),
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

    /** CYP-355 — the agent's worktree sub-folder, so the hand-off motor respawns the mediated session into the
     *  SAME worktree this manager uses (one source, no drift). Null == unknown agent. */
    fun worktreeNameOf(agentId: String): String? = worktreeOf[agentId]

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

    /**
     * Boot path: spawn fail-closed (a failure → ERROR, never aborts the boot). Returns true if RUNNING.
     *
     * CYP-368: takes the same per-agent mutex as the runtime controls, via `runBlocking`. Boot runs on the
     * startup thread before Ktor serves a request, so there is nothing to contend with and nothing to block —
     * but the lock must cover **every** writer, or the rule has an exception a reader has to remember.
     * `bootAgent` stays non-suspend so `BootOrchestrator.boot()` keeps its signature.
     */
    fun bootAgent(agentId: String): Boolean = transitions.withAgentBlocking(agentId) {
        try {
            doSpawn(agentId, restart = false)
            true
        } catch (e: Exception) {
            log.error("agent '{}' failed to boot ({})", agentId, e.message)
            setRunState(agentId, AgentRunState.ERROR)
            false
        }
    }

    /** Stop the agent and **confirm its process is gone** before reporting STOPPED (no zombie). */
    suspend fun stop(agentId: String): AgentRunStateEvent = transitions.withAgent(agentId) {
        ensureKnown(agentId)
        sessions.removeAndAwait(agentId) // remove from registry + await real termination
        onTeardown?.invoke(agentId) // CYP-355: also tear down an interactive PTY if the agent was INTERACTIVE
        onContextReset?.invoke(agentId) // CYP-316: a stopped agent has no standing context → token feed → null
        onBusyReset?.invoke(agentId) // CYP-324: a stopped agent is not processing → clear the `*` (+ mode→MEDIATED)
        setRunState(agentId, AgentRunState.STOPPED)
    }

    /** Start a stopped agent in its SAME worktree. 409 if already running; 503 + ERROR on spawn failure. */
    suspend fun start(agentId: String): AgentRunStateEvent = transitions.withAgent(agentId) {
        ensureKnown(agentId)
        // CYP-368: check-then-act, now atomic per agent. The loser of two concurrent starts enters here AFTER
        // the winner published RUNNING, so it fails on this existing guard — before it can spawn. The 409 is not
        // new behaviour bolted on; it falls out of the serialisation.
        if (runStateOf(agentId) == AgentRunState.RUNNING) {
            throw ConflictException("agent '$agentId' is already running", code = "already_running")
        }
        // A crashed agent leaves its dead session in the registry (nothing removes it). Clear it before the
        // respawn, so doSpawn never has to displace one — displacement is how a live process becomes an orphan.
        sessions.removeAndAwait(agentId)
        spawnOrError(agentId, restart = false)
    }

    /** Restart = stop→start as ONE op: await the old process's death, then respawn into the same worktree. */
    suspend fun restart(agentId: String): AgentRunStateEvent = transitions.withAgent(agentId) {
        ensureKnown(agentId)
        sessions.removeAndAwait(agentId) // no orphan: old session fully gone before respawn
        onTeardown?.invoke(agentId) // CYP-355: an INTERACTIVE agent restarting must also lose its PTY (no orphan)
        onContextReset?.invoke(agentId) // CYP-316: respawn = fresh context → token feed resets to null until turn 1
        onBusyReset?.invoke(agentId) // CYP-324: respawn = idle until its next turn
        spawnOrError(agentId, restart = true)
    }

    private fun ensureKnown(agentId: String) {
        if (!knows(agentId)) throw NotFoundException("unknown agent '$agentId'", code = "agent_not_found")
    }

    private fun spawnOrError(agentId: String, restart: Boolean): AgentRunStateEvent {
        try {
            return doSpawn(agentId, restart)
        } catch (e: Exception) {
            // CYP-330 rollback: a failed respawn must not leave the agent dead in ERROR. If a fresh
            // (context-free) spawn seam is wired, retry ONCE without `--resume` so it still reaches RUNNING.
            val fresh = spawnFresh
            if (fresh != null) {
                try {
                    log.warn(
                        "agent '{}' {} respawn failed ({}); retrying FRESH (context-free rollback)",
                        agentId, if (restart) "restart" else "start", e.message,
                    )
                    return doSpawn(agentId, restart, spawnFn = fresh)
                } catch (e2: Exception) {
                    setRunState(agentId, AgentRunState.ERROR)
                    log.error("agent '{}' fresh fallback also failed ({})", agentId, e2.message)
                    throw ServiceUnavailableException("agent '$agentId' failed to spawn", code = "spawn_failed")
                }
            }
            setRunState(agentId, AgentRunState.ERROR)
            log.error("agent '{}' failed to {} ({})", agentId, if (restart) "restart" else "start", e.message)
            throw ServiceUnavailableException("agent '$agentId' failed to spawn", code = "spawn_failed")
        }
    }

    private fun doSpawn(
        agentId: String,
        restart: Boolean,
        spawnFn: (agentId: String, worktreeName: String) -> ConnectorSession = spawn,
    ): AgentRunStateEvent {
        val worktreeName = worktreeOf.getValue(agentId)
        ensureWorktree(worktreeName) // idempotent — reuses the existing worktree
        val session = spawnFn(agentId, worktreeName)
        // CYP-368 tripwire, fail-loud. `ConnectorSessions.register` displaces silently, and a displaced local
        // session keeps running: an unregistered `claude`, unreaped, still burning tokens. With the per-agent
        // mutex this can never happen — so if it ever does, we learn it here instead of in a token bill. (The
        // check lives here, not in `register()`, because the wire path displaces ON PURPOSE on reconnect,
        // CYP-141/RC3.)
        check(sessions.session(agentId) == null) {
            "agent '$agentId' already has a live session at spawn time — the per-agent lock was bypassed"
        }
        sessions.register(session)
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
