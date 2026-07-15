package com.tneff.cyppieagents.net.hub.mux

import com.tneff.cyppieagents.mux.DEFAULT_INITIAL_WINDOW
import com.tneff.cyppieagents.mux.YamuxFrame
import com.tneff.cyppieagents.mux.YamuxFrameCodec
import com.tneff.cyppieagents.mux.YamuxFrameDecoder
import com.tneff.cyppieagents.mux.YamuxFlags
import com.tneff.cyppieagents.mux.YamuxGoAway
import com.tneff.cyppieagents.mux.YamuxRecvWindow
import com.tneff.cyppieagents.mux.YamuxSendWindow
import com.tneff.cyppieagents.mux.YamuxType
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** CYP-620 — the client-side per-operator stream cap (pinned start param). At the cap `openStream` fails closed (the
 *  transport RSTs, or post-CYP-619 holds+retries) — the DoS envelope is now on STREAMS over one tunnel, not tunnels. */
const val MAX_STREAMS: Int = 64

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
            // Take as much send-credit as is available now (per-stream backpressure — never the tunnel).
            val take = windowLock.withLock {
                val avail = sendWindow.available()
                if (avail <= 0L) 0 else minOf(avail, (plaintext.size - off).toLong()).toInt()
                    .also { if (it > 0) sendWindow.consume(it.toLong()) }
            }
            if (take == 0) { creditSignal.receive(); continue } // window empty ⇒ block until a WINDOW_UPDATE grants
            session.writeFrame(YamuxFrame.data(streamId, plaintext.copyOfRange(off, off + take)))
            off += take
        }
    }

    /** Next inbound app-bytes for THIS stream, or `null` when the peer FIN/RST'd it or the session tore down. Draining
     *  returns recv-window credit to the peer (a `WINDOW_UPDATE`) per the deadlock-free on-drain rule. */
    override suspend fun receive(): ByteArray? {
        val bytes = inbound.receiveCatching().getOrNull() ?: return null
        val delta = windowLock.withLock { recvWindow.onDrained(bytes.size.toLong()) }
        if (delta > 0) session.writeFrame(YamuxFrame.windowUpdate(streamId, delta))
        return bytes
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        creditSignal.trySend(Unit) // unblock any waiting sender so it observes `closed` and returns
        session.closeStream(streamId) // FIN + unregister
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
 * (no best-effort skip → a stray payload can never land in the wrong stream's socket). One write-mutex serializes
 * `carrier.send`; the read-loop is the sole reader (demux by streamId).
 */
class ClientMuxSession(
    private val carrier: NoiseTunnel,
    private val scope: CoroutineScope,
    private val maxStreams: Int = MAX_STREAMS,
    private val initialWindow: Long = DEFAULT_INITIAL_WINDOW,
    maxFrameLen: Int = YamuxFrameDecoder.DEFAULT_MAX_FRAME_LEN,
) {
    private val decoder = YamuxFrameDecoder(maxFrameLen)
    private val streams = mutableMapOf<Long, MuxedStream>()
    private val lock = Mutex()       // guards [streams] + [nextId]
    private val writeMutex = Mutex() // serializes carrier.send (one Noise transport message at a time)
    private var nextId = 1L          // client opens ODD ids (server = even), never reused within a session
    private var closed = false
    private var readJob: Job? = null

    /** Start the demux read-loop (idempotent). */
    fun start() {
        if (readJob == null) readJob = scope.launch { readLoop() }
    }

    /**
     * Open a new stream for [streamClass], or `null` fail-closed (at [maxStreams] or after [close]). Sends a `SYN`
     * frame whose first payload byte is the streamClass (pinned contract) — usable optimistically (yamux allows data
     * before the peer `ACK`).
     */
    suspend fun openStream(streamClass: StreamClass): MuxedStream? {
        val stream = lock.withLock {
            if (closed) return null
            if (streams.size >= maxStreams) return null // fail-closed at the per-operator stream cap
            val id = nextId
            nextId += 2
            MuxedStream(id, streamClass, this, carrier.handshakeHash, initialWindow).also { streams[id] = it }
        }
        writeFrame(YamuxFrame.data(stream.streamId, byteArrayOf(streamClass.wireByte), flags = YamuxFlags.SYN))
        return stream
    }

    internal suspend fun writeFrame(frame: YamuxFrame) {
        if (closed) return
        val bytes = YamuxFrameCodec.encode(frame)
        writeMutex.withLock { carrier.send(bytes) }
    }

    internal suspend fun closeStream(id: Long) {
        val existed = lock.withLock { streams.remove(id) != null }
        if (existed) writeFrame(YamuxFrame(YamuxType.WINDOW_UPDATE, YamuxFlags.FIN, id, 0L)) // header-only FIN
    }

    private suspend fun readLoop() {
        try {
            while (!closed) {
                val chunk = carrier.receive() ?: break // carrier closed/EOF → session ends
                for (frame in decoder.feed(chunk)) dispatch(frame) // throws on malformed/overshoot → caught below
            }
        } catch (_: Throwable) {
            runCatching { writeFrame(YamuxFrame.goAway(YamuxGoAway.PROTOCOL_ERROR)) } // fail-closed
        } finally {
            teardown()
        }
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
            YamuxType.PING -> if (f.isSyn) writeFrame(YamuxFrame.ping(f.length, YamuxFlags.ACK)) // pong
            YamuxType.GO_AWAY -> closed = true // peer teardown → the loop exits next check
        }
    }

    private suspend fun streamRemoteClosed(id: Long) {
        lock.withLock { streams.remove(id) }?.remoteClosed()
    }

    private suspend fun teardown() {
        closed = true
        val all = lock.withLock { streams.values.toList().also { streams.clear() } }
        all.forEach { it.remoteClosed() }
    }

    /** Q5 teardown: GoAway(normal) + close every stream + close the carrier. Idempotent. */
    suspend fun close() {
        if (!closed) runCatching { writeFrame(YamuxFrame.goAway(YamuxGoAway.NORMAL)) }
        closed = true
        readJob?.cancel()
        teardown()
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
    suspend fun close() = session.close()
}
