package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.model.AgentTerminalControlEvent
import com.tneff.cyppieagents.model.TerminalControlState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * CYP-354 (BE-1) — holds the **current** per-agent [TerminalControlState] and broadcasts it to
 * `/ws/terminal-state`. The runtime twin of [AgentBusyStateTracker] / [AgentTokenUsageTracker]: one
 * instance per [ProjectRuntime], selected by the active pointer.
 *
 * **Latest-wins, not an append log.** Each agent has exactly one current value ([AgentTerminalControlEvent]);
 * a transition *replaces* it. So the connect [snapshot] plus the live [events] deltas are **idempotent** — a
 * reconnecting client upserts by `agentId` and re-sees the current mode (e.g. INTERACTIVE + heldBy/since),
 * neither duplicating nor losing.
 *
 * **The default is MEDIATED** — an agent with no entry is mediated (absent == MEDIATED, exactly as an absent
 * CYP-324 busy entry == idle). BE-2/CYP-355 drives the hand-off transitions ([set]); BE-3/CYP-356 supplies
 * `CONTEXT_LOST`. This tracker is the single source of truth + the push; it invents no transitions itself.
 * **Content-free by construction** (state/identity/time only, never keystrokes). De-duplicates unchanged
 * values so a repeat doesn't spam the socket.
 */
class TerminalControlStateTracker {

    private val _events = MutableSharedFlow<AgentTerminalControlEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST, // never block a hand-off on a slow WS client
    )

    /** Live deltas for `/ws/terminal-state`; the socket prepends the [snapshot]. */
    val events: Flow<AgentTerminalControlEvent> = _events.asSharedFlow()

    // The current mode per agent. ConcurrentHashMap forbids null, so the (non-null) event object is the value.
    private val current = ConcurrentHashMap<String, AgentTerminalControlEvent>()

    /**
     * Record [agentId]'s current mode (BE-2/BE-3 drive the transitions). Emits ONLY on a change (latest-wins
     * de-dup on the whole event, incl. [heldBy]/[since]), so a repeated same value does not spam the socket.
     */
    fun set(agentId: String, state: TerminalControlState, heldBy: String? = null, since: Long? = null) {
        val next = AgentTerminalControlEvent(agentId, state, heldBy, since)
        val prev = current.put(agentId, next)
        if (prev != next) _events.tryEmit(next)
    }

    /**
     * Force [agentId] back to MEDIATED: a lifecycle stop/restart ends any in-flight hand-off, so the mode
     * must return to mediated (and drop any holder) even if the interactive session died. Emits on change.
     */
    fun reset(agentId: String) = set(agentId, TerminalControlState.MEDIATED)

    /** Drop an agent entirely (CYP-97 remove) — no further snapshot/delta for it. */
    fun forget(agentId: String) {
        current.remove(agentId)
    }

    /** The current mode of every known agent — the WS connect snapshot (one per agent, latest-wins). */
    fun snapshot(): List<AgentTerminalControlEvent> = current.values.toList()
}
