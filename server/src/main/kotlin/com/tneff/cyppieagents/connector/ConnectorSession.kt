package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap

/**
 * The connector seam (Decision D8): one live agent session, independent of *how* it is produced.
 * The MVP implementation is a Claude-Code process over piped stdio (CYP-5/CYP-13 live wiring);
 * MCP / OpenAI / remote connectors can implement the same interface later without changes here.
 *
 * Contract:
 *  - [events] are **already masked** `StreamJsonEvent`s — the implementation applies the secret
 *    masker before emitting (Reviewer Gate #3: masking happens before any egress, incl. this WS).
 *  - [sendTurn] injects a human/PO turn; the implementation serializes turns per session
 *    (single-flight turn-queue, Gate #5) so an injection can't race a running turn.
 */
interface ConnectorSession {
    val agentId: String
    val events: Flow<StreamJsonEvent>
    suspend fun sendTurn(turn: UserTurn)
    fun close()

    /**
     * Like [close], but **suspends until the underlying process has terminated** (CYP-73): a Stop must
     * confirm the agent is gone before reporting STOPPED, so a dying process can't keep writing to the
     * bus (no zombie). Default delegates to [close] for sessions with no real process (stubs/fakes).
     */
    suspend fun closeAndAwait() = close()
}

/**
 * Opens (spawns/attaches) a session for an agent. Live impl lands with the CYP-13 wiring.
 *
 * Every connector MUST declare its [capabilities] (Doc 10 §3) — a **mandatory part of the contract**.
 * The Mediator queries them to gate fidelity-dependent functions per agent: a function runs only when
 * its dimension is AVAILABLE, degraded (marked) on LIMITED, off (logged) on UNAVAILABLE. This is a
 * compile-time obligation: a new connector cannot exist without declaring, honestly, what it can feed.
 */
interface Connector {
    val capabilities: Capabilities
    fun open(agentId: String): ConnectorSession

    /**
     * Open a session whose worktree cwd may differ from the agent id (Spec §11 isolation). The default
     * ignores [worktreeName] — connectors with no worktree concept (a [Capabilities]-only test double,
     * an MCP connector) need only implement [open]. The live stream-json connector overrides it.
     */
    fun open(agentId: String, worktreeName: String): ConnectorSession = open(agentId)
}

/** Registry of currently-live sessions, looked up by the `/ws/agent` route by agentId. */
class ConnectorSessions {
    private val byAgent = ConcurrentHashMap<String, ConnectorSession>()

    fun register(session: ConnectorSession) {
        byAgent[session.agentId] = session
    }

    fun session(agentId: String): ConnectorSession? = byAgent[agentId]

    fun remove(agentId: String) {
        byAgent.remove(agentId)?.close()
    }

    /**
     * Remove the session and **wait for its process to terminate** (CYP-73 Stop). Returns true if a
     * session was present. The registry entry is removed FIRST (atomically), so no `/ws/agent` reconnect
     * or restart can re-find a half-dead session while we await its exit.
     */
    suspend fun removeAndAwait(agentId: String): Boolean {
        val session = byAgent.remove(agentId) ?: return false
        session.closeAndAwait()
        return true
    }

    fun agentIds(): Set<String> = byAgent.keys.toSet()
}
