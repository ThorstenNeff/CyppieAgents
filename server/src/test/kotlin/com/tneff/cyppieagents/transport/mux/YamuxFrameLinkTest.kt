package com.tneff.cyppieagents.transport.mux

import com.tneff.cyppieagents.mux.YamuxFrame
import com.tneff.cyppieagents.mux.YamuxFrameCodec
import com.tneff.cyppieagents.mux.YamuxFlags
import com.tneff.cyppieagents.mux.YamuxProtocolException
import com.tneff.cyppieagents.transport.NOISE_MAX_FRAME
import com.tneff.cyppieagents.transport.ServerNoiseTunnel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-620 Increment 3 (adapter) teeth — the Noise-message ↔ yamux-frame boundary + invariant M1.
 */
class YamuxFrameLinkTest {

    /** A fake [ServerNoiseTunnel]: inbound messages come from [inbound]; every [send] is recorded and (optionally)
     *  gated on [sendGate] so a test can prove backpressure pass-through deterministically. */
    private class FakeTunnel(
        private val inbound: Channel<ByteArray?> = Channel(Channel.UNLIMITED),
        val sendGate: CompletableDeferred<Unit>? = null,
    ) : ServerNoiseTunnel {
        val sent = mutableListOf<ByteArray>()
        var closed = false
        override val handshakeHash: ByteArray = ByteArray(32)
        fun feedInbound(msg: ByteArray?) { inbound.trySend(msg) }
        override suspend fun send(plaintext: ByteArray) {
            sendGate?.await() // when present, blocks here until the test releases it (relay-TCP backed up)
            sent += plaintext
        }
        override suspend fun receive(): ByteArray? = inbound.receive()
        override suspend fun close() { closed = true }
    }

    @Test
    fun write_encodesEachFrameAsExactlyOneNoiseMessage() = runTest {
        val tunnel = FakeTunnel()
        val link = YamuxFrameLink(tunnel)
        val frame = YamuxFrame.data(streamId = 7, bytes = byteArrayOf(1, 2, 3), flags = YamuxFlags.SYN)
        link.write(frame)
        assertEquals(1, tunnel.sent.size, "one frame → exactly one Noise message (no batching, no split)")
        assertTrue(YamuxFrameCodec.encode(frame).contentEquals(tunnel.sent[0]), "the message IS the wire encoding")
    }

    @Test
    fun read_decodesOneMessageIntoItsFrames_multiFrameMessageYieldsAll() = runTest {
        val tunnel = FakeTunnel()
        val link = YamuxFrameLink(tunnel)
        val a = YamuxFrame.data(1, byteArrayOf(10, 11))
        val b = YamuxFrame.windowUpdate(1, delta = 4096)
        // Two frames packed into ONE Noise message → read() returns both, in order.
        tunnel.feedInbound(YamuxFrameCodec.encode(a) + YamuxFrameCodec.encode(b))
        val frames = link.read()
        assertEquals(listOf(a, b), frames)
    }

    @Test
    fun read_reassemblesAFrameSplitAcrossTwoMessages() = runTest {
        val tunnel = FakeTunnel()
        val link = YamuxFrameLink(tunnel)
        val wire = YamuxFrameCodec.encode(YamuxFrame.data(3, byteArrayOf(9, 8, 7, 6)))
        val cut = 5 // split mid-header
        tunnel.feedInbound(wire.copyOfRange(0, cut))
        assertEquals(emptyList(), link.read(), "a partial-prefix message yields no complete frame yet")
        assertTrue(link.pendingIn() > 0, "the prefix is buffered in the decoder")
        tunnel.feedInbound(wire.copyOfRange(cut, wire.size))
        assertEquals(listOf(YamuxFrame.data(3, byteArrayOf(9, 8, 7, 6))), link.read(), "the completing message yields it")
        assertEquals(0, link.pendingIn())
    }

    @Test
    fun read_nullWhenTunnelClosed() = runTest {
        val tunnel = FakeTunnel()
        val link = YamuxFrameLink(tunnel)
        tunnel.feedInbound(null) // tunnel/relay closed
        assertNull(link.read())
    }

    @Test
    fun read_failsClosedOnMalformedFrame_neverSkips() = runTest {
        val tunnel = FakeTunnel()
        val link = YamuxFrameLink(tunnel)
        val bad = YamuxFrameCodec.encode(YamuxFrame.data(1, byteArrayOf(1)))
        bad[0] = 9 // corrupt the Version byte (must be 0)
        tunnel.feedInbound(bad)
        assertFailsWith<YamuxProtocolException> { link.read() }
    }

    @Test
    fun construction_failsClosedWhenMaxFrameExceedsNoiseCap_M1() {
        // A maxFrameLen that would let a frame exceed one Noise message is refused at construction (M1, fail-closed).
        assertFailsWith<IllegalArgumentException> {
            YamuxFrameLink(FakeTunnel(), maxFrameLen = NOISE_MAX_FRAME) // + HEADER_SIZE overflows the cap
        }
        // The exact boundary is allowed: HEADER_SIZE + maxFrameLen == cap.
        YamuxFrameLink(FakeTunnel(), maxFrameLen = NOISE_MAX_FRAME - YamuxFrameCodec.HEADER_SIZE)
    }

    @Test
    fun write_failsClosedOnAnOverCapFrame_M1() = runTest {
        val tunnel = FakeTunnel()
        val link = YamuxFrameLink(tunnel)
        // A DATA frame larger than one Noise message (a caller that ignored the chunk bound) → refused, never truncated.
        val oversize = YamuxFrame.data(1, ByteArray(NOISE_MAX_FRAME + 1))
        assertFailsWith<YamuxProtocolException> { link.write(oversize) }
        assertTrue(tunnel.sent.isEmpty(), "nothing bled onto the tunnel")
    }

    @Test
    fun write_backpressurePassesThrough_suspendsUntilTunnelDrains() = runTest {
        val gate = CompletableDeferred<Unit>()
        val tunnel = FakeTunnel(sendGate = gate)
        val link = YamuxFrameLink(tunnel)
        var completed = false
        val job = launch {
            link.write(YamuxFrame.data(1, byteArrayOf(1)))
            completed = true
        }
        runCurrent()
        assertFalse(completed, "write suspends while the tunnel/relay-TCP is backed up (no local buffer)")
        assertTrue(tunnel.sent.isEmpty())
        gate.complete(Unit) // relay drains
        job.join()
        assertTrue(completed && tunnel.sent.size == 1, "write resumes and delivers once the tunnel accepts")
    }
}
