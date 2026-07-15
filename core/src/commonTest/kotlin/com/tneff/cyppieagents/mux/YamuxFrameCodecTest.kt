package com.tneff.cyppieagents.mux

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CYP-620 — conformance + fail-closed teeth for the shared `:core` yamux codec. Because this codec is the
 * COMPILER-ENFORCED wire contract (hub + client both use it), these vectors are the correctness oracle for BOTH sides.
 */
class YamuxFrameCodecTest {

    // ---- conformance: exact byte layout vs the yamux spec v0 (hashicorp/yamux) ----

    @Test
    fun conformance_dataFrame_exactWireBytes() {
        // Version=0, Type=DATA(0), Flags=SYN(0x0001), StreamID=0x00000001, Length=3, payload="abc".
        val frame = YamuxFrame.data(streamId = 1, bytes = byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte()), flags = YamuxFlags.SYN)
        val expected = byteArrayOf(
            0x00,                                // Version
            0x00,                                // Type = DATA
            0x00, 0x01,                          // Flags = SYN
            0x00, 0x00, 0x00, 0x01,              // StreamID = 1
            0x00, 0x00, 0x00, 0x03,              // Length = 3
            'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(),
        )
        assertContentEquals(expected, YamuxFrameCodec.encode(frame), "DATA frame matches the yamux spec byte layout")
    }

    @Test
    fun conformance_windowUpdate_lengthIsDelta_noPayload() {
        // Type=WINDOW_UPDATE(1), Flags=ACK(0x2), StreamID=2, Length=256*1024 (a 256KB credit delta), no payload.
        val delta = 256L * 1024
        val frame = YamuxFrame.windowUpdate(streamId = 2, delta = delta, flags = YamuxFlags.ACK)
        val bytes = YamuxFrameCodec.encode(frame)
        assertEquals(YamuxFrameCodec.HEADER_SIZE, bytes.size, "WINDOW_UPDATE is header-only (Length is a value, not a byte count)")
        assertEquals(0x01, bytes[1].toInt(), "Type=WINDOW_UPDATE")
        assertEquals(delta, YamuxFrameCodec.getU32(bytes, 8), "Length carries the window delta")
    }

    @Test
    fun conformance_u32_streamId_stayUnsigned_pastIntMax() {
        // A StreamID / delta > 2^31 must round-trip unsigned (the reason we hold u32 in Long).
        val big = 0xFFFF_FFFEL
        val rt = YamuxFrameDecoder().feed(YamuxFrameCodec.encode(YamuxFrame.windowUpdate(big, big))).single()
        assertEquals(big, rt.streamId)
        assertEquals(big, rt.length)
    }

    // ---- round-trip: every type + flag survives encode→decode ----

    @Test
    fun roundTrip_allTypes() {
        val frames = listOf(
            YamuxFrame.data(1, byteArrayOf(1, 2, 3, 4), YamuxFlags.SYN),
            YamuxFrame.data(2, ByteArray(0), YamuxFlags.FIN),                 // a 0-length DATA (FIN carrier)
            YamuxFrame.windowUpdate(3, 4096, YamuxFlags.SYN or YamuxFlags.ACK),
            YamuxFrame.ping(0xDEADBEEFL, YamuxFlags.SYN),
            YamuxFrame.goAway(YamuxGoAway.PROTOCOL_ERROR),
            YamuxFrame(YamuxType.DATA, YamuxFlags.RST, 5, 0),                  // a stream RST (0-length DATA + RST)
        )
        val decoded = YamuxFrameDecoder().feed(frames.flatMap { YamuxFrameCodec.encode(it).toList() }.toByteArray())
        assertEquals(frames, decoded, "all frame types round-trip encode→decode (contract identity)")
    }

    // ---- streaming: partial + coalesced feeds ----

    @Test
    fun streaming_partialThenComplete_and_multipleInOneFeed() {
        val f1 = YamuxFrame.data(1, byteArrayOf(9, 9, 9), YamuxFlags.SYN)
        val f2 = YamuxFrame.windowUpdate(1, 1024)
        val wire = YamuxFrameCodec.encode(f1) + YamuxFrameCodec.encode(f2)
        val dec = YamuxFrameDecoder()
        // Feed byte-by-byte for the first frame's header+partial-payload → nothing emitted until complete.
        assertTrue(dec.feed(wire.copyOfRange(0, 5)).isEmpty(), "a partial header emits nothing")
        assertTrue(dec.feed(wire.copyOfRange(5, 14)).isEmpty(), "a partial DATA payload emits nothing")
        val out = dec.feed(wire.copyOfRange(14, wire.size)) // completes f1 + all of f2
        assertEquals(listOf(f1, f2), out, "the buffered partial + the rest decode to both frames, in order")
        assertEquals(0, dec.pending(), "no bytes left buffered (no leak)")
    }

    // ---- fail-closed (no-byte-bleed): a malformed frame throws, never a silent skip ----

    @Test
    fun failClosed_badVersion() {
        val bad = YamuxFrameCodec.encode(YamuxFrame.windowUpdate(1, 1)).also { it[0] = 0x07 } // version 7
        assertFailsWith<YamuxProtocolException> { YamuxFrameDecoder().feed(bad) }
    }

    @Test
    fun failClosed_unknownType() {
        val bad = YamuxFrameCodec.encode(YamuxFrame.windowUpdate(1, 1)).also { it[1] = 0x7F } // type 127
        assertFailsWith<YamuxProtocolException> { YamuxFrameDecoder().feed(bad) }
    }

    @Test
    fun failClosed_dataLengthOverCap_neverAllocates() {
        // A DATA header claiming Length beyond maxFrameLen → protocol error BEFORE any payload wait/allocation.
        val hdr = ByteArray(YamuxFrameCodec.HEADER_SIZE)
        hdr[1] = 0x00 // DATA
        YamuxFrameCodec.putU32(hdr, 4, 1)
        YamuxFrameCodec.putU32(hdr, 8, 1_000_000) // 1 MB > the 64KB test cap
        assertFailsWith<YamuxProtocolException> { YamuxFrameDecoder(maxFrameLen = 64 * 1024).feed(hdr) }
    }

    // ---- ★ no-byte-bleed: interleaved streams keep their OWN payloads (the load-bearing boundary) ----

    @Test
    fun noByteBleed_interleavedStreams_eachKeepsItsOwnPayload() {
        val a = YamuxFrame.data(1, byteArrayOf(0xA1.toByte(), 0xA2.toByte()), YamuxFlags.SYN)
        val b = YamuxFrame.data(2, byteArrayOf(0xB1.toByte(), 0xB2.toByte(), 0xB3.toByte()), YamuxFlags.SYN)
        val a2 = YamuxFrame.data(1, byteArrayOf(0xA3.toByte()))
        val wire = YamuxFrameCodec.encode(a) + YamuxFrameCodec.encode(b) + YamuxFrameCodec.encode(a2)
        val decoded = YamuxFrameDecoder().feed(wire)
        assertEquals(3, decoded.size)
        // Each frame's payload is EXACTLY its own — never a byte from another stream.
        assertContentEquals(byteArrayOf(0xA1.toByte(), 0xA2.toByte()), decoded[0].payload)
        assertEquals(1L, decoded[0].streamId)
        assertContentEquals(byteArrayOf(0xB1.toByte(), 0xB2.toByte(), 0xB3.toByte()), decoded[1].payload)
        assertEquals(2L, decoded[1].streamId)
        assertContentEquals(byteArrayOf(0xA3.toByte()), decoded[2].payload)
        assertEquals(1L, decoded[2].streamId)
    }
}
