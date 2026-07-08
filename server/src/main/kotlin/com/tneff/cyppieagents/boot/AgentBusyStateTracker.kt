package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.model.AgentBusyStateEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * CYP-324 — holds the **current** per-agent busy/idle state and broadcasts it to `/ws/busy-state`. The
 * runtime twin of [AgentTokenUsageTracker] (and [LifecycleManager]) for the busy feed: one instance per
 * [ProjectRuntime], selected by the active pointer.
 *
 * **Latest-wins, not an append log.** Each agent has exactly one current value ([AgentBusyStateEvent]); a
 * new turn-start/turn-end *replaces* it. So the connect [snapshot] plus the live [events] deltas are
 * **idempotent** — a reconnecting client upserts by `agentId`, so a reconnect mid-turn correctly re-sees
 * `busy = true` and can neither duplicate nor lose a state.
 *
 * The value is driven from REAL session state (the [EventProjector.onBusy] hook: `turn.start` → busy,
 * `result`/process-exit → idle), so it is never a heuristic. De-duplicates unchanged values so a run of
 * same-state signals doesn't spam the socket.
 */
class AgentBusyStateTracker {

    private val _events = MutableSharedFlow<AgentBusyStateEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST, // never block a turn on a slow WS client
    )

    /** Live deltas for `/ws/busy-state`; the socket prepends the [snapshot]. */
    val events: Flow<AgentBusyStateEvent> = _events.asSharedFlow()

    // The current state per agent. ConcurrentHashMap forbids null, so the (non-null) event object is the value.
    private val current = ConcurrentHashMap<String, AgentBusyStateEvent>()

    /**
     * Record whether [agentId] is currently processing a turn. Emits ONLY on a change (latest-wins de-dup),
     * so a repeated same state does not spam the socket.
     */
    fun set(agentId: String, busy: Boolean) {
        val next = AgentBusyStateEvent(agentId, busy)
        val prev = current.put(agentId, next)
        if (prev != next) _events.tryEmit(next)
    }

    /**
     * Force [agentId] back to idle: a lifecycle stop/restart ends any in-flight turn, so the title bar must
     * drop the `*` even if the session died without a final `result`. Emits on change.
     */
    fun reset(agentId: String) = set(agentId, false)

    /** Drop an agent entirely (CYP-97 remove) — no further snapshot/delta for it. */
    fun forget(agentId: String) {
        current.remove(agentId)
    }

    /** The current state of every known agent — the WS connect snapshot (one per agent, latest-wins). */
    fun snapshot(): List<AgentBusyStateEvent> = current.values.toList()
}
