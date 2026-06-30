package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * CYP-167/CYP-170 — the resume facade. Returned by [ClaudeCodeConnector.open] ONLY when a durable
 * session id exists for the agent. It runs the first attempt WITH `--resume <id>`.
 *
 * **CYP-170 — dependency inversion (the fix for the deadlock):** `claude` in stream-json input mode
 * emits `system/init` ONLY after it receives the first stdin turn (verified, 2.1.196). The original
 * facade gated `sendTurn` on bind → bind waited on the turn → the turn waited on the gate ⇒ deadlock.
 * So the FIRST turn is now sent **ungated** to the resumed attempt, and resume success/failure is read
 * from THAT turn's outcome:
 *  - the attempt binds (`system/init` during the turn) ⇒ BOUND ⇒ committed (resume worked).
 *  - the attempt dies unbound (`is_error` while `boundSessionId == null`, or stdout ends) ⇒ DIED_UNBOUND
 *    ⇒ a stale id: clear the durable entry, respawn fresh WITHOUT `--resume`, and **re-inject the SAME
 *    turn exactly once** so it isn't lost. No third attempt.
 *
 * No double-deliver: the stale attempt never binds, and [ClaudeCodeSession] only mediates a result from
 * a bound session, so the stale attempt's output never reaches the hub — only the committed attempt's.
 */
class ResumingSession(
    override val agentId: String,
    private val scope: CoroutineScope,
    private val firstAttempt: ClaudeCodeSession,
    /** Drop the durable entry — called ONLY on a genuine stale-resume failure, never on [close] (R3). */
    private val onResumeFailed: () -> Unit,
    /** Spawn a fresh session WITHOUT `--resume` (the durable entry has just been cleared). */
    private val respawnFresh: () -> ClaudeCodeSession,
) : ConnectorSession {

    private val log = LoggerFactory.getLogger("connector.resume")
    private val _events = MutableSharedFlow<StreamJsonEvent>(extraBufferCapacity = 256)
    override val events: Flow<StreamJsonEvent> = _events

    @Volatile private var inner: ClaudeCodeSession = firstAttempt
    @Volatile private var committed = false        // set once the resume question is answered (first turn)
    private val commitMutex = Mutex()              // serializes the one-time first-turn/commit transition
    @Volatile private var closed = false
    @Volatile private var forwardJob: Job? = null

    private fun forward(session: ClaudeCodeSession): Job =
        scope.launch { session.events.collect { _events.emit(it) } }

    fun start() {
        forwardJob = forward(firstAttempt)
        firstAttempt.start()
    }

    override suspend fun sendTurn(turn: UserTurn) {
        if (committed) { inner.sendTurn(turn); return }
        commitMutex.withLock {
            if (committed) { inner.sendTurn(turn); return }

            // FIRST turn — sent UNGATED to the resumed attempt (CYP-170: the CLI only emits system/init
            // while processing a turn, so the turn IS the probe). This either binds or dies unbound.
            firstAttempt.sendTurn(turn)

            when (firstAttempt.awaitStartupOutcome()) {
                ClaudeCodeSession.StartupOutcome.BOUND -> {
                    committed = true // resume worked; the turn already succeeded on the resumed session
                }
                ClaudeCodeSession.StartupOutcome.DIED_UNBOUND -> {
                    // Stale `--resume`: clear, respawn fresh, re-inject the SAME turn exactly once. R3: if a
                    // close raced in before we got here, don't clear/respawn (no spurious clear, no work).
                    if (closed) return
                    log.info(
                        "resume failed for agent={} (died unbound); clearing entry, respawning fresh + re-injecting the turn",
                        agentId,
                    )
                    forwardJob?.cancel()
                    firstAttempt.close()
                    onResumeFailed()
                    val fresh = respawnFresh()
                    inner = fresh
                    committed = true // set BEFORE the re-inject so no further turn can re-enter this path
                    forwardJob = forward(fresh)
                    fresh.start()
                    // Re-inject the first turn onto the fresh session. The stale attempt never bound, so it
                    // never mediated → the hub sees this turn's result EXACTLY once (from the fresh attempt).
                    // No third attempt: if the fresh one also dies, the turn surfaces as a normal dead session.
                    fresh.sendTurn(turn)
                }
            }
        }
    }

    override fun close() {
        closed = true
        forwardJob?.cancel()
        inner.close()
    }

    override suspend fun closeAndAwait() {
        closed = true
        forwardJob?.cancel()
        inner.closeAndAwait()
    }
}
