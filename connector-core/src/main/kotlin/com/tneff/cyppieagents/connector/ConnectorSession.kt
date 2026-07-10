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

    /**
     * CYP-360 — observe this session ending **on its own**: [listener] fires when the session's stream ends
     * without anyone asking it to. A deliberate [close]/[closeAndAwait] never fires it; that path cancels the
     * reader first.
     *
     * [exitCode] is the process's exit status where one can be observed (`0` clean, non-zero crash/signal) and
     * `null` where it cannot — which means **unknown**, never "clean". CYP-351 supplies the status; until then
     * every caller is told `null`.
     *
     * The listener belongs on the *session*, not on the observability tap, because only the party that spawned
     * it knows which authority owns this agent: one connector serves every project, but each project has its
     * own run-state manager. Whoever spawns subscribes, so the end is routed by construction rather than by a
     * projectId threaded through the tap.
     *
     * Default no-op — **and that default is a trap.** [ResumingSession] wraps a [ClaudeCodeSession] and is what
     * the connector hands out whenever a durable session id exists; inheriting this no-op silently swallowed
     * every subscription while the unit tests, which build the inner session directly, stayed green. A wrapper
     * that does not override this hears nothing and says nothing.
     */
    fun addExitListener(listener: (exitCode: Int?) -> Unit) {}
}
