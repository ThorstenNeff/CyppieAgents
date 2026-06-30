package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.flow.Flow

/**
 * The connector seam (Decision D8): one live agent session, independent of *how* it is produced.
 * The MVP implementation is a Claude-Code process over piped stdio (CYP-5/CYP-13 live wiring);
 * MCP / OpenAI / remote connectors can implement the same interface later without changes here.
 *
 * CYP-142 (S4.0): lives in `:connector-core` so BOTH the local hub connector (`:server`) and the remote
 * bridge (`:remote-runtime`) implement the SAME contract over the SAME shared session core.
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
