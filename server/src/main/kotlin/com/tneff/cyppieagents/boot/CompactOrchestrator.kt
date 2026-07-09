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
 * CYP-326 — the platform-side compact orchestrator (NOT an agent — Warden/Scanner pattern; survives the PO's
 * own compact). When the PO's context crosses the configured threshold (default 500K, SEPARATE from the
 * CYP-325 bander's 75%/750K warning) AND "compact allowed" is on, it runs a staggered team compaction:
 * Round 1 a prepare message (PO first, then each worker +1 min); +10 min Round 2 the literal `/compact` (PO
 * first, then each worker +1 min), IDLE-gated (DEFER with bounded-wait, never inject into a running turn);
 * completion per agent via the empirically-proven `compact_result:success` signal within the round window;
 * honest **X/N** at the 10-min timeout (a never-idle or never-completed agent is pending/WARN, never faked).
 *
 * **Time-injectable (Tester #1, HARD requirement):** all waits are [delay] on the injected [scope] (a
 * `runTest` TestScope auto-advances them — the ~13-min sequence is tested in virtual time), and every wall-time
 * read goes through [clock] (`() -> Long`, warden/StallPolicy pattern). No real sleeping in tests.
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
    private val config: () -> CompactConfig,
    // Timings (locked): 1 min/agent stagger, +10 min between rounds, 10-min round completion window.
    private val staggerMs: Long = 60_000,
    private val roundGapMs: Long = 600_000,
    private val roundWindowMs: Long = 600_000,
    private val idlePollMs: Long = 1_000,
) {
    private val log = LoggerFactory.getLogger("boot.compactorchestrator")

    // Threshold-watch state (SEPARATE from the bander): armed = ready to fire on the next up-crossing.
    @Volatile private var armed = true
    @Volatile private var running = false
    @Volatile private var lastRun: CompactRunSummary? = null
    @Volatile private var runJob: Job? = null // CYP-326 kill-switch: cancelled when "compact allowed" → false mid-run

    /** A content-free orchestration event for the caller to map onto EventTypes. */
    sealed interface CompactEvent {
        data class PrepareSent(val agentId: String) : CompactEvent
        data class RequestSent(val agentId: String) : CompactEvent
        data class Completed(val agentId: String) : CompactEvent
        data class OrchestrationDone(val summary: CompactRunSummary) : CompactEvent
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
     */
    fun onConfigUpdated() {
        if (!config().allowed) runJob?.cancel()
    }

    private suspend fun runOrchestration() {
        running = true
        val order = agentsInOrder()
        val startedTs = clock()
        lastRun = CompactRunSummary(0, order.size, order, startedTs, null) // in-progress snapshot
        val completed = ConcurrentHashMap.newKeySet<String>()
        var collector: Job? = null
        try {
            // Round 1 — prepare (plain text, PO first then +1 min each). NO idle-gate, NO completion detection.
            order.forEachIndexed { i, agentId ->
                if (i > 0) delay(staggerMs)
                send(agentId, PREPARE_TEXT)
                emit(CompactEvent.PrepareSent(agentId))
            }
            // +10 min from Round 1 START to Round 2 START (subtract the stagger already elapsed in round 1).
            delay((roundGapMs - staggerMs * (order.size - 1)).coerceAtLeast(0))

            // Round 2 — /compact, PO first then +1 min each; IDLE-gated (DEFER, bounded-wait), then completion window.
            // Detection + timeout hang on THIS round only; the window is anchored at the PO's /compact send moment.
            // The collector emits Completed live (so an abort already has the completed-so-far reported).
            collector = scope.launch {
                compactCompletions.collect { id -> if (id in order && completed.add(id)) emit(CompactEvent.Completed(id)) }
            }
            val windowDeadline = clock() + roundWindowMs
            order.forEachIndexed { i, agentId ->
                if (i > 0) delay(staggerMs)
                // IDLE-gate (#5): bounded-wait for idle within the round window; never idle → skip (pending/WARN).
                while (isBusy(agentId) && clock() < windowDeadline) delay(idlePollMs)
                if (isBusy(agentId)) {
                    log.warn("compact: agent '{}' never idle within the round window → pending/WARN", agentId)
                    return@forEachIndexed
                }
                if (send(agentId, COMPACT_COMMAND)) emit(CompactEvent.RequestSent(agentId))
            }
            // Wait out the remaining window, then report the honest X/N.
            val remaining = (windowDeadline - clock()).coerceAtLeast(0)
            delay(remaining)
            val summary = CompactRunSummary(completed.size, order.size, order.filter { it !in completed }, startedTs, clock())
            lastRun = summary
            emit(CompactEvent.OrchestrationDone(summary))
        } catch (c: CancellationException) {
            // CYP-326 kill-switch abort: emit an HONEST aborted summary (completed-so-far, never faked as N/N).
            withContext(NonCancellable) {
                val summary = CompactRunSummary(completed.size, order.size, order.filter { it !in completed }, startedTs, clock(), aborted = true)
                lastRun = summary
                emit(CompactEvent.OrchestrationDone(summary))
            }
            throw c
        } finally {
            collector?.cancel()
            running = false
        }
    }

    fun status(): CompactStatus {
        val cfg = config()
        return CompactStatus(cfg.allowed, cfg.thresholdTokens, armed = armed, running = running, lastRun = lastRun)
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
