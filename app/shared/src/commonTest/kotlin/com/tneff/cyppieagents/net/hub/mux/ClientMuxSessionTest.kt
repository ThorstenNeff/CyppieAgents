package com.tneff.cyppieagents.net.hub.mux

import com.tneff.cyppieagents.mux.YamuxFrame
import com.tneff.cyppieagents.mux.YamuxFrameCodec
import com.tneff.cyppieagents.mux.YamuxFrameDecoder
import com.tneff.cyppieagents.mux.YamuxType
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-620 — [ClientMuxSession] client-wiring teeth (consuming the shared `:core` yamux codec). The load-bearing one is
 * **M2 cross-stream-isolation**: N logical streams share one Noise tunnel, so a slow/stalled consumer on one stream
 * must never stall the session read-loop and thus never starve a sibling (no head-of-line) — the client half of the
 * per-stream-flow-control-not-per-tunnel-H7 property from the CYP-620 handoff.
 */
class ClientMuxSessionTest {

    /** A fake carrier tunnel: `receive()` yields queued (encoded) frame chunks; `send()` captures client frames. */
    private class FakeCarrier : NoiseTunnel {
        override val handshakeHash = ByteArray(32)
        val sent = mutableListOf<ByteArray>()
        private val rx = Channel<ByteArray>(Channel.UNLIMITED)
        suspend fun deliver(frame: YamuxFrame) { rx.send(YamuxFrameCodec.encode(frame)) }
        override suspend fun send(plaintext: ByteArray) { sent.add(plaintext) }
        override suspend fun receive(): ByteArray? = rx.receiveCatching().getOrNull()
        override suspend fun close() { rx.close() }
    }

    private fun decodeOne(bytes: ByteArray): YamuxFrame {
        val frames = YamuxFrameDecoder(YamuxFrameDecoder.DEFAULT_MAX_FRAME_LEN).feed(bytes)
        assertEquals(1, frames.size, "exactly one frame in the buffer")
        return frames.single()
    }

    /** Total app-DATA bytes the client sent on [streamId] (excluding the SYN open frame's streamClass byte). */
    private fun dataBytesSentFor(carrier: FakeCarrier, streamId: Long): Int {
        val dec = YamuxFrameDecoder(YamuxFrameDecoder.DEFAULT_MAX_FRAME_LEN)
        var total = 0
        for (chunk in carrier.sent) for (f in dec.feed(chunk)) {
            if (f.type == YamuxType.DATA && !f.isSyn && f.streamId == streamId) total += f.payload.size
        }
        return total
    }

    @Test
    fun send_blocksAtWindowZero_resumesOnWindowUpdate_M2adapterBackpressure() = runTest {
        // CYP-620 adapter-backpressure (against :core's REAL YamuxSendWindow): a stream may send only while it holds
        // credit; at 0 it BLOCKS this stream (never the tunnel) until an inbound WINDOW_UPDATE grants more. With a 4-byte
        // window, sending 6 bytes emits exactly the first 4 then blocks; a grant of 4 unblocks the tail. Mutant: send
        // ignores the window (emits all 6 at once) ⇒ 6 bytes flow before any grant + send completes ⇒ RED.
        val carrier = FakeCarrier()
        val session = ClientMuxSession(carrier, scope = this, initialWindow = 4)
        session.start()
        val s = assertNotNull(session.openStream(StreamClass.AGENT_WS))

        val sendDone = CompletableDeferred<Unit>()
        launch { s.send(byteArrayOf(1, 2, 3, 4, 5, 6)); sendDone.complete(Unit) }
        advanceUntilIdle() // let the send push one window's worth, then block at credit 0

        assertEquals(4, dataBytesSentFor(carrier, s.streamId), "exactly one window (4B) flows, then the send blocks at 0")
        assertFalse(sendDone.isCompleted, "the send is BLOCKED awaiting a WINDOW_UPDATE — not dropping, not busy-looping past the window")

        carrier.deliver(YamuxFrame.windowUpdate(s.streamId, 4)) // peer credits 4 more
        advanceUntilIdle()

        assertTrue(sendDone.isCompleted, "the WINDOW_UPDATE unblocks the send")
        assertEquals(6, dataBytesSentFor(carrier, s.streamId), "after the grant the remaining bytes flow (6B total)")
        session.close()
    }

