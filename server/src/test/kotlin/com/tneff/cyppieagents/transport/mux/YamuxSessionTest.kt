package com.tneff.cyppieagents.transport.mux

import com.tneff.cyppieagents.mux.YamuxFlags
import com.tneff.cyppieagents.mux.YamuxFrame
import com.tneff.cyppieagents.mux.YamuxFrameCodec
import com.tneff.cyppieagents.mux.YamuxFrameDecoder
import com.tneff.cyppieagents.mux.YamuxGoAway
import com.tneff.cyppieagents.mux.YamuxType
import com.tneff.cyppieagents.transport.BridgeSocket
import com.tneff.cyppieagents.transport.ServerNoiseTunnel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-620 Increment 3 (step 4) teeth — the mux session: SYN-open + streamClass, the NO-BYTE-BLEED demux boundary
 * (§4.8.2, the load-bearing correctness surface), maxStreams fail-closed (§4.6), unknown-stream RST, protocol-error
 * GoAway teardown, PING/PONG, and tunnel-drop resets-all.
 */
class YamuxSessionTest {

    /** A fake tunnel: [preload] frame-messages (+ a final `null`) into [inbound]; every outbound message is captured. */
    private class FakeTunnel : ServerNoiseTunnel {
        private val inbound = Channel<ByteArray?>(Channel.UNLIMITED)
        val sent = mutableListOf<ByteArray>()
        var closed = false; private set
        override val handshakeHash = ByteArray(32)
        fun preload(vararg msgs: ByteArray?) { msgs.forEach { inbound.trySend(it) } }
        override suspend fun send(plaintext: ByteArray) { sent += plaintext }
        override suspend fun receive(): ByteArray? = inbound.receive()
        override suspend fun close() { closed = true }
    }

    /** A recording per-stream sink; [written] accumulates upstream bytes; read() blocks (no response in these teeth). */
    private class FakeSink : BridgeSocket {
        val written = ArrayList<Byte>()
        var didReset = false; private set
        private val never = CompletableDeferred<Int>()
        override val remote: InetAddress = InetAddress.getByName("127.0.0.1")
        override suspend fun write(bytes: ByteArray) { written.addAll(bytes.toList()) }
        override suspend fun read(buf: ByteArray): Int = never.await() // no downstream response in these tests
        override fun reset() { didReset = true; never.complete(-1) }
        override fun closeGraceful() {}
    }

    private class SinkTable {
        val byId = HashMap<Long, FakeSink>()
        val classes = HashMap<Long, Int>()
        val factory: (Long, Int) -> BridgeSocket = { id, cls ->
            classes[id] = cls
            FakeSink().also { byId[id] = it }
        }
    }

    private fun syn(streamId: Long, cls: Int, data: ByteArray) =
        YamuxFrame.data(streamId, byteArrayOf(cls.toByte()) + data, YamuxFlags.SYN)

    private fun enc(f: YamuxFrame) = YamuxFrameCodec.encode(f)

    private fun sentFrames(t: FakeTunnel): List<YamuxFrame> {
        val dec = YamuxFrameDecoder()
        val out = ArrayList<YamuxFrame>()
        t.sent.forEach { out += dec.feed(it) }
        return out
    }

    @Test
    fun syn_opensStream_extractsStreamClass_deliversFirstPayload() = runTest(UnconfinedTestDispatcher()) {
        val tunnel = FakeTunnel(); val sinks = SinkTable()
        tunnel.preload(enc(syn(1, 3, byteArrayOf('h'.code.toByte(), 'i'.code.toByte()))), null)
        YamuxSession(YamuxFrameLink(tunnel), MuxWriteScheduler(), sinks.factory, backgroundScope).run()
        assertEquals(3, sinks.classes[1], "streamClass = the first payload byte of the SYN DATA")
        assertEquals("hi", sinks.byId[1]!!.written.toByteArray().decodeToString(), "the rest of the payload is the first request DATA")
    }

    @Test
    fun noByteBleed_interleavedStreams_eachSinkGetsExactlyItsOwnBytes() = runTest(UnconfinedTestDispatcher()) {
        val tunnel = FakeTunnel(); val sinks = SinkTable()
        tunnel.preload(
            enc(syn(1, 1, byteArrayOf(0xA1.toByte()))),
            enc(syn(2, 2, byteArrayOf(0xB1.toByte()))),
            enc(syn(3, 3, byteArrayOf(0xC1.toByte()))),
            enc(YamuxFrame.data(2, byteArrayOf(0xB2.toByte()))),
            enc(YamuxFrame.data(1, byteArrayOf(0xA2.toByte()))),
            enc(YamuxFrame.data(3, byteArrayOf(0xC2.toByte()))),
            enc(YamuxFrame.data(1, byteArrayOf(0xA3.toByte()))),
            null,
        )
        YamuxSession(YamuxFrameLink(tunnel), MuxWriteScheduler(), sinks.factory, backgroundScope).run()
        // The load-bearing property: an interleaved transcript demuxes so each stream's socket holds EXACTLY its bytes.
        assertEquals(listOf(0xA1, 0xA2, 0xA3), sinks.byId[1]!!.written.map { it.toInt() and 0xFF })
        assertEquals(listOf(0xB1, 0xB2), sinks.byId[2]!!.written.map { it.toInt() and 0xFF })
        assertEquals(listOf(0xC1, 0xC2), sinks.byId[3]!!.written.map { it.toInt() and 0xFF })
    }

