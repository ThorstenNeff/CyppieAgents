package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.ResultEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.SystemEvent
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * One long-lived stream-json session for a single agent — the **provider-agnostic core** (CYP-142 S4.0),
 * shared by the local hub connector (`:server`) and the remote bridge (`:remote-runtime`). Its hub couplings
 * are **seamed** (C1): `onBind`/`onUnbind` (server: `SessionRegistry` + CYP-167 store / bridge: no-op),
 * `onTurnResult` (server: `MediationRouter` / bridge: `WireSend` via the shared [MediationGate]), and
 * [observer] (server: CYP-37 recorder+projector / bridge: null). The CYP-167 write-after-init + CYP-170
 * lazy-init dependency-inversion logic lives HERE, once, so the bridge reuses it by construction.
 */
class ClaudeCodeSession(
    override val agentId: String,
    private val process: AgentProcess,
    private val turnQueue: SessionTurnQueue,
    private val scope: CoroutineScope,
    // CYP-142 seams (replace the former registry/router/recorder/projector hub coupling):
    private val observer: SessionObserver? = null,
    /** Invoked ONCE at the bind point with the session id (after `system/init`). Server: `registry.bind` +
     *  the CYP-167 durable store (write-after-init). Bridge: no-op. */
    private val onBind: ((sessionId: String) -> Unit)? = null,
    /** Invoked with the bound session id on close/stop. Server: `registry.unbind`. Bridge: no-op. */
    private val onUnbind: ((sessionId: String) -> Unit)? = null,
    /** Invoked with a turn-end result from a BOUND session. Server: `MediationRouter.onResult` (Gate #6 via
     *  [MediationGate]); Bridge: `WireSend` of [MediationGate.classify]'s result. */
    private val onTurnResult: ((ResultEvent) -> Unit)? = null,
) : ConnectorSession {

    /** CYP-167 — did this spawn ever bind a session id, or did it die unbound (e.g. a stale `--resume`)? */
    enum class StartupOutcome { BOUND, DIED_UNBOUND }

    private val log = LoggerFactory.getLogger("connector.session")
    private val _events = MutableSharedFlow<StreamJsonEvent>(extraBufferCapacity = 256)
    override val events: Flow<StreamJsonEvent> = _events

    // CYP-167 — completes BOUND at the first system/init bind, or DIED_UNBOUND if the process emits an
    // error result / ends its stdout before ever binding. The [ResumingSession] facade awaits this to
    // decide whether a `--resume` attempt succeeded or must fall back to a fresh respawn.
    private val startupOutcome = CompletableDeferred<StartupOutcome>()
    suspend fun awaitStartupOutcome(): StartupOutcome = startupOutcome.await()

    // correlationId for the in-flight work-run: minted at turn.start, carried to result.final.
    @Volatile private var currentCorrelationId: String? = null

    // Turn-queue key is the STABLE agentId for the whole session lifetime (Gate #5): it must NOT change at
    // system/init, or a turn injected after init would take a different mutex and race a turn still running.
    @Volatile private var boundSessionId: String? = null
    @Volatile private var pendingTurn: CompletableDeferred<Unit>? = null
    private var readerJob: Job? = null

    fun start() {
        readerJob = scope.launch {
            process.stdoutLines.collect { line ->
                val parsed = runCatching { CommJson.decodeFromString<StreamJsonEvent>(line) }.getOrNull()
                if (parsed == null) {
                    // Version drift / partial line: skip rather than crash the reader (CYP-5 note).
                    if (line.isNotBlank()) log.debug("skipping unparsable line for agent={}", agentId)
                    return@collect
                }
                val masked = EventMasking.mask(parsed) // Gate #3: mask BEFORE any egress (shared core)

                if (masked is SystemEvent && boundSessionId == null && !masked.sessionId.isNullOrBlank()) {
                    boundSessionId = masked.sessionId
                    // Gate #1 + CYP-167 write-after-init, via the seam (server: registry.bind + durable store).
                    onBind?.invoke(masked.sessionId!!)
                    if (!startupOutcome.isCompleted) startupOutcome.complete(StartupOutcome.BOUND)
                }

                // Observability tap (CYP-37): the SINGLE point after masking, before the stream forks.
                observer?.onEvent(agentId, masked.sessionId, currentCorrelationId, masked)

                _events.emit(masked) // to /ws/agent (UI) / the bridge's wire fork, already masked

                if (masked is ResultEvent) {
                    // CYP-167 stale-resume discriminator (R2): an error result WHILE still unbound = a dead
                    // `--resume`. R2 is carried by the bind-time complete(BOUND): once BOUND, a later error is a
                    // no-op via isCompleted. The boundSessionId==null check is defensive belt-and-suspenders.
                    if (masked.isError && boundSessionId == null && !startupOutcome.isCompleted) {
                        startupOutcome.complete(StartupOutcome.DIED_UNBOUND)
                    }
                    // CYP-170: ONLY mediate a result from a session that actually BOUND — a result while still
                    // unbound is a failed/stale `--resume` about to be replaced, and must NOT reach the hub
                    // (else the re-injected turn double-delivers). Gate #6 lives in the seam's [MediationGate].
                    if (boundSessionId != null) {
                        runCatching { onTurnResult?.invoke(masked) }
                            .onFailure { log.warn("mediation failed for agent={}: {}", agentId, it.message) }
                    }
                    pendingTurn?.complete(Unit) // release the single-flight turn (Gate #5)
                    pendingTurn = null
                }
            }
            // NORMAL stdout completion (the process exited on its own); a deliberate close() cancels this job.
            // CYP-167 secondary net: stdout ended before ANY bind → DIED_UNBOUND so the resume facade falls back.
            if (!startupOutcome.isCompleted) startupOutcome.complete(StartupOutcome.DIED_UNBOUND)
            // CYP-170: release a turn awaiting a result from a process that died WITHOUT one (no hang).
            pendingTurn?.complete(Unit)
            pendingTurn = null
            observer?.onProcessExit(agentId, boundSessionId)
        }
    }

    override suspend fun sendTurn(turn: UserTurn) {
        // Gate #5: serialize on the stable agentId key — hold from injection until this turn's result arrives.
        turnQueue.runTurn(agentId) {
            val correlationId = UUID.randomUUID().toString()
            currentCorrelationId = correlationId
            observer?.onTurnStart(agentId, boundSessionId, correlationId)
            val done = CompletableDeferred<Unit>()
            pendingTurn = done
            process.writeLine(turn.toNdjsonLine())
            done.await()
        }
    }

    override fun close() {
        readerJob?.cancel()
        process.destroy()
        boundSessionId?.let { onUnbind?.invoke(it) }
        pendingTurn?.cancel()
        observer?.onStopped(agentId)
    }

    /**
     * Stop the session and **confirm the process is gone** before returning (CYP-73, no zombie). Same
     * teardown as [close] — cancel the reader first so the stdout-completion path doesn't misfire as a
     * crash exit — then `destroy()` and await actual termination.
     */
    override suspend fun closeAndAwait() {
        readerJob?.cancel()
        process.destroy()
        process.awaitTerminated()
        boundSessionId?.let { onUnbind?.invoke(it) }
        pendingTurn?.cancel()
        observer?.onStopped(agentId)
    }
}
