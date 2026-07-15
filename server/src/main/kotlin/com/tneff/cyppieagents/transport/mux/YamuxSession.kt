package com.tneff.cyppieagents.transport.mux

import com.tneff.cyppieagents.mux.DEFAULT_INITIAL_WINDOW
import com.tneff.cyppieagents.mux.YamuxFlags
import com.tneff.cyppieagents.mux.YamuxFrame
import com.tneff.cyppieagents.mux.YamuxFrameDecoder
import com.tneff.cyppieagents.mux.YamuxGoAway
import com.tneff.cyppieagents.mux.YamuxProtocolException
import com.tneff.cyppieagents.mux.YamuxType
import com.tneff.cyppieagents.transport.BridgeSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * CYP-620 Increment 3 (step 4) — the multiplexed **session** over ONE Noise tunnel: it weaves the adapter
 * ([YamuxFrameLink]), the write scheduler ([MuxWriteScheduler]), and the per-stream state machines ([YamuxStream])
 * into a live mux. This is the demux boundary — and therefore the sole guardian of **no byte-bleed** (§4.8.2): every
 * inbound frame's payload lands in EXACTLY its own stream's loopback socket and no other's.
 *
 * **Two loops:**
 *  - READER (this coroutine, [run]) — `link.read()` → dispatch each frame by `streamId`/type. A `SYN` `DATA` opens a
 *    stream (its first payload byte is the `streamClass`, §4.2); `DATA`/`WINDOW_UPDATE` route to an existing stream;
 *    `FIN`/`RST` flags drive its lifecycle; `PING` is answered; inbound `GO_AWAY` ends the session. A malformed frame
 *    or a flow-control violation ([YamuxProtocolException]) → we emit `GO_AWAY(protocol-error)` and tear the tunnel
 *    down (fail-closed, never best-effort-continue).
 *  - WRITER (a child coroutine) — `scheduler.next()` → `link.write()`, honoring the reserved-control priority +
 *    round-robin fairness the scheduler encodes.
 *
 * **Fail-closed demux (§4.8.1/§4.8.2):** a `DATA`/`WINDOW_UPDATE` for an unknown/retired `streamId` is answered with a
 * per-stream `RST` (never routed into another stream — that would be a cross-stream data leak); a `SYN` beyond
 * [maxStreams] (the per-tunnel DoS envelope replacing the pool cap) is refused with a `RST`, the stream never opens.
 *
 * **Stream lifecycle ≠ tunnel lifecycle (§4.8.3):** a per-stream `RST`/full-close retires only that stream (this class
 * observes the local FIN/RST it emits via [StreamEmitter] and the remote FIN it dispatches, retiring the id when BOTH
 * halves have closed — so a fully-closed id frees a `maxStreams` slot and cannot leak). Only a tunnel drop (or a
 * protocol error) aborts every stream.
 *
 * This class is the hub (responder) side. The client mirror ([the `:app` `ClientMuxSession`, Dev5's lane]) speaks the
 * same `:core` codec; per the ratified recovery ruling, a carrier drop resets all streams and completes [run] — the
 * owning `RemoteHubSession` re-dials and starts a fresh session (this class never re-dials).
 */
class YamuxSession(
    private val link: YamuxFrameLink,
    private val scheduler: MuxWriteScheduler,
    /** Dials/holds the per-stream loopback socket for a newly opened stream. Prod = a `127.0.0.1` [BridgeSocket]. */
    private val sinkFactory: (streamId: Long, streamClass: Int) -> BridgeSocket,
    private val scope: CoroutineScope,
    private val maxStreams: Int = DEFAULT_MAX_STREAMS,
    private val initialSendWindow: Long = DEFAULT_INITIAL_WINDOW,
    private val maxRecvWindow: Long = DEFAULT_INITIAL_WINDOW,
    private val maxFrameLen: Int = YamuxFrameDecoder.DEFAULT_MAX_FRAME_LEN,
) : StreamEmitter {

    private val lock = Mutex() // guards the stream table + the half-close sets
    private val streams = HashMap<Long, YamuxStream>()
    private val localClosed = HashSet<Long>()  // this side sent FIN/RST for the id
    private val remoteClosed = HashSet<Long>() // the peer sent FIN/RST for the id
    private var tornDown = false

    /** Run the session until the tunnel closes, an inbound `GO_AWAY` arrives, or a protocol error tears it down. */
    suspend fun run() {
        val writer = scope.launch {
            try {
                while (true) {
                    val f = scheduler.next() ?: break
                    link.write(f)
                }
            } catch (_: Exception) {
                // link write failed (tunnel dead) — the reader loop will also end; nothing more to flush.
            }
        }
        try {
            reader@ while (true) {
                val frames = link.read() ?: break // tunnel/relay closed
                for (f in frames) {
                    if (f.type == YamuxType.GO_AWAY) break@reader // the peer is closing the session
                    dispatch(f)
                }
            }
        } catch (_: YamuxProtocolException) {
            // Malformed frame or flow-control violation → announce + fail closed.
            runCatching { link.write(YamuxFrame.goAway(YamuxGoAway.PROTOCOL_ERROR)) }
        } catch (_: Exception) {
            // Tunnel I/O error — treat as a drop.
        } finally {
            scheduler.close() // no more enqueues; next() drains what is queued then returns null
            runCatching { writer.join() } // let the writer flush already-queued frames (e.g. an RST) before we close
            teardown() // abort every remaining stream + close the link
        }
    }

    private suspend fun dispatch(f: YamuxFrame) {
        when (f.type) {
            YamuxType.PING -> if (f.isSyn) control(YamuxFrame.ping(f.length, YamuxFlags.ACK)) // reply PONG
            YamuxType.DATA -> if (f.isSyn) openStream(f) else routeExisting(f)
            YamuxType.WINDOW_UPDATE -> routeExisting(f)
            YamuxType.GO_AWAY -> {} // handled in the reader loop
        }
    }

    /** A `SYN` `DATA`: open a new stream. The first payload byte is the `streamClass`; the rest is its first `DATA`. */
    private suspend fun openStream(f: YamuxFrame) {
        val streamId = f.streamId
        val payload = f.payload
        if (payload.isEmpty()) throw YamuxProtocolException("SYN DATA for stream $streamId carries no streamClass byte")
        val streamClass = payload[0].toInt() and 0xFF
        val rest = if (payload.size > 1) payload.copyOfRange(1, payload.size) else YamuxFrame.EMPTY

        val opened = lock.withLock {
            when {
                streams.containsKey(streamId) -> null // duplicate SYN for a live id → refuse (fail-closed)
                streams.size >= maxStreams -> null    // DoS envelope (§4.6) → refuse, do not open
                else -> {
                    val sink = sinkFactory(streamId, streamClass)
                    YamuxStream(streamId, streamClass, sink, this, scope, initialSendWindow, maxRecvWindow, maxFrameLen)
                        .also { streams[streamId] = it }
                }
            }
        }
        if (opened == null) {
            // Refused → RST this id (fail-closed); never opens, never bleeds into another stream.
            scheduler.enqueueData(streamId, YamuxFrame.windowUpdate(streamId, 0, YamuxFlags.RST))
            return
        }
        opened.start()
        if (rest.isNotEmpty()) opened.onData(rest) // may throw on overshoot → GoAway (reader catch)
        if (f.isFin) { opened.onFin(); markRemoteClosed(streamId) }
    }

    /** Route a `DATA`/`WINDOW_UPDATE` for an existing stream; an unknown id is RST (never misrouted — no byte-bleed). */
    private suspend fun routeExisting(f: YamuxFrame) {
        val streamId = f.streamId
        val stream = lock.withLock { streams[streamId] }
        if (stream == null) {
            scheduler.enqueueData(streamId, YamuxFrame.windowUpdate(streamId, 0, YamuxFlags.RST))
            return
        }
        if (f.isRst) { retireOnReset(streamId); return }
        when (f.type) {
            YamuxType.DATA -> if (f.payload.isNotEmpty()) stream.onData(f.payload) // throws on overshoot → GoAway
            YamuxType.WINDOW_UPDATE -> if (f.length > 0) stream.onWindowUpdate(f.length) // throws on overflow → GoAway
            else -> {}
        }
        if (f.isFin) { stream.onFin(); markRemoteClosed(streamId) }
    }

    // ─── StreamEmitter (a stream's frames → the scheduler's two lanes) ──────────────────────────────────────────────

    /** A stream's own DATA/FIN/RST → the PER-STREAM lane (class-0 control streams → the priority lane); observe a
     *  local FIN/RST to drive retirement. */
    override suspend fun ordered(streamId: Long, streamClass: Int, frame: YamuxFrame) {
        if (streamClass == 0) scheduler.enqueueControl(frame) else scheduler.enqueueData(streamId, frame)
        if (frame.isFin || frame.isRst) lock.withLock { localClosed += streamId; maybeRetireLocked(streamId) }
    }

    /** A credit WINDOW_UPDATE we grant the peer → the priority control lane (prompt credit return). */
    override suspend fun control(frame: YamuxFrame) = scheduler.enqueueControl(frame)

    // ─── retirement / teardown ──────────────────────────────────────────────────────────────────────────────────

    private suspend fun markRemoteClosed(streamId: Long) = lock.withLock {
        remoteClosed += streamId
        maybeRetireLocked(streamId)
    }

    /** Retire the id once BOTH halves have closed — frees a `maxStreams` slot; the still-flowing direction (if any)
     *  finishes on its own pump before this fires (a half-closed stream is NOT retired). */
    private fun maybeRetireLocked(streamId: Long) {
        if (streamId in localClosed && streamId in remoteClosed) {
            streams.remove(streamId)?.close()
            localClosed -= streamId; remoteClosed -= streamId
        }
    }

    private suspend fun retireOnReset(streamId: Long) = lock.withLock {
        streams.remove(streamId)?.onReset()
        localClosed -= streamId; remoteClosed -= streamId
    }

    private suspend fun teardown() = lock.withLock {
        if (tornDown) return@withLock
        tornDown = true
        streams.values.forEach { it.abort() } // tunnel drop → every stream resets (§4.8.3)
        streams.clear(); localClosed.clear(); remoteClosed.clear()
        runCatching { link.close() }
    }

    /** Live stream count — the `maxStreams` occupancy. Test/observability. */
    suspend fun openStreamCount(): Int = lock.withLock { streams.size }

    companion object {
        /**
         * The per-tunnel concurrent-stream cap — the NEW per-operator DoS envelope that REPLACES the tunnel-pool cap
         * (§4.6). Excess opens are refused fail-closed (not queued). Proposed 64 (Dev5's pool-analysis finalizes the
         * value); it is a DoS-envelope decision → **Auftraggeber sign-off** (same posture as the CYP-611 cap bump), and
         * is to be single-sourced into `:core` alongside the window config (the CYP-613 follow-up).
         */
        const val DEFAULT_MAX_STREAMS: Int = 64
    }
}
