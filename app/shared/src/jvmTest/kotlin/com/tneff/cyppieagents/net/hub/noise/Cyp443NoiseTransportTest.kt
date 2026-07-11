package com.tneff.cyppieagents.net.hub.noise

import com.southernstorm.noise.protocol.HandshakeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * CYP-443 Slice 1 — the [NoiseJavaClientTransport] against a REAL `noise-java` responder over the in-memory relay.
 * Productionizes the CR1 spike as honesty teeth: the initiator's `h` matches the responder's (the §7 seam, CI-2),
 * app traffic round-trips through the AEAD tunnel, and a **wrong pinned key fails closed** (misroute/MITM, CI-1).
 */
class Cyp443NoiseTransportTest {

    /** Hub-side responder: completes the NK handshake, then echoes each decrypted message back encrypted. */
    private fun CoroutineScope.launchResponder(
        hubRelay: RelayChannel,
        responder: HandshakeState,
        onHandshakeHash: (ByteArray) -> Unit = {},
    ): Job = launch(Dispatchers.Default) {
        try {
            responder.start()
            val msg1 = hubRelay.receive() ?: return@launch
            responder.readMessage(msg1, 0, msg1.size, ByteArray(NOISE_MAX_FRAME), 0) // e, es
            val out = ByteArray(NOISE_MAX_FRAME)
            val l2 = responder.writeMessage(out, 0, null, 0, 0) // e, ee
            hubRelay.send(out.copyOf(l2))
            onHandshakeHash(responder.handshakeHash.copyOf()) // before split, both ends compute the same h
            val pair = responder.split()
            while (true) {
                val frame = hubRelay.receive() ?: break
                val pt = ByteArray(frame.size)
                val n = pair.receiver.decryptWithAd(null, frame, 0, pt, 0, frame.size)
                val plain = pt.copyOf(n)
                val ct = ByteArray(plain.size + pair.sender.getMACLength())
                val m = pair.sender.encryptWithAd(null, plain, 0, ct, 0, plain.size)
                hubRelay.send(ct.copyOf(m))
            }
        } catch (e: Exception) {
            hubRelay.close() // fail-close so a wrong-key initiator sees EOF instead of hanging
        }
    }

    private fun newResponderWithStatic(): Pair<HandshakeState, ByteArray> {
        val hs = HandshakeState(NOISE_NK_SUITE, HandshakeState.RESPONDER)
        hs.localKeyPair.generateKeyPair()
        val static = ByteArray(hs.localKeyPair.publicKeyLength)
        hs.localKeyPair.getPublicKey(static, 0)
        return hs to static
    }

    @Test
    fun nkHandshake_exposesMatchingH_andRoundtripsAppTraffic() = runBlocking {
        val (clientRelay, hubRelay) = InMemoryRelayChannel.pair()
        val (responder, hubStatic) = newResponderWithStatic()
        var responderH: ByteArray? = null
        val job = launchResponder(hubRelay, responder) { responderH = it }

        val tunnel = NoiseJavaClientTransport().connect(hubStatic, clientRelay)
        assertEquals(32, tunnel.handshakeHash.size, "BLAKE2s h is 32 bytes")

        tunnel.send("hello-hub".encodeToByteArray())
        val echo = tunnel.receive()
        assertNotNull(echo)
        assertEquals("hello-hub", echo.decodeToString(), "app traffic round-trips through the AEAD tunnel")

        // The mandatory §7 seam: the PoP will channel-bind to THIS h — both ends must compute the same value.
        assertContentEquals(tunnel.handshakeHash, responderH, "initiator h == responder h (CI-2)")

        tunnel.close()
        job.cancel()
    }

    @Test
    fun wrongPinnedKey_failsClosed_misrouteOrMitmCaught() = runBlocking {
        val (clientRelay, hubRelay) = InMemoryRelayChannel.pair()
        val (responder, _) = newResponderWithStatic() // responder uses its REAL static
        val (_, wrongStatic) = newResponderWithStatic() // client pins a DIFFERENT static
        val job = launchResponder(hubRelay, responder)

        // es against the wrong static diverges → the responder's readMessage fails → EOF → fail-closed (CI-1).
        assertFailsWith<NoiseHandshakeException> {
            NoiseJavaClientTransport().connect(wrongStatic, clientRelay)
        }
        job.cancel()
    }

    @Test
    fun badSizedPin_failsClosed_beforeTouchingRelay() = runBlocking {
        val (clientRelay, _) = InMemoryRelayChannel.pair()
        assertFailsWith<NoiseHandshakeException> {
            NoiseJavaClientTransport().connect(ByteArray(16), clientRelay) // 16B ≠ 32B X25519
        }
    }
}
