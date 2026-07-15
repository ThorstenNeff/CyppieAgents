package com.tneff.cyppieagents.transport.mux

import com.tneff.cyppieagents.mux.MuxHello
import com.tneff.cyppieagents.mux.YamuxFlags
import com.tneff.cyppieagents.mux.YamuxFrame
import com.tneff.cyppieagents.mux.YamuxFrameCodec
import com.tneff.cyppieagents.transport.BridgeSocket
import com.tneff.cyppieagents.transport.ServerNoiseTunnel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CYP-620 Increment 3 (step 5) teeth — [MuxBridge] + the G7 [MuxHello] handshake: hello match starts the session and
 * bridges streams to per-stream loopback sockets; a bad/absent/pool-peer hello is refused fail-closed (no session, no
 * bytes); T3 loopback guard.
 */
class MuxBridgeTest {

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

    private class FakeSink : BridgeSocket {
        val written = ArrayList<Byte>()
        private val never = CompletableDeferred<Int>()
        override val remote: InetAddress = InetAddress.getByName("127.0.0.1")
        override suspend fun write(bytes: ByteArray) { written.addAll(bytes.toList()) }
        override suspend fun read(buf: ByteArray): Int = never.await()
        override fun reset() { never.complete(-1) }
        override fun closeGraceful() {}
    }

    private class Dialer {
        val sinks = mutableListOf<FakeSink>()
        val factory: (String, Int) -> BridgeSocket = { _, _ -> FakeSink().also { sinks += it } }
    }

    private fun syn(streamId: Long, cls: Int, data: ByteArray) =
        YamuxFrameCodec.encode(YamuxFrame.data(streamId, byteArrayOf(cls.toByte()) + data, YamuxFlags.SYN))

    @Test
    fun helloMatch_answersHello_startsSession_bridgesStreamToLoopback() = runTest(UnconfinedTestDispatcher()) {
        val tunnel = FakeTunnel(); val dialer = Dialer()
        tunnel.preload(MuxHello.ENCODED, syn(1, 3, byteArrayOf('h'.code.toByte(), 'i'.code.toByte())), null)
        MuxBridge(loopbackPort = 65000, socketFactory = dialer.factory).bridge(tunnel)
        assertTrue(tunnel.sent.isNotEmpty() && tunnel.sent[0].contentEquals(MuxHello.ENCODED), "our hello is answered on a match")
        assertEquals(1, dialer.sinks.size, "one loopback socket dialed for the one opened stream")
        assertEquals("hi", dialer.sinks[0].written.toByteArray().decodeToString(), "the stream's request bytes reached its loopback socket")
    }

    @Test
    fun helloMismatch_refusesFailClosed_noSession_noBytes() = runTest(UnconfinedTestDispatcher()) {
        val tunnel = FakeTunnel(); val dialer = Dialer()
        // A pool-mode peer / tampered marker: it sends HTTP bytes instead of a mux hello.
        tunnel.preload("GET / HTTP/1.1\r\n".encodeToByteArray(), syn(1, 3, byteArrayOf(1)), null)
        MuxBridge(loopbackPort = 65000, socketFactory = dialer.factory).bridge(tunnel)
        assertTrue(tunnel.closed, "a bad hello → the tunnel is closed fail-closed")
        assertTrue(tunnel.sent.isEmpty(), "no hello answered — no session started")
        assertTrue(dialer.sinks.isEmpty(), "no stream bridged — nothing bled to the loopback")
    }

    @Test
    fun helloAbsent_tunnelClosedBeforeHello_refuses() = runTest(UnconfinedTestDispatcher()) {
        val tunnel = FakeTunnel(); val dialer = Dialer()
        tunnel.preload(null) // tunnel closed before any hello
        MuxBridge(loopbackPort = 65000, socketFactory = dialer.factory).bridge(tunnel)
        assertTrue(tunnel.closed)
        assertTrue(dialer.sinks.isEmpty())
    }

    @Test
    fun helloVersionSkew_refused() = runTest(UnconfinedTestDispatcher()) {
        val tunnel = FakeTunnel(); val dialer = Dialer()
        val skewed = MuxHello.ENCODED.copyOf(); skewed[5] = (MuxHello.VERSION + 1).toByte() // bump the version byte
        tunnel.preload(skewed, null)
        MuxBridge(loopbackPort = 65000, socketFactory = dialer.factory).bridge(tunnel)
        assertTrue(tunnel.closed, "a version skew is refused fail-closed (no cross-version session)")
        assertTrue(tunnel.sent.isEmpty())
    }

    @Test
    fun perStream_oneLoopbackSocketDialedPerOpenedStream() = runTest(UnconfinedTestDispatcher()) {
        val tunnel = FakeTunnel(); val dialer = Dialer()
        tunnel.preload(MuxHello.ENCODED, syn(1, 3, byteArrayOf(1)), syn(2, 2, byteArrayOf(2)), null)
        MuxBridge(loopbackPort = 65000, socketFactory = dialer.factory).bridge(tunnel)
        assertEquals(2, dialer.sinks.size, "one loopback socket per stream — the mux fans out, the pool is gone")
    }

    @Test
    fun construction_nonLoopbackTarget_failsClosed_T3() {
        assertFailsWith<IllegalArgumentException> { MuxBridge(loopbackPort = 65000, loopbackHost = "8.8.8.8") }
    }
}
