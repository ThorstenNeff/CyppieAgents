package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.ExperimentalFederation
import com.tneff.cyppieagents.model.FederationFrame
import com.tneff.cyppieagents.model.FederationHello
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * CYP-864 (S-Fed-4b, DARK) — teeth for the N-tunnel binding seam. A stub [ServerNoiseTunnel] stands in for the live
 * Noise/relay tunnel; the binding must carry frames byte-exact in both directions and bridge the pull→push shapes.
 */
@OptIn(ExperimentalFederation::class)
class Cyp864FederationTunnelTransportTest {

    private class StubTunnel(
        outbound: List<ByteArray> = emptyList(),
        override val handshakeHash: ByteArray = ByteArray(32) { it.toByte() },
    ) : ServerNoiseTunnel {
        val sent = mutableListOf<ByteArray>()
        private val inbound = ArrayDeque(outbound)
        var closed = 0
        override suspend fun send(plaintext: ByteArray) { sent += plaintext }
        override suspend fun receive(): ByteArray? = inbound.removeFirstOrNull()
        override suspend fun close() { closed++ }
    }

    /** Outbound: a frame (incl. non-UTF8 bytes) is sent as ONE tunnel message, byte-exact (no reshape). */
    @Test
    fun send_forwardsToTunnel_byteExact() = runBlocking {
        val tunnel = StubTunnel()
        val transport = FederationTunnelTransport(tunnel)
        val frame = byteArrayOf(0x00, 0xFF.toByte(), 0x10, 0x80.toByte())
        transport.send(frame)
        assertEquals(1, tunnel.sent.size)
        assertContentEquals(frame, tunnel.sent.single())
    }

    /** Inbound: the push [incoming] Flow drains the tunnel's pull `receive()` until it returns null, verbatim + in order. */
    @Test
    fun incoming_drainsTunnelUntilClose_verbatim() = runBlocking {
        val a = byteArrayOf(1)
        val b = byteArrayOf(2, 3)
        val transport = FederationTunnelTransport(StubTunnel(outbound = listOf(a, b)))
        val got = transport.incoming.toList()
        assertEquals(2, got.size)
        assertContentEquals(a, got[0])
        assertContentEquals(b, got[1])
    }

    /** Lifecycle: closing the transport closes the underlying tunnel. */
    @Test
    fun close_closesUnderlyingTunnel() = runBlocking {
        val tunnel = StubTunnel()
        FederationTunnelTransport(tunnel).close()
        assertEquals(1, tunnel.closed)
    }

    /** The tunnel's handshake hash `h` is exposed for the §4b-1 federation-peer PoP channel-binding. */
    @Test
    fun handshakeHash_exposedForPoPChannelBinding() {
        val tunnel = StubTunnel(handshakeHash = ByteArray(32) { (it + 7).toByte() })
        assertContentEquals(tunnel.handshakeHash, FederationTunnelTransport(tunnel).handshakeHash)
    }

    /**
     * M2-FREEZE CONFORMANCE (byte-exact vs §4b): a ratified frame serialized via CommJson and pushed through the
     * binding reaches the tunnel as the IDENTICAL bytes (no transform), and decodes back to the same frame. A drift
     * in the binding (any reshape/re-encode) reds here.
     */
    @Test
    fun m2Conformance_carriesRatifiedFrameByteExact() = runBlocking {
        val frame: FederationFrame = FederationHello(protoMin = 1, protoMax = 4)
        val wire = CommJson.encodeToString(FederationFrame.serializer(), frame).encodeToByteArray()
        val tunnel = StubTunnel()
        FederationTunnelTransport(tunnel).send(wire)
        assertContentEquals(wire, tunnel.sent.single()) // byte-exact on the wire
        assertEquals(frame, CommJson.decodeFromString(FederationFrame.serializer(), tunnel.sent.single().decodeToString()))
    }
}
