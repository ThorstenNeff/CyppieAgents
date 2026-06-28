package com.tneff.cyppieagents.connector

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

/** Opens (spawns/attaches) a session for an agent. Live impl lands with the CYP-13 wiring. */
interface Connector {
    fun open(agentId: String): ConnectorSession
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
