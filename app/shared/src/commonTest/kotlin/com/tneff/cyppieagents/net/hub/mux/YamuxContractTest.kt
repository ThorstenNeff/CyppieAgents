package com.tneff.cyppieagents.net.hub.mux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-620 — pins the **ratified yamux-spec-v0 wire contract** the client mirrors (verified against hashicorp/yamux).
 * These are load-bearing for the bilateral seam: a drift here (a class/type/flag/window value) would silently break
 * interop with the shared `:core` codec + the server. Each assert guards ONE pinned value, so any mutation reddens.
 * (When the authoritative `:core` yamux module lands, this provisional mirror is deleted and these become the
 * `:core` conformance vectors' client-side echo — same values, single-sourced.)
 */
class YamuxContractTest {

    @Test
    fun streamClass_wireValues_pinned() {
        // Pinned: 0=control/lifecycle · 1=agent-ws · 2=singleton-ws · 3=rest (first SYN-payload byte).
        assertEquals(0, StreamClass.CONTROL.wire)
        assertEquals(1, StreamClass.AGENT_WS.wire)
        assertEquals(2, StreamClass.SINGLETON_WS.wire)
        assertEquals(3, StreamClass.REST.wire)
        // Round-trip: the byte the peer reads maps back to exactly one class; out-of-range = null (fail-closed).
        for (c in StreamClass.entries) assertEquals(c, StreamClass.fromWire(c.wire))
        assertNull(StreamClass.fromWire(4), "an unknown streamClass byte is null (never silently coerced)")
        assertNull(StreamClass.fromWire(-1))
    }

    @Test
    fun frameType_wireValues_pinned() {
        // Pinned: Data=0 · WindowUpdate=1 · Ping=2 · GoAway=3 (== hashicorp/yamux protoVersion 0).
        assertEquals(0, FrameType.DATA.wire)
        assertEquals(1, FrameType.WINDOW_UPDATE.wire)
        assertEquals(2, FrameType.PING.wire)
        assertEquals(3, FrameType.GO_AWAY.wire)
        for (t in FrameType.entries) assertEquals(t, FrameType.fromWire(t.wire))
        assertNull(FrameType.fromWire(4), "an unknown frame type is null → the session GoAways proto-err, never guesses")
    }

    @Test
    fun flags_areDistinctSingleBits_pinned() {
        // Pinned bitmask: SYN=1, ACK=2, FIN=4, RST=8 — distinct single bits so they compose (e.g. SYN|ACK).
        assertEquals(1, YamuxFlags.SYN)
        assertEquals(2, YamuxFlags.ACK)
        assertEquals(4, YamuxFlags.FIN)
        assertEquals(8, YamuxFlags.RST)
        val all = listOf(YamuxFlags.SYN, YamuxFlags.ACK, YamuxFlags.FIN, YamuxFlags.RST)
        assertEquals(all.size, all.toSet().size, "flags are distinct")
        all.forEach { assertEquals(1, it.countOneBits(), "each flag is a single bit (composable)") }
    }

    @Test
    fun protocolConstants_pinned() {
        assertEquals(0, YamuxProtocol.VERSION)
        assertEquals(12, YamuxProtocol.HEADER_SIZE)
        assertEquals(256 * 1024, YamuxProtocol.INITIAL_STREAM_WINDOW, "per-stream window init = 256 KiB")
    }

    @Test
    fun frame_hasFlag_composesAndIsolates() {
        // The SYN|ACK compose is how a stream accept is encoded; hasFlag must read one bit without matching others.
        val synAck = YamuxFrame(FrameType.WINDOW_UPDATE, YamuxFlags.SYN or YamuxFlags.ACK, streamId = 7, length = 256 * 1024)
        assertTrue(synAck.hasFlag(YamuxFlags.SYN))
        assertTrue(synAck.hasFlag(YamuxFlags.ACK))
        assertFalse(synAck.hasFlag(YamuxFlags.FIN), "hasFlag reads ONE bit — FIN not set here")
        assertFalse(synAck.hasFlag(YamuxFlags.RST))
    }

    @Test
    fun frame_valueSemantics_forDeterministicTests() {
        // Value equality (incl. payload bytes) so the session/wiring teeth can assert on frames deterministically.
        val a = YamuxFrame(FrameType.DATA, 0, streamId = 3, length = 2, payload = byteArrayOf(1, 2))
        val b = YamuxFrame(FrameType.DATA, 0, streamId = 3, length = 2, payload = byteArrayOf(1, 2))
        val c = YamuxFrame(FrameType.DATA, 0, streamId = 3, length = 2, payload = byteArrayOf(1, 9))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c, "different payload bytes ⇒ not equal")
    }
}
