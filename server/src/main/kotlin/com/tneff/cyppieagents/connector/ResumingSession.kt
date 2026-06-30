package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * CYP-167 / E4 — the **resume facade**. Returned by [ClaudeCodeConnector.open] ONLY when a durable
 * session id exists for the agent. It runs the first attempt WITH `--resume <id>`; if that attempt dies
 * **without ever binding** (a stale/expired id — spike verdict (a)/(b): the CLI emits an error result
 * while unbound, then exits), it calls [onResumeFailed] (which drops the durable entry) and respawns a
 * fresh session WITHOUT the flag — **exactly once** (no third try: a fresh spawn that also dies surfaces
 * as a normal dead session, fail closed).
 *
 * Why a facade: callers ([com.tneff.cyppieagents.boot.LifecycleManager] / [ConnectorSessions]) hold ONE
 * stable [ConnectorSession] across the swap — no `open()`-signature ripple. The fallback is armed ONLY
 * in the pre-bind startup window: once a session binds it is committed, and a later death is a normal
 * mid-session crash (CYP-73's restart path), NOT a resume failure — so the durable entry stays intact (R2).
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

    // The committed inner session (attempt#1 if it bound, else the fresh respawn). [sendTurn] waits on
    // [ready] so it can never write to a not-yet-committed inner.
    @Volatile private var inner: ClaudeCodeSession = firstAttempt
    private val ready = CompletableDeferred<Unit>()
    @Volatile private var closed = false
    private var supervisor: Job? = null
    @Volatile private var forwardJob: Job? = null

    private fun forward(session: ClaudeCodeSession): Job =
        scope.launch { session.events.collect { _events.emit(it) } }

    fun start() {
        forwardJob = forward(firstAttempt)
        firstAttempt.start()
        supervisor = scope.launch {
            when (firstAttempt.awaitStartupOutcome()) {
                ClaudeCodeSession.StartupOutcome.BOUND -> {
                    // The resume worked: attempt#1 is the committed session.
                    inner = firstAttempt
                    if (closed) firstAttempt.close()
                    if (!ready.isCompleted) ready.complete(Unit)
                }
                ClaudeCodeSession.StartupOutcome.DIED_UNBOUND -> {
                    firstAttempt.close() // tidy the dead resume attempt (its process already exited)
                    forwardJob?.cancel()
                    // R3: if we are already closing, do NOT clear and do NOT respawn — no spurious clear().
                    if (closed) { if (!ready.isCompleted) ready.cancel(); return@launch }
                    log.info("resume failed for agent={} (died unbound); clearing entry, respawning fresh", agentId)
                    onResumeFailed()
                    val fresh = respawnFresh()
                    inner = fresh
                    if (closed) { fresh.close(); if (!ready.isCompleted) ready.cancel(); return@launch }
                    forwardJob = forward(fresh)
                    fresh.start()
                    // No second await: the fallback fires exactly once (M7). A fresh spawn that also dies
                    // surfaces as a normal dead session via its own events/sendTurn (fail closed).
                    if (!ready.isCompleted) ready.complete(Unit)
                }
            }
        }
    }

    override suspend fun sendTurn(turn: UserTurn) {
        ready.await() // block until a session is committed (resume bound, or fresh fallback up)
        inner.sendTurn(turn)
    }

    override fun close() {
        closed = true
        supervisor?.cancel()
        forwardJob?.cancel()
        inner.close()
        if (!ready.isCompleted) ready.cancel()
    }

    override suspend fun closeAndAwait() {
        closed = true
        supervisor?.cancel()
        forwardJob?.cancel()
        inner.closeAndAwait()
        if (!ready.isCompleted) ready.cancel()
    }
}