    @Test
    fun maxStreams_refusesExcessOpens_withRst_noExtraSink() = runTest(UnconfinedTestDispatcher()) {
        val tunnel = FakeTunnel(); val sinks = SinkTable()
        tunnel.preload(enc(syn(1, 3, byteArrayOf(1))), enc(syn(2, 3, byteArrayOf(2))), enc(syn(3, 3, byteArrayOf(3))), null)
        YamuxSession(YamuxFrameLink(tunnel), MuxWriteScheduler(), sinks.factory, backgroundScope, maxStreams = 2).run()
        assertEquals(setOf(1L, 2L), sinks.byId.keys, "only 2 streams opened; the 3rd is refused (no sink dialed)")
        val rst = sentFrames(tunnel).singleOrNull { it.streamId == 3L && it.isRst }
        assertTrue(rst != null, "the refused SYN is answered with an RST for its id (fail-closed, DoS envelope §4.6)")
    }

    @Test
    fun unknownStream_data_isRst_neverBleedsIntoAnother() = runTest(UnconfinedTestDispatcher()) {
        val tunnel = FakeTunnel(); val sinks = SinkTable()
        tunnel.preload(
            enc(syn(1, 3, byteArrayOf(0x11))),
            enc(YamuxFrame.data(9, byteArrayOf(0x99.toByte()))), // stream 9 was never opened
            null,
        )
        YamuxSession(YamuxFrameLink(tunnel), MuxWriteScheduler(), sinks.factory, backgroundScope).run()
        assertNull(sinks.byId[9], "no sink dialed for the unknown stream")
        assertEquals(listOf(0x11), sinks.byId[1]!!.written.map { it.toInt() and 0xFF }, "stream 1's bytes are untouched — the unknown DATA did NOT bleed in")
        assertTrue(sentFrames(tunnel).any { it.streamId == 9L && it.isRst }, "the unknown-stream DATA is answered with an RST")
    }

    @Test
    fun peerOvershootsWindow_emitsGoAway_andTearsDown() = runTest(UnconfinedTestDispatcher()) {
        val tunnel = FakeTunnel(); val sinks = SinkTable()
        // maxRecvWindow = 4, but the SYN's first DATA is 5 bytes → a flow-control violation → GoAway + teardown.
        tunnel.preload(enc(syn(1, 3, byteArrayOf(1, 2, 3, 4, 5))))
        YamuxSession(YamuxFrameLink(tunnel), MuxWriteScheduler(), sinks.factory, backgroundScope, maxRecvWindow = 4).run()
        val goAway = sentFrames(tunnel).singleOrNull { it.type == YamuxType.GO_AWAY }
        assertTrue(goAway != null && goAway.length == YamuxGoAway.PROTOCOL_ERROR.toLong(), "a flow-control breach → GoAway(protocol-error)")
        assertTrue(tunnel.closed, "the tunnel is torn down fail-closed")
    }

    @Test
    fun inboundGoAway_endsSessionCleanly_abortsStreams() = runTest(UnconfinedTestDispatcher()) {
        val tunnel = FakeTunnel(); val sinks = SinkTable()
        tunnel.preload(enc(syn(1, 3, byteArrayOf(1))), enc(YamuxFrame.goAway(YamuxGoAway.NORMAL)), null)
        YamuxSession(YamuxFrameLink(tunnel), MuxWriteScheduler(), sinks.factory, backgroundScope).run()
        assertTrue(sinks.byId[1]!!.didReset, "an inbound GoAway ends the session → every stream is aborted")
        assertTrue(tunnel.closed)
    }

    @Test
    fun pingSyn_isRepliedWithPong() = runTest(UnconfinedTestDispatcher()) {
        val tunnel = FakeTunnel(); val sinks = SinkTable()
        tunnel.preload(enc(YamuxFrame.ping(opaque = 99, flags = YamuxFlags.SYN)), null)
        YamuxSession(YamuxFrameLink(tunnel), MuxWriteScheduler(), sinks.factory, backgroundScope).run()
        val pong = sentFrames(tunnel).singleOrNull { it.type == YamuxType.PING && it.isAck }
        assertTrue(pong != null && pong.length == 99L, "a PING(SYN) is answered with a PONG(ACK) carrying the same opaque")
    }

    @Test
    fun tunnelClose_resetsAllStreams() = runTest(UnconfinedTestDispatcher()) {
        val tunnel = FakeTunnel(); val sinks = SinkTable()
        tunnel.preload(enc(syn(1, 3, byteArrayOf(1))), enc(syn(2, 3, byteArrayOf(2))), null)
        YamuxSession(YamuxFrameLink(tunnel), MuxWriteScheduler(), sinks.factory, backgroundScope).run()
        assertTrue(sinks.byId[1]!!.didReset && sinks.byId[2]!!.didReset, "a tunnel drop resets ALL streams (§4.8.3)")
    }
}
