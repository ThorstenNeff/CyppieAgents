package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventPage
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * Deterministic test seams for the Event-Log spine (CYP-44, requested by the Tester). Shared so the
 * Needle-Absence / ordering / overflow harnesses (ST3/ST10) reuse exactly the same doubles.
 */

/**
 * Fully controllable [TimeSource]: [clock] is settable (simulate an NTP backwards jump) while
 * [nextSeq] stays atomically monotonic — the property the total-order tests rely on.
 */
class ManualTimeSource(start: Long = 0L, startSeq: Long = 0L) : TimeSource {
    @Volatile
    var clock: Long = start
    private val seq = AtomicLong(startSeq)

    override fun now(): Long = clock
    override fun nextSeq(): Long = seq.incrementAndGet()
    override fun resumeAtLeast(seq: Long) {
        this.seq.updateAndGet { current -> if (seq > current) seq else current }
    }
}

/**
 * Latch-pausable [EventSink] wrapper: while [pause]d, every [appendBatch] parks until [resume],
 * letting a test deterministically saturate the recorder's queue and prove the tap stays
 * non-blocking under back-pressure (Gate #1/#3 Observer-effect bound) without timing flakiness.
 */
class LatchableEventSink(private val delegate: EventSink) : EventSink {
    @Volatile
    private var gate: CompletableDeferred<Unit>? = null
    val appendCalls = AtomicInteger(0)

    fun pause() {
        gate = CompletableDeferred()
    }

    fun resume() {
        gate?.complete(Unit)
        gate = null
    }

    override suspend fun appendBatch(drafts: List<EventDraft>): List<Event> {
        gate?.await()
        appendCalls.incrementAndGet()
        return delegate.appendBatch(drafts)
    }

    override suspend fun query(filter: EventFilter, page: Page): EventPage = delegate.query(filter, page)
    override fun subscribe(filter: EventFilter): Flow<Event> = delegate.subscribe(filter)
    override suspend fun deleteByProject(projectId: String): Int = delegate.deleteByProject(projectId)
}

/** Compact draft builder for tests. */
fun draft(
    agent: String = "agent",
    team: String = "team",
    type: EventType = EventType.TURN_START,
    severity: Severity = Severity.INFO,
    sessionId: String? = null,
    correlationId: String? = null,
    sourceTs: Long? = null,
    detail: JsonObject = JsonObject(emptyMap()),
) = EventDraft(
    agentId = agent,
    projectId = team,
    type = type,
    severity = severity,
    sessionId = sessionId,
    correlationId = correlationId,
    sourceTs = sourceTs,
    detail = detail,
)