    @Test
    fun dataFrames_slowConsumer_doesNotStallSibling_M2crossStreamIsolation() = runTest {
        val carrier = FakeCarrier()
        val session = ClientMuxSession(carrier, scope = this) // this = TestScope
        session.start()
        val a = assertNotNull(session.openStream(StreamClass.AGENT_WS))     // id 1
        val b = assertNotNull(session.openStream(StreamClass.SINGLETON_WS)) // id 3

        // The peer delivers DATA for A, then B. A's consumer NEVER reads (stalled) — B must still arrive.
        carrier.deliver(YamuxFrame.data(a.streamId, "a".encodeToByteArray()))
        carrier.deliver(YamuxFrame.data(b.streamId, "b".encodeToByteArray()))

        // Do NOT read `a`. If the read-loop blocked on A's (unread) delivery, B would never be dispatched.
        // Mutant: per-stream inbound Channel(RENDEZVOUS) ⇒ delivering A suspends the loop ⇒ this times out ⇒ RED.
        val got = withTimeout(5_000) { b.receive() }
        assertEquals("b", got?.decodeToString(), "the sibling stream is delivered despite the stalled consumer (M2 isolation)")

        session.close()
    }

    @Test
    fun openStream_emitsSyn_withStreamClassByte_oddId_pinnedContract() = runTest {
        val carrier = FakeCarrier()
        val session = ClientMuxSession(carrier, scope = this)
        session.start()
        val s = assertNotNull(session.openStream(StreamClass.CONTROL))

        val syn = decodeOne(carrier.sent.single())
        assertTrue(syn.isSyn, "openStream emits a SYN frame")
        assertEquals(s.streamId, syn.streamId)
        assertEquals(1L, syn.streamId, "the client opens ODD stream ids (yamux: client=odd)")
        assertEquals(1, syn.payload.size, "the SYN payload is the single streamClass byte")
        assertEquals(StreamClass.CONTROL.wire, syn.payload[0].toInt(), "first SYN-payload byte = streamClass (0=control)")

        session.close()
    }

    @Test
    fun openStream_atMaxStreams_failsClosed_null() = runTest {
        val carrier = FakeCarrier()
        val session = ClientMuxSession(carrier, scope = this, maxStreams = 2)
        session.start()
        assertNotNull(session.openStream(StreamClass.REST))
        assertNotNull(session.openStream(StreamClass.REST))
        assertNull(session.openStream(StreamClass.REST), "at maxStreams ⇒ null (fail-closed) — the per-operator stream cap")
        session.close()
    }

    @Test
    fun peerFin_endsReceive_withNull() = runTest {
        val carrier = FakeCarrier()
        val session = ClientMuxSession(carrier, scope = this)
        session.start()
        val s = assertNotNull(session.openStream(StreamClass.AGENT_WS))
        carrier.deliver(YamuxFrame.data(s.streamId, "hi".encodeToByteArray()))
        assertEquals("hi", withTimeout(5_000) { s.receive() }?.decodeToString())
        // Peer FIN (header-only WINDOW_UPDATE|FIN) → the stream's receive() returns null (fail-closed EOF).
        carrier.deliver(YamuxFrame(com.tneff.cyppieagents.mux.YamuxType.WINDOW_UPDATE, com.tneff.cyppieagents.mux.YamuxFlags.FIN, s.streamId, 0L))
        assertNull(withTimeout(5_000) { s.receive() }, "a peer FIN ends the stream (receive → null)")
        session.close()
    }
}
