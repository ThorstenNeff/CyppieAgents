package com.tneff.cyppieagents.transport.mux

import com.tneff.cyppieagents.mux.YamuxFlags
import com.tneff.cyppieagents.mux.YamuxFrame
import com.tneff.cyppieagents.mux.YamuxType
import com.tneff.cyppieagents.mux.YamuxProtocolException
import com.tneff.cyppieagents.transport.BridgeSocket
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-620 Increment 3 (step 3) teeth — one stream's flow control (G1–G4), the two pumps, and the per-stream FIN/RST
 * discipline. Fakes: a scriptable [FakeSink] ([BridgeSocket]) + a recording [RecordingEmitter].
 */
class YamuxStreamTest {

    /** A scriptable loopback socket: [feedRead]/[feedEof] drive the downstream (route→client) response; writes,
     *  reset, and graceful-close are recorded; [failReads] makes read() throw (abrupt route error). */
    private class FakeSink : BridgeSocket {
        val writes = mutableListOf<ByteArray>()
        var didReset = false; private set
        var didGraceful = false; private set
        var failReads = false
        private val readQ = Channel<ByteArray?>(Channel.UNLIMITED) // null = EOF
        fun feedRead(bytes: ByteArray) { readQ.trySend(bytes) }
        fun feedEof() { readQ.trySend(null) }
        override val remote: InetAddress = InetAddress.getByName("127.0.0.1")
        override suspend fun write(bytes: ByteArray) { writes += bytes }
        override suspend fun read(buf: ByteArray): Int {
            if (failReads) throw java.io.IOException("route reset")
            val chunk = readQ.receive() ?: return -1
            check(chunk.size <= buf.size) { "test feeds chunks that fit the credit-capped buffer" }
            chunk.copyInto(buf)
            return chunk.size
        }
        override fun reset() { didReset = true }
        override fun closeGraceful() { didGraceful = true }
    }

    private class RecordingEmitter : StreamEmitter {
        val ordered = mutableListOf<YamuxFrame>()
        val orderedClasses = mutableListOf<Int>()
        val control = mutableListOf<YamuxFrame>()
        override suspend fun ordered(streamId: Long, streamClass: Int, frame: YamuxFrame) {
            ordered += frame; orderedClasses += streamClass
        }
        override suspend fun control(frame: YamuxFrame) { control += frame }
    }

    @Test
    fun downstream_readsResponse_emitsDataFrames_thenFinOnEof_viaOrderedLane() = runTest(UnconfinedTestDispatcher()) {
        val sink = FakeSink(); val em = RecordingEmitter()
        val s = YamuxStream(streamId = 3, streamClass = 3, sink = sink, emitter = em, scope = backgroundScope)
        s.start()
        sink.feedRead(byteArrayOf(1, 2, 3)); sink.feedRead(byteArrayOf(4, 5)); sink.feedEof()
        advanceUntilIdle()
        assertEquals(
            listOf(
                YamuxFrame.data(3, byteArrayOf(1, 2, 3)),
                YamuxFrame.data(3, byteArrayOf(4, 5)),
                YamuxFrame.data(3, YamuxFrame.EMPTY, YamuxFlags.FIN),
            ), em.ordered,
            "response bytes → DATA frames, then a FIN on socket EOF — all via the per-stream ORDERED lane",
        )
        assertTrue(em.control.isEmpty(), "no FIN on the control lane — it must never overtake the last DATA (truncation)")
    }

    @Test
    fun downstream_backpressure_blocksAtZeroCredit_resumesOnWindowUpdate() = runTest(UnconfinedTestDispatcher()) {
        val sink = FakeSink(); val em = RecordingEmitter()
        // Send window = 3 bytes; the pump can emit 3 bytes then must block until the peer grants more.
        val s = YamuxStream(3, 3, sink, em, backgroundScope, initialSendWindow = 3)
        s.start()
        sink.feedRead(byteArrayOf(1, 2, 3)) // exactly the initial credit
        sink.feedRead(byteArrayOf(4, 5, 6)) // waits — no credit
        advanceUntilIdle()
        assertEquals(1, em.ordered.size, "only the first 3-byte frame; the pump then BLOCKS at zero credit (this stream only)")
        s.onWindowUpdate(3) // peer returns credit
        advanceUntilIdle()
        assertEquals(2, em.ordered.size, "the pump resumes and emits the next frame once credit is granted")
        assertEquals(YamuxFrame.data(3, byteArrayOf(4, 5, 6)), em.ordered[1])
    }

