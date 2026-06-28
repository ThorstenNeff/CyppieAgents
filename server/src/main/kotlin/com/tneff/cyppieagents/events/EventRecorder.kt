package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * The decoupling buffer between "an event happens" and "an event is on disk" (PRD §3.3), so writing
 * never distorts what we measure on the hot Mediation path (Observer-Effect). [record] is the tap;
 * a single writer-coroutine batch-drains the bounded queue into the [EventSink].
 *
 * Three gates are structural here, not by discipline:
 *  - **Gate #1 non-blocking tap:** [record] is a plain (non-`suspend`) function over `Channel.trySend`
 *    — it cannot suspend and cannot await disk. The compiler enforces it.
 *  - **Gate #2 drop-newest + visible:** a full queue rejects the *new* event and bumps an
 *    authoritative in-memory counter; the writer then emits a [EventType.LOG_DROPPED] event with the
 *    cumulative count. That `log.dropped` is appended **directly to the sink, never back through the
 *    queue**, so the proof of the gap can't itself be dropped (CYP-44).
 *  - **Gate #3 total order:** stamping (`seq`) lives in the sink under its write lock; the recorder
 *    only moves drafts, so concurrent tappers can't perturb ordering.
 */
class EventRecorder(
    private val sink: EventSink,
    private val scope: CoroutineScope,
    capacity: Int = DEFAULT_CAPACITY,
    private val batchSize: Int = DEFAULT_BATCH,
) {
    private val log = LoggerFactory.getLogger("events.recorder")
    private val queue = Channel<EventDraft>(capacity)
    private val droppedCount = AtomicLong(0)
    private var reportedDropped = 0L
    private var writer: Job? = null

    /** Authoritative count of events dropped under back-pressure since start (monotonic). */
    val dropped: Long get() = droppedCount.get()

    /**
     * Non-blocking hot-path tap. Returns true if the event was enqueued, false if it was dropped
     * (drop-newest) because the queue was full. NEVER suspends, NEVER touches disk.
     */
    fun record(draft: EventDraft): Boolean {
        if (queue.trySend(draft).isSuccess) return true
        droppedCount.incrementAndGet()
        return false
    }

    /** Start the single writer-coroutine. Idempotent guard: must be called once. */
    fun start() {
        check(writer == null) { "EventRecorder already started" }
        writer = scope.launch { drainLoop() }
    }

    /** Close the queue, drain what's buffered, emit a final drop report, and await the writer. */
    suspend fun stop() {
        queue.close()
        writer?.join()
    }

    private suspend fun drainLoop() {
        val batch = ArrayList<EventDraft>(batchSize)
        // `for (x in queue)` ends when the channel is closed AND drained.
        for (first in queue) {
            batch.clear()
            batch.add(first)
            while (batch.size < batchSize) {
                val next = queue.tryReceive().getOrNull() ?: break
                batch.add(next)
            }
            flush(batch)
            reportDropsIfAny()
        }
        // Final report so drops that happened with nothing left to drain are still made visible.
        reportDropsIfAny()
    }

    private suspend fun flush(batch: List<EventDraft>) {
        try {
            sink.appendBatch(batch)
        } catch (e: Throwable) {
            // A sink failure must not kill the writer-loop; log and keep draining.
            log.error("event flush failed for {} drafts", batch.size, e)
        }
    }

    /**
     * If the drop counter advanced since the last report, append a `log.dropped` event carrying the
     * delta and the cumulative total. Goes straight to the sink (bypassing the queue) so it survives
     * a full queue — the gap must be visible in the same telemetry the operator watches.
     */
    private suspend fun reportDropsIfAny() {
        val total = droppedCount.get()
        if (total <= reportedDropped) return
        val delta = total - reportedDropped
        reportedDropped = total
        val report = EventDraft(
            agentId = PLATFORM,
            projectId = PLATFORM,
            type = EventType.LOG_DROPPED,
            severity = Severity.WARN,
            detail = buildJsonObject {
                put("dropped", delta)
                put("total", total)
            },
        )
        try {
            sink.appendBatch(listOf(report))
        } catch (e: Throwable) {
            log.error("failed to emit log.dropped (total={})", total, e)
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 4096
        const val DEFAULT_BATCH = 64

        /** System source id for platform-level telemetry (e.g. `log.dropped`). */
        const val PLATFORM = "platform"
    }
}
