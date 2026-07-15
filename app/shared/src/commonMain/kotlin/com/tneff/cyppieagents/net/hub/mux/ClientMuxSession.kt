package com.tneff.cyppieagents.net.hub.mux

import com.tneff.cyppieagents.mux.DEFAULT_INITIAL_WINDOW
import com.tneff.cyppieagents.mux.MuxHello
import com.tneff.cyppieagents.mux.YamuxFrame
import com.tneff.cyppieagents.mux.YamuxFrameCodec
import com.tneff.cyppieagents.mux.YamuxFrameDecoder
import com.tneff.cyppieagents.mux.YamuxFlags
import com.tneff.cyppieagents.mux.YamuxGoAway
import com.tneff.cyppieagents.mux.YamuxRecvWindow
import com.tneff.cyppieagents.mux.YamuxSendWindow
import com.tneff.cyppieagents.mux.YamuxType
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.pool.TunnelLane
import com.tneff.cyppieagents.net.logMux
import com.tneff.cyppieagents.net.muxCarrierId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** CYP-620 — the client-side per-operator stream cap (pinned start param). At the cap `openStream` fails closed (the
 *  transport RSTs, or post-CYP-619 holds+retries) — the DoS envelope is now on STREAMS over one tunnel, not tunnels. */
const val MAX_STREAMS: Int = 64

/** Session-level control frames (PING pong, GoAway) ride the highest priority lane (== [StreamClass.CONTROL].wire). */
internal const val SESSION_CONTROL_PRIORITY: Int = 0

/**
 * CYP-620 (client wiring) — ONE logical yamux stream, presented to the loopback bridge as a [NoiseTunnel] so the
 * bridge/transport pump a **stream** with zero interface change (a stream *is* a tunnel to the bridge).
 *
 * **Cross-stream isolation (M2):** the inbound buffer is per-stream + [Channel.UNLIMITED], so the session's single
 * read-loop delivers to it **without ever suspending** — a slow/stalled consumer here can never stall the loop and so
 * can never starve a sibling stream (no head-of-line). The buffer is bounded not by blocking the loop but by the
 * per-stream **receive window**: `:core`'s [YamuxRecvWindow] fails closed on a peer overshoot, and credit is returned
 * (`WINDOW_UPDATE`) only as the app **DRAINS** (deadlock-free, §G1).
 *
 * **Adapter-backpressure (send):** [send] may only emit `DATA` while it holds send-credit ([YamuxSendWindow]); at 0 it
 * **blocks THIS stream** (never the tunnel) until an inbound `WINDOW_UPDATE` grants more — the client half of
 * per-stream-flow-control (not per-tunnel-H7).
 */
class MuxedStream internal constructor(
    val streamId: Long,
    val streamClass: StreamClass,
    private val session: ClientMuxSession,
    override val handshakeHash: ByteArray,
    initialWindow: Long,
    private val frameSizeCap: Int,
) : NoiseTunnel {

    internal val inbound: Channel<ByteArray> = Channel(Channel.UNLIMITED)
    private val sendWindow = YamuxSendWindow(initialWindow)
    private val recvWindow = YamuxRecvWindow(initialWindow)
    private val windowLock = Mutex()                         // guards sendWindow + recvWindow (concurrent send/dispatch)
    private val creditSignal = Channel<Unit>(Channel.CONFLATED) // wakes a blocked sender when the peer grants credit
    private var closed = false

    override suspend fun send(plaintext: ByteArray) {
        var off = 0
        while (off < plaintext.size && !closed) {
            // Take as much send-credit as is available now (per-stream backpressure — never the tunnel), but never
            // more than [frameSizeCap] per DATA frame — so a bulk stream is chunked and a CONTROL frame can preempt
            // BETWEEN its chunks (the control-non-starvation guarantee, paired with the priority write-scheduler).
            val take = windowLock.withLock {
                val avail = sendWindow.available()
                if (avail <= 0L) 0 else minOf(avail, (plaintext.size - off).toLong(), frameSizeCap.toLong()).toInt()
                    .also { if (it > 0) sendWindow.consume(it.toLong()) }
            }
            if (take == 0) { creditSignal.receive(); continue } // window empty ⇒ block until a WINDOW_UPDATE grants
            session.writeFrame(YamuxFrame.data(streamId, plaintext.copyOfRange(off, off + take)), streamClass.wire)
            off += take
        }
    }

    /** Next inbound app-bytes for THIS stream, or `null` when the peer FIN/RST'd it or the session tore down. Draining
     *  returns recv-window credit to the peer (a `WINDOW_UPDATE`) per the deadlock-free on-drain rule. */
    override suspend fun receive(): ByteArray? {
        val bytes = inbound.receiveCatching().getOrNull() ?: return null
        val delta = windowLock.withLock { recvWindow.onDrained(bytes.size.toLong()) }
        if (delta > 0) session.writeFrame(YamuxFrame.windowUpdate(streamId, delta), streamClass.wire)
        return bytes
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        creditSignal.trySend(Unit) // unblock any waiting sender so it observes `closed` and returns
        session.closeStream(streamId, streamClass.wire) // FIN + unregister (at this stream's priority)
    }

    /** Session read-loop → account the peer's spend against our recv window (overshoot ⇒ fail closed), then deliver to
     *  this stream's isolated buffer. Never suspends the loop (UNLIMITED) → M2. */
    internal suspend fun deliver(payload: ByteArray) {
        windowLock.withLock { recvWindow.onReceived(payload.size.toLong()) } // throws on overshoot → loop fails closed
        inbound.send(payload)
    }

    /** Inbound `WINDOW_UPDATE`: the peer credited our send window → wake a blocked sender. */
    internal suspend fun onWindowGrant(delta: Long) {
        windowLock.withLock { sendWindow.grant(delta) }
        creditSignal.trySend(Unit)
    }

    /** Peer FIN/RST or session teardown → close the buffer so [receive] returns `null` (fail-closed EOF). */
    internal fun remoteClosed() {
        closed = true
        creditSignal.trySend(Unit)
        inbound.close()
    }
}

