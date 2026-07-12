package com.tneff.cyppieagents.transport

import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.HandshakeState
import com.tneff.cyppieagents.crypto.RawKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * CYP-457 (S1) — the [NoiseJavaServerTerminator] (NK **responder**) against a CYP-443-style `noise-java` **initiator**
 * over an in-memory relay, using the **real S-C `dhKey` X25519 static** ([RawKeys.generateX25519]). These are the
 * honesty teeth for the four AC:
 *  - **AC1/AC4** — the handshake completes on the injected S-C static, the initiator that pins S-C's `dhPubKey`
 *    succeeds (proving noise-java re-derives the SAME public from the JDK scalar), `h` is 32 B and **equal on both
 *    ends** (feeds S-E `cb`), and app traffic round-trips both directions through the AEAD tunnel.
 *  - **AC2 (★ money-tooth)** — a reordered / tampered transport frame → `AEADBadTagException` → the tunnel **RESETS**:
 *    no resync, no silent replay; even a subsequently-correct frame is refused, and the send path is fail-closed too.
 *  - **AC3** — the injected static is a 32-byte scalar; a wrong-sized static fails closed before any relay I/O.
 */
class Cyp457NoiseTerminatorTest {

    /** In-memory duplex [ServerRelayChannel] pair (ordered, reliable; closing a side → the peer's `receive()==null`). */
    private class InMemRelay(
        private val outbound: Channel<ByteArray>,
        private val inbound: Channel<ByteArray>,
    ) : ServerRelayChannel {
        override suspend fun send(frame: ByteArray) { outbound.send(frame.copyOf()) }
        override suspend fun receive(): ByteArray? = inbound.receiveCatching().getOrNull()
        override suspend fun close() { outbound.close() }
        companion object {
            /** (clientSide, hubSide): what one sends, the other receives. */
            fun pair(): Pair<InMemRelay, InMemRelay> {
                val c2h = Channel<ByteArray>(Channel.UNLIMITED)
                val h2c = Channel<ByteArray>(Channel.UNLIMITED)
                return InMemRelay(outbound = c2h, inbound = h2c) to InMemRelay(outbound = h2c, inbound = c2h)
            }
        }
    }

    /** A CYP-443-style noise-java **initiator** that pins [hubStatic] and runs NK over [relay]; returns `(h, pair)`
     *  so the test can drive/tamper raw transport frames directly (the client side of the tunnel). */
    private suspend fun runInitiator(hubStatic: ByteArray, relay: ServerRelayChannel): Pair<ByteArray, CipherStatePair> {
        val hs = HandshakeState(NOISE_NK_SUITE, HandshakeState.INITIATOR)
        try {
            hs.remotePublicKey.setPublicKey(hubStatic, 0)
            hs.start()
            val buf = ByteArray(NOISE_MAX_FRAME)
            val len1 = hs.writeMessage(buf, 0, null, 0, 0)         // e, es
            relay.send(buf.copyOf(len1))
            val msg2 = relay.receive() ?: error("relay closed during the NK handshake")
            hs.readMessage(msg2, 0, msg2.size, ByteArray(NOISE_MAX_FRAME), 0) // e, ee
            check(hs.action == HandshakeState.SPLIT) { "initiator handshake incomplete (action=${hs.action})" }
            val h = hs.handshakeHash.copyOf()
            return h to hs.split()
        } finally {
            hs.destroy()
        }
    }

    private fun enc(pair: CipherStatePair, msg: String): ByteArray {
        val pt = msg.encodeToByteArray()
        val ct = ByteArray(pt.size + pair.sender.getMACLength())
        val n = pair.sender.encryptWithAd(null, pt, 0, ct, 0, pt.size)
        return if (n == ct.size) ct else ct.copyOf(n)
    }
    private fun dec(pair: CipherStatePair, ct: ByteArray): String {
        val pt = ByteArray(ct.size)
        val n = pair.receiver.decryptWithAd(null, ct, 0, pt, 0, ct.size)
        return pt.copyOf(n).decodeToString()
    }

    /** Establish a live tunnel on a fresh S-C static; returns the hub [ServerNoiseTunnel], the client cipher [pair],
     *  the two relay ends, and both `h` values. */
    private data class Established(
        val tunnel: ServerNoiseTunnel,
        val clientPair: CipherStatePair,
        val clientRelay: InMemRelay,
        val hubH: ByteArray,
        val clientH: ByteArray,
        val dhPubKey: ByteArray,
    )
    private suspend fun establish(): Established = coroutineScope {
        val dh = RawKeys.generateX25519()
        val terminator = NoiseJavaServerTerminator(dh.privateRaw)
        val (clientRelay, hubRelay) = InMemRelay.pair()
        val termDeferred = async(Dispatchers.Default) { terminator.terminate(hubRelay) }
        val (clientH, clientPair) = runInitiator(dh.publicRaw, clientRelay) // client pins S-C dhPubKey
        val tunnel = termDeferred.await()
        Established(tunnel, clientPair, clientRelay, tunnel.handshakeHash, clientH, dh.publicRaw)
    }