    @Test
    fun upstream_onData_writesToSink_andReturnsCreditOnDrain_viaControlLane() = runTest(UnconfinedTestDispatcher()) {
        val sink = FakeSink(); val em = RecordingEmitter()
        val s = YamuxStream(7, 1, sink, em, backgroundScope)
        s.start()
        s.onData(byteArrayOf(9, 8, 7, 6)) // 4 bytes request
        advanceUntilIdle()
        assertEquals(1, sink.writes.size); assertTrue(sink.writes[0].contentEquals(byteArrayOf(9, 8, 7, 6)))
        assertEquals(1, em.control.size, "credit returned on DRAIN (deadlock-free) via the priority control lane")
        val wu = em.control[0]
        assertEquals(YamuxType.WINDOW_UPDATE, wu.type); assertEquals(7L, wu.streamId); assertEquals(4L, wu.length)
    }

    @Test
    fun upstream_onFin_drainsRemaining_thenHalfClosesGracefully_notReset() = runTest(UnconfinedTestDispatcher()) {
        val sink = FakeSink(); val em = RecordingEmitter()
        val s = YamuxStream(3, 3, sink, em, backgroundScope)
        s.start()
        s.onData(byteArrayOf(1, 1)) // one pending request chunk
        s.onFin() // peer done sending
        advanceUntilIdle()
        assertEquals(1, sink.writes.size, "the pending chunk was drained before the half-close")
        assertTrue(sink.didGraceful, "clean FIN → shutdownOutput (route sees a clean input-EOF), CYP-609 per stream")
        assertFalse(sink.didReset, "a clean FIN never resets")
    }

    @Test
    fun onReset_abortsSocket_notGraceful() = runTest(UnconfinedTestDispatcher()) {
        val sink = FakeSink(); val em = RecordingEmitter()
        val s = YamuxStream(3, 3, sink, em, backgroundScope)
        s.start()
        s.onReset()
        advanceUntilIdle()
        assertTrue(sink.didReset, "inbound RST → abortive socket reset (truncation guard, per stream)")
        assertFalse(sink.didGraceful)
    }

    @Test
    fun recvWindow_peerOvershoot_failsClosed() = runTest(UnconfinedTestDispatcher()) {
        val sink = FakeSink(); val em = RecordingEmitter()
        val s = YamuxStream(3, 3, sink, em, backgroundScope, maxRecvWindow = 4) // advertised only 4 bytes
        s.start()
        assertFailsWith<YamuxProtocolException> {
            s.onData(byteArrayOf(1, 2, 3, 4, 5)) // 5 > 4 advertised → flow-control violation → fail closed
        }
    }

    @Test
    fun sendWindow_grantOverflow_failsClosed() = runTest(UnconfinedTestDispatcher()) {
        val sink = FakeSink(); val em = RecordingEmitter()
        val s = YamuxStream(3, 3, sink, em, backgroundScope, initialSendWindow = YamuxFrame.U32_MAX)
        s.start()
        assertFailsWith<YamuxProtocolException> { s.onWindowUpdate(1) } // U32_MAX + 1 > u32 → protocol violation
    }

    @Test
    fun downstream_socketReadError_emitsRst_viaOrderedLane() = runTest(UnconfinedTestDispatcher()) {
        val sink = FakeSink(); val em = RecordingEmitter()
        sink.failReads = true
        val s = YamuxStream(3, 3, sink, em, backgroundScope)
        s.start()
        advanceUntilIdle()
        assertEquals(1, em.ordered.size)
        assertTrue(em.ordered[0].isRst, "an abrupt route read error → an RST frame to the peer (same lane as DATA)")
        assertTrue(em.control.isEmpty())
    }
}
