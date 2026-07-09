package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.model.CompactConfig
import com.tneff.cyppieagents.model.CompactRunSummary
import com.tneff.cyppieagents.model.CompactStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * CYP-326/329 — the platform-side compact orchestrator (NOT an agent — Warden/Scanner pattern; survives the
 * PO's own compact). When the PO's context crosses the configured threshold (default 500K, SEPARATE from the
 * CYP-325 bander's 75%/750K warning) AND "compact allowed" is on, it runs a staggered team compaction:
 * Round 1 a prepare message (PO first, then each worker +[CompactConfig.staggerMs]); a fixed
 * [CompactConfig.roundGapMs] pause after the last prepare; Round 2 the literal `/compact` (PO first, then each
 * worker +stagger), IDLE-gated (DEFER with bounded-wait, never inject into a running turn); completion per
 * agent via the empirically-proven `compact_result:success` signal within [CompactConfig.roundWindowMs];
 * honest **X/N** at the window timeout (a never-idle or never-completed agent is pending/WARN, never faked).
 *
 * **CYP-329 — operator-tunable timings, snapshotted at run-start:** the three timings come from [config] (so an
 * operator can speed the sequence up without a redeploy); [runOrchestration] reads them ONCE at run-start, so a
 * mid-run config change is a no-op for the in-flight run and takes effect on the next run. Defaults are 2 min
 * stagger / 2 min round-gap / 10 min window → a uniform 2-min cadence. The [roundGapMs] semantic is a **fixed
 * pause after the last prepare** (decision (c)); the old start-to-start formula collapsed to 0 at fast values.
 * A pure timing change never aborts a run — only "compact allowed"→false does ([onConfigUpdated]).
 *
 * **Time-injectable (Tester #1, HARD requirement):** all waits are [delay] on the injected [scope] (a
 * `runTest` TestScope auto-advances them — the sequence is tested in virtual time), and every wall-time read
 * goes through [clock] (`() -> Long`, warden/StallPolicy pattern). No real sleeping in tests.
 */
class CompactOrchestrator(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** PO context-token updates (the CYP-325 feed, already filtered to the PO agent); null = unknown (ignored). */
    private val poContext: Flow<Int?>,
    /** Per-agent compaction-completed signal (the CYP-326 [CompactCompletionSignal]). */
    private val compactCompletions: Flow<String>,
    /** True iff the agent is mid-turn (the CYP-324 busy signal) — the IDLE-gate. */
    private val isBusy: (agentId: String) -> Boolean,
    /** The mediator send seam (`sendTurn`); returns false if there was no live session (fail-closed, → pending). */
    private val send: suspend (agentId: String, text: String) -> Boolean,
    /** Content-free event emission (agentId + optional X/N detail), via the CYP-34 EventSink. */
    private val emit: suspend (event: CompactEvent) -> Unit,
    /** The run roster, **PO first** then workers (locked decision (c)). */
    private val agentsInOrder: () -> List<String>,
    /** CYP-329 — the live config: the "allowed" gate, threshold, AND the tunable timings (read at run-start). */
    private val config: () -> CompactConfig,
    /** CYP-327 — the per-run correlationId source (injectable for deterministic tests). */
    private val correlationIdGen: () -> String = { java.util.UUID.randomUUID().toString() },
    /** Idle-poll granularity for the IDLE-gate — fixed platform behaviour, NOT operator config. */
    private val idlePollMs: Long = 1_000,
) {
    private val log = LoggerFactory.getLogger("boot.compactorchestrator")

    // Threshold-watch state (SEPARATE from the bander): armed = ready to fire on the next up-crossing.
    @Volatile private var armed = true
    @Volatile private var running = false
    @Volatile private var lastRun: CompactRunSummary? = null
    @Volatile private var runJob: Job? = null // CYP-326 kill-switch: cancelled when "compact allowed" → false mid-run

    /** A content-free orchestration event for the caller to map onto EventTypes. CYP-327: every event of a run
     *  carries the run's [correlationId] (the authoritative join key the caller stamps onto the EventDraft). */
    sealed interface CompactEvent {
        val correlationId: String
        data class PrepareSent(val agentId: String, override val correlationId: String) : CompactEvent
        data class RequestSent(val agentId: String, override val correlationId: String) : CompactEvent
        data class Completed(val agentId: String, override val correlationId: String) : CompactEvent
        data class OrchestrationDone(val summary: CompactRunSummary, override val correlationId: String) : CompactEvent
    }

    fun start() {
        scope.launch { poContext.collect { onPoContext(it) } }
    }

    /** Threshold-watch + re-arm (fire-once-per-up-crossing, absolute 500K default). */
    internal fun onPoContext(tokens: Int?) {
        val t = tokens ?: return
        val cfg = config()
        when {
            t <= cfg.thresholdTokens -> armed = true // re-arm on drop back below the threshold
            armed && cfg.allowed && !running && t > cfg.thresholdTokens -> {
                armed = false
                runJob = scope.launch { runOrchestration() }
            }
        }
    }

    /**
     * CYP-326 kill-switch: call when the compact config changes (from `POST /api/compact/config`). If "compact
     * allowed" was turned OFF while a run is in progress, ABORT it — cancel the run job so no further `/compact`
     * is sent to a not-yet-served agent and the completion-wait ends; the run emits an HONEST aborted
     * `orchestration.done` (X of N so far, `aborted=true`, never re-labelled "all done"). Already-sent `/compact`
     * commands are not retractable (a compacted agent still counts in `completed`).
     *
     * **CYP-329 — Not-Aus guardrail:** the same endpoint now also carries timing changes, but this guard fires
     * ONLY on `allowed`→false. A pure timing change (allowed still true) → guard false → NO cancel → the run
     * runs to completion (and, since timings are snapshotted at run-start, the change applies to the NEXT run).
     */
    fun onConfigUpdated() {
        if (!config().allowed) runJob?.cancel()
    }

    private suspend fun runOrchestration() {
        running = true
        val order = agentsInOrder()
        // CYP-329: snapshot the tunable timings ONCE at run-start — a mid-run config change is a no-op for this
        // in-flight run (it applies to the NEXT run), consistent with "a timing change never aborts a run".
        val cfg0 = config()
        val stagger = cfg0.staggerMs
        val roundGap = cfg0.roundGapMs
        val window = cfg0.roundWindowMs
        val startedTs = clock()
        val cid = correlationIdGen() // CYP-327: the run's authoritative join key, stamped onto every event + the summary
        lastRun = CompactRunSummary(0, order.size, order, startedTs, null, correlationId = cid) // in-progress snapshot
        val completed = ConcurrentHashMap.newKeySet<String>()
        var collector: Job? = null
        try {
            // Round 1 — prepare (plain text, PO first then +stagger each). NO idle-gate, NO completion detection.
            order.forEachIndexed { i, agentId ->
                if (i > 0) delay(stagger)
                send(agentId, PREPARE_TEXT)
                emit(CompactEvent.PrepareSent(agentId, cid))
            }
            // CYP-329 decision (c): a FIXED pause after the last prepare before Round 2 (uniform cadence; never
            // underflows — the old `(roundGapMs - stagger*(n-1)).coerceAtLeast(0)` collapsed to 0 at fast values).
            delay(roundGap)

            // Round 2 — /compact, PO first then +stagger each; IDLE-gated (DEFER, bounded-wait), then completion window.
            // Detection + timeout hang on THIS round only; the window is anchored at the PO's /compact send moment.
            // The collector emits Completed live (so an abort already has the completed-so-far reported).
            collector = scope.launch {
                compactCompletions.collect { id -> if (id in order && completed.add(id)) emit(CompactEvent.Completed(id, cid)) }
            }
            val windowDeadline = clock() + window
            order.forEachIndexed { i, agentId ->
                if (i > 0) delay(stagger)
                // IDLE-gate (#5): bounded-wait for idle within the round window; never idle → skip (pending/WARN).
                while (isBusy(agentId) && clock() < windowDeadline) delay(idlePollMs)
                if (isBusy(agentId)) {
                    log.warn("compact: agent '{}' never idle within the round window → pending/WARN", agentId)
                    return@forEachIndexed
                }
                if (send(agentId, COMPACT_COMMAND)) emit(CompactEvent.RequestSent(agentId, cid))
            }
            // Wait out the remaining window, then report the honest X/N.
            val remaining = (windowDeadline - clock()).coerceAtLeast(0)
            delay(remaining)
            val summary = CompactRunSummary(completed.size, order.size, order.filter { it !in completed }, startedTs, clock(), correlationId = cid)
            lastRun = summary
            emit(CompactEvent.OrchestrationDone(summary, cid))
        } catch (c: CancellationException) {
            // CYP-326 kill-switch abort: emit an HONEST aborted summary (completed-so-far, never faked as N/N).
            withContext(NonCancellable) {
                val summary = CompactRunSummary(completed.size, order.size, order.filter { it !in completed }, startedTs, clock(), aborted = true, correlationId = cid)
                lastRun = summary
                emit(CompactEvent.OrchestrationDone(summary, cid))
            }
            throw c
        } finally {
            collector?.cancel()
            running = false
        }
    }

    fun status(): CompactStatus {
        val cfg = config()
        return CompactStatus(
            cfg.allowed, cfg.thresholdTokens, armed = armed, running = running, lastRun = lastRun,
            staggerMs = cfg.staggerMs, roundGapMs = cfg.roundGapMs, roundWindowMs = cfg.roundWindowMs, // CYP-329
        )
    }

    companion object {
        const val PREPARE_TEXT = "Bereite dich auf einen compact vor."
        /** The literal slash command the long-lived harness intercepts (spike-verified). */
        const val COMPACT_COMMAND = "/compact"
        /** CYP-326 #1 — the [UserEvent.injectedSource] marker for the orchestrator's transcript-visible injects. */
        const val INJECTED_SOURCE = "compact-orchestrator"

        /**
         * CYP-326 #1 — the synthetic transcript event for a platform-injected message ([text] = PREPARE_TEXT or
         * `/compact`). Recorded into the AgentEventStore by the `send` seam (before the stdin write) so the
         * operator sees the incoming trigger; [UserEvent.injectedSource] = [INJECTED_SOURCE] marks it so the
         * client renders it (a plain replayed echo, injectedSource == null, stays dropped → no CYP-323 dup).
         */
        fun injectedUserEvent(text: String): com.tneff.cyppieagents.model.UserEvent =
            com.tneff.cyppieagents.model.UserEvent(
                com.tneff.cyppieagents.model.AgentMessage(role = "user", content = listOf(com.tneff.cyppieagents.model.TextBlock(text))),
                injectedSource = INJECTED_SOURCE,
            )
    }
}