/**
 * CYP-620 — the client mux **session**: wraps ONE authenticated [NoiseTunnel] (the shared carrier) and multiplexes N
 * logical [MuxedStream]s over it via the shared `:core` yamux codec + window machine (the bilateral wire + flow-control
 * are compiler-enforced, not reimplemented). Client opens ODD stream ids; the first SYN-payload byte is the
 * [StreamClass] (the hub's `:server` scheduler honors the same 4 values for QoS priority).
 *
 * **Fail-closed:** a malformed frame OR a recv-window overshoot throws → the loop GoAways `PROTOCOL_ERROR` + tears down
 * (no best-effort skip → a stray payload can never land in the wrong stream's socket). The [MuxWriteScheduler] is the
 * ONE writer (serializes `carrier.send`) AND drains CONTROL ahead of bulk (non-starvation); the read-loop is the sole
 * reader (demux by streamId).
 */
class ClientMuxSession(
    private val carrier: NoiseTunnel,
    private val scope: CoroutineScope,
    private val maxStreams: Int = MAX_STREAMS,
    private val initialWindow: Long = DEFAULT_INITIAL_WINDOW,
    maxFrameLen: Int = YamuxFrameDecoder.DEFAULT_MAX_FRAME_LEN,
    private val frameSizeCap: Int = YamuxFrameDecoder.DEFAULT_MAX_FRAME_LEN,
) {
    private val decoder = YamuxFrameDecoder(maxFrameLen)
    private val streams = mutableMapOf<Long, MuxedStream>()
    private val lock = Mutex()       // guards [streams] + [nextId]
    // CYP-620 control-non-starvation: the priority write-scheduler REPLACES the FIFO write-mutex — it is the ONE writer
    // (serializes carrier.send) AND drains CONTROL frames ahead of bulk so a lifecycle STOP never waits behind REST bulk.
    private val scheduler = MuxWriteScheduler(carrier, scope)
    private var nextId = 1L          // client opens ODD ids (server = even), never reused within a session
    private var closed = false
    private var readJob: Job? = null
    // CYP-622 G7 hello gate: openStream awaits this so no yamux SYN precedes the hello. true = handshake ok; false =
    // fail-closed (server-hello mismatch/EOF, or teardown) ⇒ every openStream returns null (no stream on a bad peer).
    private val helloComplete = CompletableDeferred<Boolean>()
    private val carrierId = muxCarrierId(carrier.handshakeHash) // opaque, non-secret — correlates VM/session mux logs

    /** Convenience: launch [run] in [scope] (fire-and-forget) — for a consumer/test that doesn't await the lifecycle. */
    fun start() {
        if (readJob == null) readJob = scope.launch { run() }
    }

    /**
     * CYP-620 ①-ruling — drive the demux read-loop until the carrier drops (EOF/error), then reset ALL streams and
     * RETURN. The drop is **surfaced** to the caller ([com.tneff.cyppieagents.net.hub.remote.RemoteHubSession]), never
     * swallowed (mirrors the server `YamuxSession` returning on link EOF). This session is a **pure consumer of ONE
     * given tunnel** — it dials/handshakes/authenticates nothing; `RemoteHubSession` owns the carrier lifecycle and,
     * observing this `run()` end, re-dials + spans a FRESH session. **Stateless-across-carriers (§4.8.3):** a dropped
     * carrier resets every stream (no zombie on a dead tunnel = fail-closed); no stream is preserved — "no data loss"
     * is durable cursors (`?since=<seq>`) + the G5 idempotency-key on re-open, not stream carry-over.
     */
    suspend fun run() {
        try {
            // CYP-622 G7 mode/version hello FIRST (§4.8.10), before ANY yamux frame. Our hello is the FIRST carrier.send
            // (the priority scheduler's writer is idle until an openStream SYN is enqueued, and openStream is gated on
            // [helloComplete] below — so nothing races this send). Then receive + verify the server's hello (message-
            // framed, mirroring the server MuxBridge: peer-hello → verify → own-hello). A mismatch/EOF is fail-closed:
            // NO yamux loop, NO streams — run() returns, and the caller's reportDropped() re-dials (the existing drop path).
            carrier.send(MuxHello.ENCODED)
            logMux("hello", "G7 hello sent carrier=$carrierId mode=${MuxHello.MODE_MUX} version=${MuxHello.VERSION}")
            val peerHello = carrier.receive()
            if (peerHello == null || !MuxHello.verify(peerHello)) {
                logMux("hello", "G7 peer hello rejected carrier=$carrierId reason=${MuxHello.rejectReason(peerHello)} → fail-closed, no streams")
                helloComplete.complete(false) // unblock any waiting openStream → it fails closed
                return
            }
            logMux("hello", "G7 peer hello verified carrier=$carrierId → mux ready")
            helloComplete.complete(true)      // gate opens: openStream may now SYN
            while (!closed) {
                val chunk = carrier.receive() ?: break // carrier EOF → return, surfacing the drop (never swallow)
                for (frame in decoder.feed(chunk)) dispatch(frame) // throws on malformed/overshoot → fail-closed below
            }
            logMux("carrier", "carrier dropped carrier=$carrierId → run() ended → streams reset → reportDropped → re-dial")
        } catch (_: Throwable) {
            runCatching { writeFrame(YamuxFrame.goAway(YamuxGoAway.PROTOCOL_ERROR), SESSION_CONTROL_PRIORITY) } // fail-closed
        } finally {
            if (!helloComplete.isCompleted) helloComplete.complete(false) // any openStream waiter fails closed on teardown
            teardown()                        // all streams reset (stateless-across-carriers)
            runCatching { scheduler.close() } // stop the priority writer
        }
    }

    /**
     * Open a new stream for [streamClass], or `null` fail-closed (at [maxStreams] or after [close]). Sends a `SYN`
     * frame whose first payload byte is the streamClass (pinned contract) — usable optimistically (yamux allows data
     * before the peer `ACK`).
     */
    suspend fun openStream(streamClass: StreamClass): MuxedStream? {
        // CYP-622: gate on the G7 hello — no SYN may precede a verified hello. A failed handshake ⇒ null (fail-closed).
        if (!helloComplete.await()) {
            logMux("acquire", "openStream(class=${streamClass.wire}) → null (G7 hello fail-closed) carrier=$carrierId")
            return null
        }
        val stream = lock.withLock {
            if (closed) return null
            if (streams.size >= maxStreams) {
                logMux("acquire", "openStream(class=${streamClass.wire}) → null (maxStreams=$maxStreams) carrier=$carrierId")
                return null // fail-closed at the per-operator stream cap
            }
            val id = nextId
            nextId += 2
            MuxedStream(id, streamClass, this, carrier.handshakeHash, initialWindow, frameSizeCap).also { streams[id] = it }
        }
        writeFrame(YamuxFrame.data(stream.streamId, byteArrayOf(streamClass.wireByte), flags = YamuxFlags.SYN), streamClass.wire)
        logMux("acquire", "openStream(class=${streamClass.wire}) → stream ${stream.streamId} carrier=$carrierId")
        return stream
    }

    /** Enqueue a frame at [priority] (the stream's [StreamClass.wire]; session-control frames use [SESSION_CONTROL_PRIORITY]). */
    internal suspend fun writeFrame(frame: YamuxFrame, priority: Int) {
        if (closed) return
        scheduler.enqueue(YamuxFrameCodec.encode(frame), priority)
    }

    internal suspend fun closeStream(id: Long, priority: Int) {
        val existed = lock.withLock { streams.remove(id) != null }
        if (existed) writeFrame(YamuxFrame(YamuxType.WINDOW_UPDATE, YamuxFlags.FIN, id, 0L), priority) // header-only FIN
    }

    private suspend fun dispatch(f: YamuxFrame) {
        when (f.type) {
            YamuxType.DATA -> {
                val s = lock.withLock { streams[f.streamId] }
                // Deliver to THIS stream's isolated buffer only (M2). deliver() accounts the recv window (overshoot ⇒
                // throws ⇒ the loop fails closed) then enqueues without suspending.
                if (s != null && f.payload.isNotEmpty()) s.deliver(f.payload)
                if (f.isFin || f.isRst) streamRemoteClosed(f.streamId)
            }
            YamuxType.WINDOW_UPDATE -> {
                if (f.isFin || f.isRst) {
                    streamRemoteClosed(f.streamId)
                } else {
                    // A flow-control credit for our send window → wake a blocked sender on that stream.
                    lock.withLock { streams[f.streamId] }?.onWindowGrant(f.length)
                }
            }
            YamuxType.PING -> if (f.isSyn) writeFrame(YamuxFrame.ping(f.length, YamuxFlags.ACK), SESSION_CONTROL_PRIORITY) // pong
            YamuxType.GO_AWAY -> closed = true // peer teardown → the loop exits next check
        }
    }

    private suspend fun streamRemoteClosed(id: Long) {
        val s = lock.withLock { streams.remove(id) }
        if (s != null) {
            logMux("stream", "stream $id peer FIN/RST carrier=$carrierId → closed")
            s.remoteClosed()
        }
    }

    private suspend fun teardown() {
        closed = true
        val all = lock.withLock { streams.values.toList().also { streams.clear() } }
        all.forEach { it.remoteClosed() }
    }

    /** Q5 teardown: GoAway(normal) + close every stream + close the carrier. Idempotent. */
    suspend fun close() {
        if (!closed) runCatching { writeFrame(YamuxFrame.goAway(YamuxGoAway.NORMAL), SESSION_CONTROL_PRIORITY) }
        closed = true
        readJob?.cancel()
        teardown()
        // Graceful: let the writer FLUSH the enqueued GoAway to the still-live carrier BEFORE we close it (CYP-609
        // clean-close). (run()'s finally uses the abrupt scheduler.close() — there the carrier is already dead.)
        runCatching { scheduler.drainAndClose() }
        runCatching { carrier.close() }
    }

    internal fun liveStreamCount(): Int = streams.size // test-visibility
}

