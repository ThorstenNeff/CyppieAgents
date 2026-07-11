package com.tneff.cyppieagents.net.hub.noise

import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.HandshakeState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * CYP-443 Slice 1 — the Desktop-JVM [ClientNoiseTransport] actual over Signal's `noise-java` (CR1-spike-verified:
 * `Noise_NK_25519_ChaChaPoly_BLAKE2s` + `getHandshakeHash()`). Runs the crypto **outside the CP origin** (RR6-ii)
 * — plaintext lives only in this process. Native/web actuals follow.
 */
class NoiseJavaClientTransport : ClientNoiseTransport {

    override suspend fun connect(
        pinnedHubStatic: ByteArray,
        relay: RelayChannel,
        prologue: ByteArray,
    ): NoiseTunnel {
        val hs = try {
            HandshakeState(NOISE_NK_SUITE, HandshakeState.INITIATOR)
        } catch (e: Exception) {
            throw NoiseHandshakeException("cannot init Noise suite $NOISE_NK_SUITE", e)
        }
        try {
            if (prologue.isNotEmpty()) hs.setPrologue(prologue, 0, prologue.size)
            // NK pre-message `<- s`: the initiator KNOWS the responder static in advance — the PIN (CI-1).
            val remote = hs.remotePublicKey
            if (pinnedHubStatic.size != remote.publicKeyLength) {
                throw NoiseHandshakeException(
                    "pinned hub static is ${pinnedHubStatic.size}B, expected ${remote.publicKeyLength}B (X25519)",
                )
            }
            remote.setPublicKey(pinnedHubStatic, 0)
            hs.start()

            val buf = ByteArray(NOISE_MAX_FRAME)
            // NK message 1: initiator -> responder  (e, es)
            val len1 = hs.writeMessage(buf, 0, null, 0, 0)
            relay.send(buf.copyOf(len1))
            // NK message 2: responder -> initiator  (e, ee)
            val msg2 = relay.receive()
                ?: throw NoiseHandshakeException("relay closed during the NK handshake")
            hs.readMessage(msg2, 0, msg2.size, ByteArray(NOISE_MAX_FRAME), 0)

            if (hs.action != HandshakeState.SPLIT) {
                throw NoiseHandshakeException("NK handshake did not complete (action=${hs.action})")
            }
            // `h` BEFORE split (and before destroy) — per-session unique, both ends compute it (CI-2).
            val h = hs.handshakeHash.copyOf()
            val pair: CipherStatePair = hs.split()
            return NoiseJavaTunnel(relay, pair, h)
        } catch (e: NoiseHandshakeException) {
            throw e
        } catch (e: Exception) {
            // Wrong hub key / misrouted rendezvous ⇒ es/ee fails ⇒ AEADBadTagException etc. ⇒ FAIL CLOSED (CI-1).
            throw NoiseHandshakeException("NK handshake failed (wrong hub key / misroute / relay error)", e)
        } finally {
            hs.destroy() // wipe ephemeral/handshake key material; the split CipherStatePair is independent
        }
    }
}

/** Post-handshake encrypted duplex. `noise-java` [CipherState]s are stateful (per-message nonce) + not thread-safe;
 *  each direction is serialized so nonce order + on-wire order are preserved. */
internal class NoiseJavaTunnel(
    private val relay: RelayChannel,
    private val pair: CipherStatePair,
    private val h: ByteArray,
) : NoiseTunnel {
    private val sendLock = Mutex()
    private val receiveLock = Mutex()
    private val sender = pair.sender
    private val receiver = pair.receiver

    override val handshakeHash: ByteArray get() = h.copyOf() // defensive copy — never hand out the internal array

    override suspend fun send(plaintext: ByteArray) {
        val maxPayload = NOISE_MAX_FRAME - sender.getMACLength()
        if (plaintext.size > maxPayload) {
            // The byte-stream/length-prefix framing (the separate framing spec) chunks; a single Noise message is capped.
            throw NoiseHandshakeException("plaintext ${plaintext.size}B exceeds one Noise frame ($maxPayload B) — framing layer must chunk")
        }
        sendLock.withLock {
            val ct = ByteArray(plaintext.size + sender.getMACLength())
            val n = sender.encryptWithAd(null, plaintext, 0, ct, 0, plaintext.size)
            relay.send(if (n == ct.size) ct else ct.copyOf(n))
        }
    }

    override suspend fun receive(): ByteArray? {
        val frame = relay.receive() ?: return null
        receiveLock.withLock {
            val out = ByteArray(frame.size)
            val n = try {
                receiver.decryptWithAd(null, frame, 0, out, 0, frame.size)
            } catch (e: Exception) {
                // A tampered/forged frame ⇒ the tunnel is compromised ⇒ fail closed (never return partial plaintext).
                throw NoiseHandshakeException("Noise transport decrypt failed (tampered frame)", e)
            }
            return out.copyOf(n)
        }
    }

    override suspend fun close() {
        try {
            pair.destroy()
        } finally {
            relay.close()
        }
    }
}

/** Noise messages are capped at 65535 bytes on the wire (2-byte length in the spec's transport encoding). */
internal const val NOISE_MAX_FRAME = 65535
