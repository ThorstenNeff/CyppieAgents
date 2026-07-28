package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.model.ExperimentalFederation
import com.tneff.cyppieagents.model.HubIssuerTrust
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CYP-851 (S-Fed-4a) — teeth for the byte-opaque hub↔hub session plumbing over the [FederationPeerTransport] stub.
 * Deterministic (no delays/timing): the fake transport's inbound is a fixed flow, so no subscription race.
 */
@OptIn(ExperimentalFederation::class)
class Cyp851FederationSessionTest {

    private class FakeTransport(incomingFrames: List<ByteArray> = emptyList()) : FederationPeerTransport {
        val sent = mutableListOf<ByteArray>()
        var closeCount = 0
        override val incoming: Flow<ByteArray> = incomingFrames.asFlow()
        override suspend fun send(frame: ByteArray) { sent += frame }
        override suspend fun close() { closeCount++ }
    }

    // CYP-858: the constructor is private — the SOLE path is the gated FederationSession.open(). These plumbing teeth
    // build through an ADMITting gate (enabled + TRUSTED) so they still exercise a real session; the admission gating
    // itself is covered by Cyp858FederationSessionSolePathTest.
    private val admittingGate = FederationAdmissionGate(federationEnabled = true)
    private fun openSession(transport: FederationPeerTransport): FederationSession =
        FederationSession.open(admittingGate, HubIssuerTrust.TRUSTED, transport)!!

    /**
     * Byte-OPAQUE forward: an arbitrary, non-UTF8 frame is forwarded to the transport UNCHANGED — proving the
     * session never decodes/reshapes a frame (the stub-only §5-Naht discipline in behavioral form). MUT the session
     * to parse/transform the frame → RED.
     */
    @Test
    fun send_forwardsArbitraryBytesUnchanged_byteOpaque() = runBlocking {
        val t = FakeTransport()
        val session = openSession(t)
        val frame = byteArrayOf(0x00, 0xFF.toByte(), 0x7F, 0x80.toByte(), 0x01)
        session.send(frame)
        assertEquals(1, t.sent.size)
        assertContentEquals(frame, t.sent.single())
    }

    /** Inbound stream is the transport's stream verbatim — the session surfaces frames, does not buffer/reorder. */
    @Test
    fun incoming_isTheTransportStreamVerbatim() = runBlocking {
        val a = byteArrayOf(1)
        val b = byteArrayOf(2, 3)
        val session = openSession(FakeTransport(incomingFrames = listOf(a, b)))
        val got = session.incoming.toList()
        assertEquals(2, got.size)
        assertContentEquals(a, got[0])
        assertContentEquals(b, got[1])
    }

    /** Fail-closed lifecycle: send after close throws (never silently drops), and close closes the transport. */
    @Test
    fun sendAfterClose_failsClosed() = runBlocking {
        val t = FakeTransport()
        val session = openSession(t)
        session.close()
        // assertFailsWith returns the caught exception (non-Unit) → keep a Unit-returning assert LAST so the
        // JUnit4 @Test method stays `void`.
        assertFailsWith<IllegalStateException> { session.send(byteArrayOf(9)) }
        assertTrue(session.isClosed)
        assertEquals(1, t.closeCount)
    }

    /** Close is idempotent — a double close closes the transport exactly once. */
    @Test
    fun close_isIdempotent() = runBlocking {
        val t = FakeTransport()
        val session = openSession(t)
        session.close()
        session.close()
        assertEquals(1, t.closeCount)
    }

    /**
     * N-CHANNEL, NO MUX: two sessions ride two INDEPENDENT transports. A frame sent on session A lands ONLY on
     * transport A; transport B never sees it. Proves federation concurrency is N independent transports, never a
     * multiplexed shared channel (mux is §5-forbidden). MUT a shared/static transport → B sees A's frame → RED.
     */
    @Test
    fun twoSessions_rideIndependentTransports_noMux() = runBlocking {
        val tA = FakeTransport()
        val tB = FakeTransport()
        val sA = openSession(tA)
        val sB = openSession(tB)

        sA.send(byteArrayOf(0xA))
        assertEquals(1, tA.sent.size)
        assertEquals(0, tB.sent.size) // no cross-talk

        sB.send(byteArrayOf(0xB))
        assertEquals(1, tA.sent.size) // A unchanged by B's send
        assertEquals(1, tB.sent.size)
    }
}
