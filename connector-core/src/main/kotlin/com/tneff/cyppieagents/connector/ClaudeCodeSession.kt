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
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.IOException
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

    // CYP-371's stop-is-not-a-death guard — this flag lives HERE, in CYP-371, NOT in CYP-351. It is set by our
    // own teardown (`close`/`closeAndAwait`) BEFORE `destroy()`. The reader tail runs on EOF, and since CYP-371 a
    // deliberate stop reaches EOF (destroy → the pipe closes) instead of being cancelled — so the tail must know
    // WHY the stream ended. An EOF we caused ourselves is a stop, not a death: it must not report `onProcessExit`
    // (which RecordingSessionObserver writes as a `process.exit` event). Without this flag, CYP-371's own
    // destroy→join would fabricate a death on EVERY stop — so the guard is CYP-371's to carry.
    //
    // CYP-351 does NOT own this flag; it adds a SECOND, orthogonal suppression on top: run-state honesty — even
    // an UNBIDDEN EOF is not a death unless the process is actually gone (death OBSERVED via the exit status, T7),
    // not inferred from EOF. Two distinct guards: `closing` = "the stop was ours" (CYP-371); T7 = "an EOF is not
    // yet a death" (CYP-351).
    @Volatile private var closing = false

    /**
     * CYP-360 — did this attempt ever bind a session id? Read by [ResumingSession] at the moment the session
     * ends, to tell the agent's session from a stale `--resume` attempt that never came up. Non-suspending on
     * purpose: `boundSessionId` is set during the stream, long before the end, so it is already settled.
     */
    val everBound: Boolean get() = boundSessionId != null

    /** CYP-360 — notified when this session's stream ends on its own (never on a deliberate [close]). */
    private val exitListeners = java.util.concurrent.CopyOnWriteArrayList<(Int?) -> Unit>()

    override fun addExitListener(listener: (exitCode: Int?) -> Unit) {
        exitListeners.add(listener)
    }

    fun start() {
        readerJob = scope.launch {
          // CYP-377: `AgentProcess.stdoutLines` reads via `readLine()` with no catch. Our own `destroy()` closes
          // the stream under a reader parked in `readLine()`, which throws `IOException: Stream closed`. Whatever
          // the cause, once the stream throws the reader has ENDED — so we catch it and fall through to the tail
          // (below), which ALREADY tells the two cases apart and needs no second discriminator here:
          //   • `closing == true`  (WE tore it down): the tail returns at its `closing` guard → a stop is not a
          //     death, and the last line was already flushed. No false `onProcessExit`.
          //   • `closing == false` (a LIVE process failed): the tail runs in FULL — DIED_UNBOUND + onProcessExit
          //     + the CYP-360 exit listeners — reporting an OBSERVED death, INDISTINGUISHABLE from an EOF death so
          //     BE-3's heal transition (CYP-356) sees a single shape. Not swallowed (that would go deaf on a dead
          //     process), not rethrown (an uncaught IOException would redden the gate and noise the dogfood logs).
          try {
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
          } catch (readerEnded: IOException) {
            // The stream ended by throwing; fall through to the tail (see above). Debug-logged, never re-raised:
            // the tail is the one place that reports the death (or silences the stop).
            log.debug("reader for agent={} ended via {} (closing={})", agentId, readerEnded.message, closing)
          }
            // stdout completion. Since CYP-371 this is reached on a deliberate `closeAndAwait()` too — it no
            // longer cancels the reader, it destroys the process and lets the reader run out on EOF (the flush),
            // so the reader ends on a stop as well as on an unbidden death. These first two steps run either way:
            // CYP-167 secondary net (stdout ended before ANY bind → DIED_UNBOUND so the resume facade falls back)
            // and CYP-170 (release a turn awaiting a result from a process that died WITHOUT one — no hang).
            if (!startupOutcome.isCompleted) startupOutcome.complete(StartupOutcome.DIED_UNBOUND)
            pendingTurn?.complete(Unit)
            pendingTurn = null
            // CYP-371: but a stop WE asked for is not a death. `closing` is set before our `destroy()`, so an EOF
            // we caused ourselves stops here — it must NOT report `onProcessExit` (which RecordingSessionObserver
            // records as a `process.exit` event) nor fire the CYP-360 exit listeners. Only an UNBIDDEN end reports
            // a death. (CYP-351 refines this further: even an unbidden EOF is not a death unless the process is
            // actually gone — death OBSERVED via the exit status, not inferred from EOF.)
            if (closing) return@launch
            observer?.onProcessExit(agentId, boundSessionId)
            // CYP-360: the same end, reported to whoever owns this session's identity (the resume facade, and
            // through it the run-state authority). The exit status is not available at this seam yet — CYP-351
            // supplies it — so listeners are told `null`, which means "unknown", never "clean".
            // A throwing listener must not swallow the others.
            exitListeners.forEach { l ->
                runCatching { l(null) }
                    .onFailure { log.warn("exit listener failed for agent={}: {}", agentId, it.message) }
            }
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
        closing = true // CYP-371: a stop is not a death (the cancel below already prevents the tail; belt-and-suspenders)
        readerJob?.cancel()
        process.destroy()
        boundSessionId?.let { onUnbind?.invoke(it) }
        pendingTurn?.cancel()
        observer?.onStopped(agentId)
    }

    /**
     * Stop the session and **confirm the process is gone** before returning (CYP-73, no zombie), and return
     * **quiescent** — the in-flight turn flushed, no `active()`-read still running — so the switch's
     * drain-before-rescope barrier (PlatformWiring, CYP-247 S3 / r4) is real. `removeAndAwait` awaits the OS
     * process, NOT the [readerJob] that drives the `active()`-reads (`onContextTokens → tokenUsage` and
     * `onTurnResult → hub.postAsAgent`); `_events.emit` (buffer 256) does not suspend, so an in-flight
     * `ResultEvent` body left unjoined would run AFTER this returns — concurrent with a switch's `rescope`,
     * mis-attributing to the newly-active project. Joining the reader while `active() == this project` is what
     * closes that window (no loss).
     *
     * The join is reached by `destroy()` **then** `join()`, and never cancels on the normal path — see the body,
     * which says why. (This KDoc read "cancel-AND-JOIN the reader first" until CYP-371: cancelling before
     * `destroy()` deadlocked, and cancelling at all severs the very flush this promises.) The one hole in the
     * quiescence guarantee is the 5 s flush timeout below — if it fires, the reader is severed mid-turn and
     * CYP-247's barrier does not hold for that turn; tracked as CYP-374.
     */
    override suspend fun closeAndAwait() {
        closing = true // CYP-371: set BEFORE destroy() so the tail reads our own EOF as a stop, not a death
        // CYP-371 — ORDER, AND NO CANCEL. `readLine()` blocks; cancelling a coroutine does not interrupt a
        // thread parked in a blocking read, so `cancelAndJoin()` waited for an EOF that only `destroy()` could
        // produce — and `destroy()` stood behind it. Against a silent, long-lived `claude`, `stop()` never
        // returned (measured: 15 s and counting; the reader parked forever on a process that never spoke).
        //
        // `destroy()` first: the pipe closes, the blocking read returns EOF, the reader ends **on its own**.
        // Then `join()` — NOT `cancelAndJoin()`. Cancelling here would cut off whatever the process said as it
        // died, breaking CYP-247 S3's promise that the in-flight turn is flushed before this returns. The reader
        // must run out, not be severed. `PtyManager.close()` has always done exactly this, for the same reason.
        //
        // The timeout is a belt, not the mechanism: a process that ignores SIGTERM and holds its stdout open
        // would otherwise park us forever. If it fires we cancel, log, and continue — a stop that degrades
        // loudly beats a stop that never returns.
        process.destroy()
        val flushed = withTimeoutOrNull(READER_FLUSH_TIMEOUT_MS) { readerJob?.join() } != null
        if (!flushed) {
            log.warn("reader did not flush within {} ms for agent={}; cancelling", READER_FLUSH_TIMEOUT_MS, agentId)
            readerJob?.cancel()
        }
        process.awaitTerminated()
        boundSessionId?.let { onUnbind?.invoke(it) }
        pendingTurn?.cancel()
        observer?.onStopped(agentId)
    }

    private companion object {
        /** CYP-371 — how long a destroyed process gets to flush its last stdout before we sever the reader. */
        const val READER_FLUSH_TIMEOUT_MS = 5_000L
    }
}
