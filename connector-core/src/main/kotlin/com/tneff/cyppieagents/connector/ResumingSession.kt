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
 *
 * **CYP-330 — proactive stale-resume probe (the RESTART fix):** the turn-path heal above only fires when a
 * turn is sent, so a **restart** (which sends no turn) left a dead `--resume` unhealed → the agent hung dead
 * and the operator had to press START. [start] now launches a PROACTIVE probe of [awaitStartupOutcome]: a
 * stale `--resume` dies unbound at startup (error result / stdout ends) WITHOUT a turn, so the probe runs the
 * SAME clear+fresh-respawn — with no turn to re-inject. A LIVE resume stays pending (binds only on the first
 * turn, CYP-170), so the probe never fires for it and the context is preserved (NO blanket clear on restart).
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
    @Volatile private var committed = false        // set once the resume question is answered (first turn / probe)
    private val commitMutex = Mutex()              // serializes the one-time commit transition (turn OR probe)
    @Volatile private var closed = false
    @Volatile private var forwardJob: Job? = null
    @Volatile private var startupProbe: Job? = null // CYP-330: the proactive stale-resume probe

    private fun forward(session: ClaudeCodeSession): Job =
        scope.launch { session.events.collect { _events.emit(it) } }

    fun start() {
        forwardJob = forward(firstAttempt)
        firstAttempt.start()
        // CYP-330: proactively answer the resume question WITHOUT a turn. A stale `--resume` dies unbound at
        // startup (error result / stdout ends), so this heals a restart to a fresh session; a LIVE resume never
        // completes DIED_UNBOUND at startup (it binds only on the first turn, CYP-170), so this waits harmlessly
        // and the turn path takes over — context preserved.
        startupProbe = scope.launch {
            if (firstAttempt.awaitStartupOutcome() == ClaudeCodeSession.StartupOutcome.DIED_UNBOUND) {
                healToFresh(reInject = null)
            }
        }
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
                    // Stale `--resume`: clear, respawn fresh, re-inject the SAME turn exactly once. Shared with
                    // the proactive probe via [healToFresh] (already holding commitMutex → non-locking form).
                    healToFreshLocked(reInject = turn)
                }
            }
        }
    }

    /** CYP-330: acquire the commit lock then heal (the proactive-probe entry; the turn path is already locked). */
    private suspend fun healToFresh(reInject: UserTurn?) {
        if (committed || closed) return
        commitMutex.withLock { healToFreshLocked(reInject) }
    }

    /**
     * CYP-167/CYP-330 — the ONE stale-resume recovery, run under [commitMutex]: clear the durable entry,
     * respawn fresh, and (turn path only) re-inject the SAME turn exactly once. [reInject] == null is the
     * restart/proactive case (no turn to replay). Re-checks [committed]/[closed] so the turn path and the
     * proactive probe can't both heal (whichever wins commits; the other returns a no-op). R3: a close that
     * raced in wins → no spurious clear/respawn.
     */
    private suspend fun healToFreshLocked(reInject: UserTurn?) {
        if (committed || closed) return
        log.info(
            "resume failed for agent={} (died unbound); clearing entry, respawning fresh{}",
            agentId,
            if (reInject != null) " + re-injecting the turn" else " (restart, no turn)",
        )
        forwardJob?.cancel()
        firstAttempt.close()
        onResumeFailed()
        val fresh = respawnFresh()
        inner = fresh
        committed = true // set BEFORE any re-inject so no further turn/probe can re-enter this path
        forwardJob = forward(fresh)
        fresh.start()
        // Re-inject the first turn onto the fresh session (turn path only). The stale attempt never bound, so it
        // never mediated → the hub sees this turn's result EXACTLY once. No third attempt.
        reInject?.let { fresh.sendTurn(it) }
    }

    override fun close() {
        closed = true
        startupProbe?.cancel()
        forwardJob?.cancel()
        inner.close()
    }

    override suspend fun closeAndAwait() {
        closed = true
        startupProbe?.cancel()
        forwardJob?.cancel()
        inner.closeAndAwait()
    }
}
