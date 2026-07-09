package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.model.AgentTokenUsageEvent
import com.tneff.cyppieagents.model.DEFAULT_PROJECT_ID
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * CYP-316 — holds the **current** per-agent context-window occupancy (token count) and broadcasts it to
 * `/ws/token-usage`. The runtime twin of [LifecycleManager] for the token feed: one instance per
 * [ProjectRuntime], selected by the active pointer.
 *
 * **Latest-wins, not an append log.** Each agent has exactly one current value ([AgentTokenUsageEvent]);
 * a new turn-result *replaces* it. So the connect [snapshot] plus the live [events] deltas are
 * **idempotent** — a reconnecting client upserts by `agentId` and can neither duplicate nor lose a value.
 *
 * **Fidelity-honest.** `contextTokens == null` is a real state (Connector-B / no `structuredUsage`, or a
 * freshly (re)started agent before its first turn), never a faked 0. The caller ([EventProjector] via its
 * `onContextTokens` hook) decides null-vs-number by the `structuredUsage` gate — this holder just stores
 * what it is told, de-duplicating unchanged values so a Connector-B agent doesn't re-emit `null` every turn.
 */
class AgentTokenUsageTracker(
    /** This tracker's project (the [store] key). One tracker per [ProjectRuntime]. */
    private val projectId: String = DEFAULT_PROJECT_ID,
    /**
     * CYP-325 (defect 2) — durable per-agent last-context-token overlay. `null` = no persistence (the bare
     * in-memory tracker, legacy/tests). When set: rehydrated into [current] on construction (so
     * snapshot-on-connect survives a server restart) and written on every trustworthy change.
     */
    private val store: TokenUsageStore? = null,
) {

    private val _events = MutableSharedFlow<AgentTokenUsageEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST, // never block a turn on a slow WS client
    )

    /** Live deltas for `/ws/token-usage`; the socket prepends the [snapshot]. */
    val events: Flow<AgentTokenUsageEvent> = _events.asSharedFlow()

    // The current value per agent. ConcurrentHashMap forbids null values, so an "unknown" (null token)
    // agent is stored as an event whose contextTokens is null — the event object is the (non-null) value.
    private val current = ConcurrentHashMap<String, AgentTokenUsageEvent>()

    init {
        // CYP-325 (defect 2): rehydrate persisted values so a restart/refresh keeps the number until the next
        // turn refreshes it. Only stored (known) values exist in the store — no null is ever rehydrated.
        store?.allFor(projectId)?.forEach { (agentId, tokens) -> current[agentId] = AgentTokenUsageEvent(agentId, tokens) }
    }

    /**
     * Record the newest turn's context size for [agentId] ([contextTokens] = null when the connector has
     * no trustworthy count). Emits ONLY on a change (latest-wins de-dup), so an unchanged value — e.g. a
     * Connector-B agent's repeated `null` — does not spam the socket.
     */
    fun onResult(agentId: String, contextTokens: Int?) {
        val next = AgentTokenUsageEvent(agentId, contextTokens)
        val prev = current.put(agentId, next)
        if (prev != next) {
            store?.put(projectId, agentId, contextTokens) // CYP-325: persist known; null (unknown/reset) → remove
            _events.tryEmit(next)
        }
    }

    /**
     * Reset an agent to "unknown" (contextTokens = null): a lifecycle restart/stop clears the standing
     * context, so the title bar must drop back to no-number until the next turn. Emits on change.
     */
    fun reset(agentId: String) = onResult(agentId, null)

    /** Drop an agent entirely (CYP-97 remove) — no further snapshot/delta for it, and drop its persisted value. */
    fun forget(agentId: String) {
        current.remove(agentId)
        store?.removeAgent(projectId, agentId) // CYP-325: no ghost persisted value after removal
    }

    /** The current value of every known agent — the WS connect snapshot (one per agent, latest-wins). */
    fun snapshot(): List<AgentTokenUsageEvent> = current.values.toList()
}
