package com.tneff.cyppieagents.transport.mux

import com.tneff.cyppieagents.mux.YamuxFrame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * CYP-620 Increment 3 (scheduler) — the ONE tunnel writer's ordering policy. Many per-stream pumps produce outbound
 * frames concurrently; there is exactly one Noise tunnel to write them to. This scheduler decides the order, enforcing
 * two ratified invariants (§4.4 / §4.8.4):
 *
 *  - **Reserved control priority (§4.8.4, no-starvation).** Session control + lifecycle frames — WINDOW_UPDATE,
 *    GO_AWAY, PING/PONG, and stream-class-0 (control/lifecycle) DATA — go out **ahead** of all bulk DATA. A bulk data
 *    stream (e.g. an avatar transfer saturating its window) can therefore NEVER delay a lifecycle **STOP** behind
 *    megabytes of payload. This is the structural successor to CYP-610's REST/WS pool split: a first-class reservation,
 *    not a pool split.
 *  - **Fairness across data streams (§4.4, HoL).** Bulk DATA streams are served **round-robin** (one frame per stream
 *    per turn, then that stream rotates to the back), so no single high-rate stream monopolizes the tunnel and stalls
 *    the others behind it.
 *
 * **Where credit lives (NOT here):** per-stream send-window credit is the *session*'s concern (`YamuxSendWindow`). A
 * creditless stream's pump simply **blocks before it enqueues** (§4.4 block-the-pump: only that stream's pump, never
 * the tunnel), so the scheduler only ever holds frames that are already cleared to send — it stays pure ordering with
 * no credit coupling. "The writer skips a creditless stream in the interleave" (§4.4) is exactly this: a blocked pump
 * contributes nothing to serve.
 *
 * **Concurrency (§4.8.5, CYP-556).** Many producers ([enqueueControl]/[enqueueData]) + one consumer ([next]); all
 * queue mutation is serialized under [lock]. A CONFLATED [wakeup] signal parks [next] while nothing is ready and wakes
 * it on the next enqueue (or [close]) — no busy-spin. The classification of a stream as control-vs-data is the caller's
 * (the session reads streamClass); the scheduler just honors the two lanes it is handed.
 *
 * The classification of a stream as control-vs-data is the caller's (the session reads `streamClass`); the scheduler
 * just honors the two lanes.
 */
class MuxWriteScheduler {

    private val control = ArrayDeque<YamuxFrame>()
    /** Per data-stream FIFO queues. Iteration order == fairness order; serving rotates the served stream to the back. */
    private val dataQueues = LinkedHashMap<Long, ArrayDeque<YamuxFrame>>()
    private val lock = Mutex()
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private var closed = false

    /** Enqueue a session-control / stream-class-0 lifecycle frame. It jumps ahead of all bulk DATA. */
    suspend fun enqueueControl(frame: YamuxFrame) = lock.withLock {
        if (closed) return@withLock
        control.addLast(frame)
        wakeup.trySend(Unit)
    }

    /** Enqueue a bulk DATA frame for [streamId] (already cleared by that stream's send-window). FIFO per stream. */
    suspend fun enqueueData(streamId: Long, frame: YamuxFrame) = lock.withLock {
        if (closed) return@withLock
        dataQueues.getOrPut(streamId) { ArrayDeque() }.addLast(frame)
        wakeup.trySend(Unit)
    }

    /**
     * The next frame to write to the tunnel, in priority order (all pending control first, then round-robin over data
     * streams). Suspends while nothing is ready. Returns `null` only when the scheduler is [close]d **and** fully
     * drained — the tunnel-writer loop ends cleanly on `null`.
     */
    suspend fun next(): YamuxFrame? {
        while (true) {
            val picked = lock.withLock {
                val f = pickLocked()
                when {
                    f != null -> Pick.Ready(f)
                    closed -> Pick.Drained // closed and empty
                    else -> Pick.Empty // open but nothing ready
                }
            }
            when (picked) {
                is Pick.Ready -> return picked.frame
                Pick.Drained -> return null // closed and drained — end the writer loop
                Pick.Empty -> wakeup.receive() // park until an enqueue/close
            }
        }
    }

    /** Stop the scheduler: [next] drains what remains, then returns `null`. Idempotent. Wakes a parked [next]. */
    suspend fun close() = lock.withLock {
        closed = true
        wakeup.trySend(Unit)
    }

    /** Control first (reserved priority), else the front data stream round-robin (serve one, rotate it to the back). */
    private fun pickLocked(): YamuxFrame? {
        if (control.isNotEmpty()) return control.removeFirst()
        val it = dataQueues.entries.iterator()
        if (!it.hasNext()) return null
        val entry = it.next() // the least-recently-served stream (front of the LinkedHashMap)
        val frame = entry.value.removeFirst()
        it.remove() // pull it off the front...
        if (entry.value.isNotEmpty()) dataQueues[entry.key] = entry.value // ...and rotate it to the back if not empty
        return frame
    }

    /** The outcome of one pick attempt — a typed result so a real `GO_AWAY(INTERNAL_ERROR)` frame can never be
     *  mistaken for the "drained" sentinel (YamuxFrame.equals is content-based). */
    private sealed interface Pick {
        data class Ready(val frame: YamuxFrame) : Pick
        data object Drained : Pick
        data object Empty : Pick
    }
}
