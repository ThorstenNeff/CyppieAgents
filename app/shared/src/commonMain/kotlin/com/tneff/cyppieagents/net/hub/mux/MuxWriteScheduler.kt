package com.tneff.cyppieagents.net.hub.mux

import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Priority lanes = the 4 stream classes ([StreamClass.wire]: 0=CONTROL highest … 3=REST lowest). */
internal const val PRIORITY_LANES: Int = 4

/**
 * CYP-620 (client half of **control-frame non-starvation** — the peer of the server's `MuxWriteScheduler`): the write
 * side of the mux. A FIFO write-mutex over `carrier.send` lets a bulk REST stream's queued frames delay a lifecycle
 * STOP; instead, frames are enqueued into per-[StreamClass] **priority lanes** and a single writer drains
 * **highest-priority-first**. Paired with the send-side frame-size-cap (a bulk DATA frame is chunked ≤ cap), a CONTROL
 * frame enqueued mid-bulk is written before the remaining bulk chunks — so `[stop]` never waits behind avatar bulk.
 *
 * The single writer coroutine also SERIALIZES `carrier.send` (one Noise transport message at a time) — it *is* the
 * one writer, replacing the old write-mutex. Enqueue is cheap + non-suspending on the hot path (lane push + signal).
 */
class MuxWriteScheduler(
    private val carrier: NoiseTunnel,
    scope: CoroutineScope,
) {
    private val lanes = Array(PRIORITY_LANES) { ArrayDeque<ByteArray>() }
    private val lock = Mutex()
    private val signal = Channel<Unit>(Channel.CONFLATED) // wakes the writer when a lane gains a frame
    private var closed = false
    private val writer: Job = scope.launch { writeLoop() }

    /** Enqueue an already-encoded frame at [priority] (0=highest=CONTROL … 3=REST); out-of-range clamps to the lowest lane. */
    suspend fun enqueue(bytes: ByteArray, priority: Int) {
        val lane = priority.coerceIn(0, PRIORITY_LANES - 1)
        lock.withLock { if (!closed) lanes[lane].addLast(bytes) }
        signal.trySend(Unit)
    }

    private suspend fun writeLoop() {
        while (!closed) {
            val next = lock.withLock { pickHighest() }
            if (next == null) { signal.receive(); continue } // nothing pending → wait for an enqueue
            carrier.send(next)                               // serialized: this is the ONLY writer
        }
    }

    /** Drain the highest-priority non-empty lane first (CONTROL before AGENT_WS before SINGLETON_WS before REST). */
    private fun pickHighest(): ByteArray? {
        for (lane in lanes) lane.removeFirstOrNull()?.let { return it }
        return null
    }

    /** Stop the writer (idempotent). The carrier itself is closed by the session (it owns the tunnel). */
    suspend fun close() {
        closed = true
        signal.trySend(Unit)
        writer.cancel()
    }
}