    @Test
    fun nkResponder_onScStatic_completes_hMatches_andRoundtripsBothWays() = runBlocking {
        val e = establish()

        // AC1: h is the 32-byte BLAKE2s hash, equal on both ends (the value the PoP will channel-bind to).
        assertEquals(32, e.hubH.size, "BLAKE2s h is 32 bytes")
        assertContentEquals(e.clientH, e.hubH, "initiator h == responder h (CI-2, feeds S-E cb)")

        // client -> hub
        e.clientRelay.send(enc(e.clientPair, "ping-hub"))
        assertEquals("ping-hub", e.tunnel.receive()?.decodeToString(), "client→hub app traffic decrypts")
        // hub -> client
        e.tunnel.send("pong-client".encodeToByteArray())
        val back = e.clientRelay.receive()
        assertNotNull(back, "hub→client frame delivered")
        assertEquals("pong-client", dec(e.clientPair, back), "hub→client app traffic decrypts")

        e.tunnel.close()
    }

    @Test
    fun scStatic_injected_derivesPublic_equalTo_dhPubKey() {
        // AC4 direct: feeding the JDK-generated X25519 scalar into noise-java derives EXACTLY S-C's published
        // dhPubKey (both clamp the same scalar, RFC 7748). If this drifted, every client pin would fail.
        val dh = RawKeys.generateX25519()
        val hs = HandshakeState(NOISE_NK_SUITE, HandshakeState.RESPONDER)
        try {
            hs.localKeyPair.setPrivateKey(dh.privateRaw, 0)
            val derived = ByteArray(hs.localKeyPair.publicKeyLength)
            hs.localKeyPair.getPublicKey(derived, 0)
            assertContentEquals(dh.publicRaw, derived, "noise-java responder public == S-C dhPubKey (the pinned static)")
        } finally {
            hs.destroy()
        }
    }

    // runBlocking<Unit>: these methods end in assertFailsWith (which returns the caught exception). Pinning the type
    // param to Unit keeps the @Test method's return type Unit — a non-Unit @Test → JUnit4 InvalidTestClassError (CYP-441).
    @Test
    fun reorderedFrame_resetsChannel_noResync() = runBlocking<Unit> {
        val e = establish()
        // Positive precondition: a normal frame round-trips first (so the reset assertions aren't vacuously green).
        e.clientRelay.send(enc(e.clientPair, "warmup"))
        assertEquals("warmup", e.tunnel.receive()?.decodeToString(), "channel is live before the reorder")

        // The initiator encrypts two ordered frames; deliver the SECOND first (reorder).
        val c1 = enc(e.clientPair, "m1") // sender nonce n1
        val c2 = enc(e.clientPair, "m2") // sender nonce n2
        e.clientRelay.send(c2)
        assertFailsWith<ServerNoiseException>("a reordered frame → AEADBadTag → fail-closed") { e.tunnel.receive() }

        // ★ No resync: the now-in-order frame c1 is REFUSED because the channel reset (a resync/skip would accept
        // "m1" — the receiver nonce did not advance on the failed decrypt). Removing the reset → this returns "m1" = RED.
        e.clientRelay.send(c1)
        assertFailsWith<ServerNoiseException>("channel is reset — no resync, the correct-nonce frame is refused too") {
            e.tunnel.receive()
        }
        // The send path is fail-closed after the reset, too.
        assertFailsWith<ServerNoiseException>("send fail-closed after reset") {
            e.tunnel.send("x".encodeToByteArray())
        }
    }

    @Test
    fun tamperedFrame_failsClosed_thenChannelDead() = runBlocking<Unit> {
        val e = establish()
        val c0 = enc(e.clientPair, "m0") // nonce n0
        val tampered = c0.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }

        e.clientRelay.send(tampered)
        assertFailsWith<ServerNoiseException>("a tampered frame → AEADBadTag → fail-closed") { e.tunnel.receive() }

        // The clean original frame (still valid at nonce n0, which did not advance) is refused — one bad frame kills
        // the channel (truncation/tamper guard). Removing the reset → this decrypts "m0" = RED.
        e.clientRelay.send(c0)
        assertFailsWith<ServerNoiseException>("channel dead after a tampered frame — even the clean frame is refused") {
            e.tunnel.receive()
        }
    }

    @Test
    fun wrongSizedStatic_failsClosed_beforeAnyRelayIo() {
        assertFailsWith<IllegalArgumentException>("a 16-byte static is rejected at construction (never 32 B X25519)") {
            NoiseJavaServerTerminator(ByteArray(16))
        }
    }

    @Test
    fun wrongPinnedKey_failsClosed_misrouteOrMitmCaught() = runBlocking {
        // The responder uses its REAL S-C static; the initiator pins a DIFFERENT hub static → the `es` in NK msg1
        // diverges → the responder's readMessage fails the AEAD tag → fail-closed (misroute/MITM caught). Mirror of
        // the client CI-1 tooth. The terminator does NOT own the relay, so the stalled initiator is just cancelled.
        val real = RawKeys.generateX25519()
        val wrong = RawKeys.generateX25519()
        coroutineScope {
            val terminator = NoiseJavaServerTerminator(real.privateRaw)
            val (clientRelay, hubRelay) = InMemRelay.pair()
            val initJob = launch(Dispatchers.Default) { runCatching { runInitiator(wrong.publicRaw, clientRelay) } }
            assertFailsWith<ServerNoiseException>("wrong pinned static → responder readMessage fails → fail-closed") {
                terminator.terminate(hubRelay)
            }
            initJob.cancel() // it is hung awaiting msg2 that a failed responder never sends
        }
    }
}
