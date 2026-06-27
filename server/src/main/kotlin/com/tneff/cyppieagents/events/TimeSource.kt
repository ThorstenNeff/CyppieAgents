package com.tneff.cyppieagents.events

import java.util.concurrent.atomic.AtomicLong

/**
 * The single time + order authority for the Event-Log (PRD 06 §5). Stamping happens in the
 * `append` path, never at the callers — so the **total order** holds regardless of which component
 * writes (Mediator today, Scanner/Warden in 07 later).
 *
 * Wall-clock ([now]) is **not monotonic** (NTP correction can step it backwards); [nextSeq] is the
 * monotonic tiebreaker that gives total order even on equal ms or a clock jump. [nextSeq] must be
 * thread-safe because several sources and the writer-coroutine compete for it.
 *
 * Injectable (interface) so tests get deterministic `seq` and can simulate a backwards clock jump
 * without flakiness (Tester seam, CYP-44).
 */
interface TimeSource {
    /** Authoritative event time, epoch ms. */
    fun now(): Long

    /** Strictly increasing sequence; atomic across concurrent callers. */
    fun nextSeq(): Long

    /**
     * Ensure the next [nextSeq] is strictly greater than [seq]. Idempotent and thread-safe; used on
     * persistent-sink open to resume the counter above the highest seq already on disk, so total
     * order survives a restart (no seq reuse).
     */
    fun resumeAtLeast(seq: Long)
}

/**
 * Production [TimeSource]: system wall-clock for [now], an [AtomicLong] for [nextSeq]. The first
 * `nextSeq()` returns 1 (or `resumeAtLeast(max)+1` after a restart).
 */
class SystemTimeSource(startSeq: Long = 0L) : TimeSource {
    private val seq = AtomicLong(startSeq)

    override fun now(): Long = System.currentTimeMillis()

    override fun nextSeq(): Long = seq.incrementAndGet()

    override fun resumeAtLeast(seq: Long) {
        this.seq.updateAndGet { current -> if (seq > current) seq else current }
    }
}
