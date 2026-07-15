package com.tneff.cyppieagents.transport.mux

import com.tneff.cyppieagents.mux.DEFAULT_INITIAL_WINDOW
import com.tneff.cyppieagents.mux.YamuxFlags
import com.tneff.cyppieagents.mux.YamuxFrame
import com.tneff.cyppieagents.mux.YamuxFrameDecoder
import com.tneff.cyppieagents.mux.YamuxProtocolException
import com.tneff.cyppieagents.mux.YamuxSendWindow
import com.tneff.cyppieagents.mux.YamuxRecvWindow
import com.tneff.cyppieagents.transport.BridgeSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * CYP-620 Increment 3 (step 3) — ONE multiplexed stream's state machine. A stream carries one HTTP/WS connection as a
 * byte-stream, to/from a per-stream loopback [BridgeSocket] (the hub's existing Ktor connector — routes UNCHANGED).
 * The session ([YamuxSession], step 4) demuxes inbound frames to the right stream's `on*` handlers and creates one
 * [YamuxStream] per `SYN`; this class owns everything below the demux: flow control, the two pumps, and the
 * per-stream FIN/RST discipline.
 *
 * **Direction map (hub = responder):** the peer's request bytes arrive as inbound `DATA` → written UPSTREAM to the
 * loopback socket (to the route); the route's response is read DOWNSTREAM off the socket → emitted as outbound `DATA`.
 *
 * **Flow control (G1–G4, from `:core`):**
 *  - RECV ([recvWindow]) — `onData` credits the window (`onReceived`, fail-closed if the peer OVERSHOT), buffers the
 *    payload, and the **upstream** pump returns credit as it DRAINS to the socket (`onDrained` → a `WINDOW_UPDATE`).
 *    Returning credit on DRAIN (not enqueue) is the deadlock-free rule.
 *  - SEND ([sendWindow]) — the **downstream** pump may only emit `DATA` while it holds credit; at 0 it BLOCKS (only
 *    this stream — block-the-pump), waking on an inbound `WINDOW_UPDATE` ([onWindowUpdate]).
 *
 * **Per-stream lifecycle ≠ tunnel lifecycle (§4.8.3, the CYP-609/618 discipline PER STREAM):**
 *  - inbound `FIN` ([onFin]) → the peer will send no more request bytes → after draining, the socket is **half-closed
 *    gracefully** (`closeGraceful` = `shutdownOutput` FIN) so the route sees a clean input-EOF (never a reset);
 *  - inbound `RST` ([onReset]) or any protocol/socket error → the socket is **reset** (abortive) — the truncation
 *    guard, per stream;
 *  - a socket EOF downstream (route closed its response) → emit a `FIN` frame so the peer sees a clean stream end.
 *  None of these touch the tunnel or any other stream.
 *
 * **Ordering (§4.4):** a stream's own outbound `DATA`/`FIN`/`RST` go through the emitter's PER-STREAM lane (FIFO —
 * a `FIN` can never overtake the last `DATA` and truncate). Only the credit `WINDOW_UPDATE` we grant (a different
 * direction) rides the priority control lane.
 */
class YamuxStream(
    val streamId: Long,
    /** 0 = control/lifecycle · 1 = agent-ws · 2 = singleton-ws · 3 = rest (§4.2). Routes the emitter's lane. */
    val streamClass: Int,
    private val sink: BridgeSocket,
    private val emitter: StreamEmitter,
    private val scope: CoroutineScope,
    initialSendWindow: Long = DEFAULT_INITIAL_WINDOW,
    maxRecvWindow: Long = DEFAULT_INITIAL_WINDOW,
    private val maxFrameLen: Int = YamuxFrameDecoder.DEFAULT_MAX_FRAME_LEN,
) {
    private val lock = Mutex() // guards BOTH windows (O(1) ops; pump consumes/reader grants, reader receives/pump drains)
    private val sendWindow = YamuxSendWindow(initialSendWindow)
    private val recvWindow = YamuxRecvWindow(maxRecvWindow)

    /** Wakes the downstream pump when the peer grants send credit (CONFLATED — a missed-while-busy grant is not lost). */
    private val creditSignal = Channel<Unit>(Channel.CONFLATED)
    /** Inbound request payloads awaiting the upstream drain. Bounded in practice by the recv window (≤ maxRecvWindow). */
    private val inbound = Channel<ByteArray>(Channel.UNLIMITED)

    /** The stream's own lifecycle job — a child of the session scope, so a tunnel-teardown cancels every stream, and
     *  cancelling THIS stream (reset/close) stops only its two pumps without touching the session or other streams. */
    private val streamJob = Job(scope.coroutineContext.job)
    private val streamScope = CoroutineScope(scope.coroutineContext + streamJob)

    /** Start the two pumps. Call once at stream open. */
    fun start() {
        streamScope.launch { upstreamPump() }
        streamScope.launch { downstreamPump() }
    }

    // ─── inbound frame handlers (called by the session's single reader loop) ────────────────────────────────────────

    /** Inbound `DATA` payload. Credits the recv window (fail-closed on overshoot → the session tears the tunnel),
     *  then hands the bytes to the upstream drain. */
    suspend fun onData(payload: ByteArray) {
        lock.withLock { recvWindow.onReceived(payload.size.toLong()) } // throws YamuxProtocolException on overshoot
        inbound.send(payload)
    }

    /** Inbound `WINDOW_UPDATE`: the peer granted us more send credit (fail-closed on u32 overflow). Wakes the pump. */
    suspend fun onWindowUpdate(delta: Long) {
        lock.withLock { sendWindow.grant(delta) } // throws on negative / overflow
        creditSignal.trySend(Unit)
    }

    /** Inbound `FIN`: the peer will send no more request bytes. Close the inbound queue → the upstream pump drains what
     *  remains, then half-closes the socket gracefully (route sees a clean input-EOF). */
    fun onFin() {
        inbound.close()
    }

    /** Inbound `RST` (or a protocol error the session maps to this stream): abort the socket + stop the pumps. */
    fun onReset() {
        sink.reset()
        inbound.close(YamuxProtocolException("stream $streamId reset by peer"))
        streamJob.cancel()
    }

    /** Tunnel-level teardown (the ONE tunnel dropped → all streams reset). Abortive, per §4.8.3. */
    fun abort() = onReset()

    /** Stop the stream's pumps (release resources). Idempotent. Used at teardown / when a stream fully retires. */
    fun close() {
        streamJob.cancel()
    }

    // ─── pumps ──────────────────────────────────────────────────────────────────────────────────────────────────

    /** UPSTREAM: inbound request payloads → the loopback socket; return recv credit on DRAIN (deadlock-free). */
    private suspend fun upstreamPump() {
        try {
            for (payload in inbound) { // ends when onFin()/onReset() closes the channel
                sink.write(payload) // backpressure from a slow route propagates here (only this stream)
                val delta = lock.withLock { recvWindow.onDrained(payload.size.toLong()) }
                if (delta > 0) emitter.control(YamuxFrame.windowUpdate(streamId, delta)) // credit back, priority lane
            }
            // Inbound closed cleanly (FIN drained): half-close the socket's write side so the route sees a clean EOF.
            sink.closeGraceful()
        } catch (_: Exception) {
            // onReset() closed the channel with a cause, or a socket write failed → abortive (truncation guard).
            sink.reset()
        }
    }

    /** DOWNSTREAM: the route's response bytes → outbound `DATA` (credit-gated); a socket EOF → a `FIN` frame. */
    private suspend fun downstreamPump() {
        try {
            while (true) {
                // Block until we hold send credit (only THIS stream blocks); then read at most `credit`/`maxFrameLen`.
                var avail = lock.withLock { sendWindow.available() }
                while (avail == 0L) {
                    creditSignal.receive()
                    avail = lock.withLock { sendWindow.available() }
                }
                val cap = minOf(avail, maxFrameLen.toLong()).toInt()
                val buf = ByteArray(cap)
                val n = sink.read(buf)
                if (n < 0) { // route closed its response cleanly → graceful stream close
                    emitter.ordered(streamId, streamClass, YamuxFrame.data(streamId, YamuxFrame.EMPTY, YamuxFlags.FIN))
                    break
                }
                if (n == 0) continue
                lock.withLock { sendWindow.consume(n.toLong()) } // n ≤ cap ≤ avail; reader only GROWS credit → safe
                emitter.ordered(streamId, streamClass, YamuxFrame.data(streamId, buf.copyOf(n)))
            }
        } catch (_: Exception) {
            // A socket read error (peer-reset, etc.) → signal an abrupt stream reset to the peer. Same lane as DATA
            // so it never overtakes an earlier DATA frame in flight.
            runCatching {
                emitter.ordered(streamId, streamClass, YamuxFrame.windowUpdate(streamId, 0, YamuxFlags.RST))
            }
        }
    }
}

/**
 * The session's frame sink for a stream. Two lanes so the [MuxWriteScheduler] priority/fairness invariants hold:
 *  - [ordered] — the stream's own `DATA`/`FIN`/`RST`: per-stream FIFO (a data stream → the round-robin data lane; a
 *    class-0 control stream → the control lane). A `FIN` must never overtake the last `DATA` (truncation).
 *  - [control] — a credit `WINDOW_UPDATE` we grant the peer (a different direction): the priority control lane, so
 *    credit is returned promptly and never stuck behind bulk data.
 */
interface StreamEmitter {
    suspend fun ordered(streamId: Long, streamClass: Int, frame: YamuxFrame)
    suspend fun control(frame: YamuxFrame)
}
