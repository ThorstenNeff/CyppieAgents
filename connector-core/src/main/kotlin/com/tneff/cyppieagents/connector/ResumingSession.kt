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

    /**
     * CYP-360 — the facade OWNS the exit listeners, because it owns which session *is* the agent.
     *
     * `ConnectorSession.addExitListener` has a no-op default, and this facade is what
     * [ClaudeCodeConnector.open] hands to the `LifecycleManager` whenever a durable session id exists. Left
     * inherited, the run-state authority would have subscribed to a stub and never heard a single death —
     * green unit tests over an unwired path.
     *
     * Forwarding blindly to [inner] would be just as wrong in the other direction: a stale `--resume` attempt
     * is *supposed* to die, and [healToFreshLocked] replaces it with a fresh session. Reporting that death
     * would flip a successfully-healed agent to ERROR — an invented observation, produced by the very fix that
     * exists to stop inventing them.
     *
     * Retiring the attempt when it is replaced is **too late**: its exit tail is already running by then (the
     * death is what triggered the heal), so it races the swap. The race-free rule is semantic, not temporal —
     * and the discriminator is **whether the attempt ever bound**, not whether the facade has committed:
     *
     *  - [firstAttempt] dying **without ever binding** *is* the stale-resume signal; the facade replaces it.
     *    Not the agent's death — never reported.
     *  - A **bound** attempt is the agent, even before [committed] (a live `--resume` only commits on the first
     *    turn, CYP-170 — gating on `committed` would swallow the real death of a resumed agent that dies before
     *    anyone talks to it).
     *  - Any death of the session that is currently [inner] is the agent's death — always reported.
     */
    private val exitListeners = java.util.concurrent.CopyOnWriteArrayList<(Int?) -> Unit>()

    override fun addExitListener(listener: (exitCode: Int?) -> Unit) {
        exitListeners.add(listener)
    }

    /** Subscribe to one attempt's death, reported only if that attempt is the agent when it dies. */
    private fun observeExitOf(session: ClaudeCodeSession) {
        session.addExitListener { exitCode ->
            if (closed) return@addExitListener
            if (session !== inner) return@addExitListener // already replaced by the heal
            // The stale-resume signal: the first attempt died without ever binding. The facade heals it.
            if (session === firstAttempt && !session.everBound) return@addExitListener
            exitListeners.forEach { l ->
                runCatching { l(exitCode) }
                    .onFailure { log.warn("exit listener failed for agent={}: {}", agentId, it.message) }
            }
        }
    }

    private fun forward(session: ClaudeCodeSession): Job =
        scope.launch { session.events.collect { _events.emit(it) } }

    fun start() {
        forwardJob = forward(firstAttempt)
        observeExitOf(firstAttempt) // CYP-360: before start, so a fast end cannot outrun the subscription
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
        observeExitOf(fresh) // CYP-360: the fresh session is now the agent; its end is the one that counts
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
