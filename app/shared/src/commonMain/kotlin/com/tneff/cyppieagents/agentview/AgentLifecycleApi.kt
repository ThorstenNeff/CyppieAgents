package com.tneff.cyppieagents.agentview

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Server-reported runtime lifecycle state of an agent's connector/process (CYP-73). Distinct from the
 * transcript-derived [AgentStatus] (activity) and from WS connection health — this is "is the process
 * up?" as the **server** sees it, surfaced from the public, secret-free `GET /api/agents` `status`
 * field (so the *display* is NOT operator-gated; only the *controls* are).
 *
 * The three server values [RUNNING]/[STOPPED]/[ERROR] map 1:1 to Backend's `:core` `AgentStatus`
 * (CYP-73 contract); [UNKNOWN] is a **client-only** state before the `/ws/lifecycle` snapshot arrives
 * (never claimed as a server fact). This client enum stays distinct from the colliding transcript
 * [AgentStatus] name on purpose — the real `AgentLifecycleApi` maps `:core.AgentStatus` → here.
 */
enum class AgentLifecycleState { RUNNING, STOPPED, ERROR, UNKNOWN }

/** A per-agent lifecycle-state change pushed over the non-gated lifecycle broadcast. */
data class AgentLifecycleEvent(val agentId: String, val state: AgentLifecycleState)

/**
 * Operator-only lifecycle actions over the agent's connector/process. The server enforces the
 * operator gate (403 otherwise); the UI additionally disables the controls without an operator token
 * (fail-closed, defence in depth). Built as an injectable port so the UI can be scaffolded + tested
 * against a stub before Backend's `POST /api/agents/{id}/start|stop|restart` lands (CYP-73 split).
 */
interface AgentLifecycleApi {
    suspend fun start(agentId: String)
    suspend fun stop(agentId: String)
    suspend fun restart(agentId: String)
}

/**
 * Live source of lifecycle-state changes (the non-gated broadcast) plus the initial snapshot. Kept
 * separate from [AgentLifecycleApi] because the **display** path is non-gated while the **action**
 * path is operator-gated — the two must not share a privileged egress (CYP-73 / CYP-55 leak rule).
 */
interface AgentLifecycleSource {
    /** Initial per-agent states (from the public agent list). */
    suspend fun snapshot(): Map<String, AgentLifecycleState>

    /** Live per-agent lifecycle-state changes. */
    fun events(): Flow<AgentLifecycleEvent>
}

/**
 * In-memory stub of both ports for the UI scaffold + tests (no server). Lifecycle actions flip the
 * agent's state and emit it on [events]; [restart] goes STOPPED→RUNNING as one step. Replaced by the
 * real REST/WS client once Backend reports the endpoint contract.
 */
class StubAgentLifecycle(
    initial: Map<String, AgentLifecycleState> = emptyMap(),
) : AgentLifecycleApi, AgentLifecycleSource {

    private val states = initial.toMutableMap()
    private val _events = MutableSharedFlow<AgentLifecycleEvent>(replay = 0, extraBufferCapacity = 64)

    override suspend fun snapshot(): Map<String, AgentLifecycleState> = states.toMap()

    override fun events(): Flow<AgentLifecycleEvent> = _events.asSharedFlow()

    override suspend fun start(agentId: String) = set(agentId, AgentLifecycleState.RUNNING)
    override suspend fun stop(agentId: String) = set(agentId, AgentLifecycleState.STOPPED)
    override suspend fun restart(agentId: String) = set(agentId, AgentLifecycleState.RUNNING)

    private suspend fun set(agentId: String, state: AgentLifecycleState) {
        states[agentId] = state
        _events.emit(AgentLifecycleEvent(agentId, state))
    }
}