/**
 * CYP-620 — the mux equivalent of `PooledTunnelSource` (which it replaces): hands out a [MuxedStream] per loopback
 * connection over the ONE shared [ClientMuxSession], instead of a distinct Noise tunnel per connection. `acquire`
 * returns a stream (presented as a `NoiseTunnel` to the bridge) or `null` fail-closed at [MAX_STREAMS] / after close
 * (the transport then RSTs or — post-CYP-619 — holds+retries for a stream slot). The per-operator DoS envelope is now
 * bounded by STREAM count over one tunnel, decoupled from tunnel count (the CYP-620 supply<demand fix).
 */
class MuxedStreamSource(private val session: ClientMuxSession) {
    suspend fun acquire(streamClass: StreamClass): MuxedStream? = session.openStream(streamClass)

    /**
     * CYP-620 minimal-cutover — acquire a stream for the transport's [TunnelLane] (the 2-lane→stream bridge the current
     * `acquire(lane)` transport injects; the fine agent-ws/singleton-ws split is the 4-acceptor refinement, CYP-621).
     * **CAUTION-1 (§4.8.4, non-negotiable):** [TunnelLane.CONTROL] → [StreamClass.CONTROL] (wire 0) — that reserved-
     * priority class IS the CYP-616 break-glass (the stop/restart fix); any other class loses it.
     */
    suspend fun acquire(lane: TunnelLane): MuxedStream? = session.openStream(laneToStreamClass(lane))

    suspend fun close() = session.close()

    companion object {
        /** The 2-lane→streamClass mapping. CAUTION-1: CONTROL MUST be wire 0 (break-glass); DATA collapses to one data class. */
        internal fun laneToStreamClass(lane: TunnelLane): StreamClass = when (lane) {
            TunnelLane.CONTROL -> StreamClass.CONTROL   // MUST be wire 0 (CYP-616 break-glass / stop-restart fix)
            TunnelLane.DATA -> StreamClass.AGENT_WS     // one data class (the fine agent/singleton split = CYP-621)
        }
    }
}
