package com.tneff.cyppieagents.net.hub.mux

import com.tneff.cyppieagents.mux.MuxHello
import com.tneff.cyppieagents.mux.YamuxFrameDecoder
import com.tneff.cyppieagents.mux.YamuxType
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-622 — the client half of the **G7 mode/version hello** (§4.8.10). The server ([MuxBridge]) requires the hello as
 * the FIRST tunnel message and refuses fail-closed if it is absent/mismatched; the client must symmetrically send its
 * hello first and verify the server's before opening any stream. This pairs the client against the REAL shared
 * [MuxHello] contract (`:core`), closing the client half compiler+test-side (bilateral end-to-end stays dogfood-proven).
 *
 * The gap this guards was invisible to the isolated unit tests (a fake carrier with no MuxBridge is green either way);
 * it only bites on a real flip — exactly the e2e-datapath class flagged as not-unit-toothable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Cyp622ClientHelloTest {

    /** A carrier that scripts the server's hello on the FIRST receive, then stays alive (so a valid-hello session enters
     *  the yamux loop and doesn't churn). [serverHello] = null ⇒ EOF on the hello read; a byte array ⇒ that (valid or bad)
     *  hello. `send()` records the client's writes in order. */
    private class ScriptedCarrier(private val serverHello: ByteArray?) : NoiseTunnel {
        val sent = mutableListOf<ByteArray>()
        private var receiveCalls = 0
        override val handshakeHash = ByteArray(32) { 0x22 }
        override suspend fun send(plaintext: ByteArray) { sent.add(plaintext) }
        override suspend fun receive(): ByteArray? {
            receiveCalls++
            return if (receiveCalls == 1) serverHello else awaitCancellation() // 1st = server hello; then park (stay alive)
        }
        override suspend fun close() {}
    }

    private fun decode(bytes: ByteArray) =
        YamuxFrameDecoder(YamuxFrameDecoder.DEFAULT_MAX_FRAME_LEN).feed(bytes)

    // --- Tooth A: the client's FIRST carrier.send is the G7 hello, strictly before any yamux SYN. ---

    @Test
    fun firstCarrierSend_isG7Hello_beforeAnySyn() = runTest {
        val carrier = ScriptedCarrier(serverHello = MuxHello.ENCODED) // a valid server hello ⇒ handshake completes
        val session = ClientMuxSession(carrier, this)
        val job = launch { session.run() }
        advanceUntilIdle()

        // The very first thing written to the carrier is our hello — NOT a yamux frame.
        assertTrue(carrier.sent.isNotEmpty(), "the client must have sent its hello")
        assertContentEquals(MuxHello.ENCODED, carrier.sent.first(), "first carrier.send == MuxHello.ENCODED (G7, before any SYN)")

        // Only AFTER the verified hello may a stream open — and its SYN is a LATER send than the hello.
        val stream = session.openStream(StreamClass.CONTROL)
        advanceUntilIdle()
        assertTrue(stream != null, "a verified hello ⇒ openStream succeeds")
        assertTrue(carrier.sent.size >= 2, "the SYN is sent after the hello")
        val synFrames = decode(carrier.sent[1])
        assertEquals(YamuxType.DATA, synFrames.single().type, "the second send is the stream-open SYN (yamux DATA+SYN)")
        assertTrue(synFrames.single().isSyn, "…and it carries the SYN flag")

        job.cancel()
    }

    // --- Tooth B: a bad or absent server hello fails the handshake closed — no streams open. ---

    @Test
    fun badServerHello_failsClosed_noStreams() = runTest {
        val carrier = ScriptedCarrier(serverHello = byteArrayOf(0x00, 0x01, 0x02)) // wrong length/magic ⇒ verify() false
        val session = ClientMuxSession(carrier, this)
        val job = launch { session.run() }
        advanceUntilIdle()

        // We still sent OUR hello first (fail-closed is about the PEER's hello), but no stream may open.
        assertContentEquals(MuxHello.ENCODED, carrier.sent.first(), "our hello is sent before we read+reject the peer's")
        val stream = session.openStream(StreamClass.CONTROL)
        advanceUntilIdle()
        assertNull(stream, "a rejected server hello ⇒ openStream fails closed (no stream on a non-mux/incompatible peer)")
        assertEquals(0, session.liveStreamCount(), "no streams opened under a fail-closed handshake")

        job.cancel()
    }

    @Test
    fun eofServerHello_failsClosed_noStreams() = runTest {
        val carrier = ScriptedCarrier(serverHello = null) // EOF on the hello read (e.g. server refused + closed)
        val session = ClientMuxSession(carrier, this)
        val job = launch { session.run() }
        advanceUntilIdle()

        val stream = session.openStream(StreamClass.CONTROL)
        advanceUntilIdle()
        assertNull(stream, "an EOF where the server hello should be ⇒ openStream fails closed")
        assertEquals(0, session.liveStreamCount(), "no streams opened after a hello EOF")

        job.cancel()
    }
}
