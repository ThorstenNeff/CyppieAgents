package com.tneff.cyppieagents.transport

import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.HandshakeState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One Noise transport message is capped at 65535 B on the wire (2-byte length in the framing encoding). */
internal const val NOISE_MAX_FRAME = 65535

/**
 * The pinned Noise suite (RR1) — pinned by protocol name, never negotiated. It MUST stay byte-identical to the client
 * `NOISE_NK_SUITE` (`:app:shared` `net/hub/noise/NoiseTunnel.kt`) and the LOCKED `CYP-443-tunnel-framing-spec.md`.
 * **Mirrored** here (not shared via `:core`) to keep this crypto-core decoupled — consistent with the ratified
 * mirror-not-share seam design (`ServerRelayChannel`/`ServerNoiseTunnel` are likewise server-side mirrors of the
 * client seams). A drift would fail the NK handshake closed. (Single-sourcing to `:core` is a design call for the PO.)
 */
internal const val NOISE_NK_SUITE = "Noise_NK_25519_ChaChaPoly_BLAKE2s"

/** The NK responder handshake did not complete, or the tunnel failed — **fail-closed**. Server mirror of the client
 *  `NoiseHandshakeException`; carries a non-secret message for the hub's own logs. */
class ServerNoiseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * CYP-457 (S1) — the server (hub) **[ServerNoiseTerminator]** over Signal's `noise-java`: the Noise_NK **responder**
 * on the hub's S-C `dhKey` X25519 static. It is the exact counterpart of Dev's client `NoiseJavaClientTransport`
 * (INITIATOR) — same pinned suite [NOISE_NK_SUITE], no negotiation, all params bound in the prologue (RR1/RR8).
 *
 * **AC4 — the static is CONSUMED, never generated ad-hoc:** [dhStaticPrivate] is the raw 32-byte X25519 scalar from
 * S-C `HubIdentity` (`hub.dhKey`, private via the S-B `SecretStore`). noise-java derives the responder's public from
 * it (`Curve25519.eval` with RFC-7748 clamping), so it equals S-C's published `dhPubKey` — the value the client pins.
 * The wiring of the real static (from the SecretStore) is CYP-459 (boot, INERT/opt-in); this class only accepts it.
 *
 * **AC1 — the handshake hash `h`** is captured before `split()` and exposed on the [ServerNoiseTunnel]; both ends
 * compute the same `h` (CI-2) → it feeds the S-E channel-binding `cb`. **AC2 — sequential-nonce fail-closed:** a
 * dropped/reordered/duplicated/tampered transport frame → `AEADBadTagException` → the tunnel **RESETS** (no resync,
 * no silent replay; even a subsequent valid frame is refused). **AC3 — zero plaintext before the handshake completes.**
 */
class NoiseJavaServerTerminator(
    private val dhStaticPrivate: ByteArray,
    /** The version/param bytes bound into the handshake (RR1). MUST be byte-identical to the initiator's, or the
     *  handshake MAC diverges → fail-closed. Empty in the crypto-core tests; the real value is set by CYP-458/459. */
    private val prologue: ByteArray = ByteArray(0),
) : ServerNoiseTerminator {

    init {
        require(dhStaticPrivate.size == 32) {
            "hub X25519 static must be a 32-byte scalar, was ${dhStaticPrivate.size} B"
        }
    }

    override suspend fun terminate(relay: ServerRelayChannel): ServerNoiseTunnel {
        val hs = try {
            HandshakeState(NOISE_NK_SUITE, HandshakeState.RESPONDER)
        } catch (e: Exception) {
            throw ServerNoiseException("cannot init Noise suite $NOISE_NK_SUITE", e)
        }
        try {
            if (prologue.isNotEmpty()) hs.setPrologue(prologue, 0, prologue.size)
            // NK pre-message `<- s`: the responder holds its OWN static. Inject the hub `dhKey` scalar — noise-java
            // derives the matching public (so the client's pin against S-C `dhPubKey` succeeds). Never generated here.
            val local = hs.localKeyPair
            if (dhStaticPrivate.size != local.privateKeyLength) {
                throw ServerNoiseException(
                    "hub static is ${dhStaticPrivate.size} B, expected ${local.privateKeyLength} B (X25519)",
                )
            }
            local.setPrivateKey(dhStaticPrivate, 0)
            hs.start()

            val scratch = ByteArray(NOISE_MAX_FRAME)
            // NK message 1: initiator -> responder  (e, es)
            val msg1 = relay.receive() ?: throw ServerNoiseException("relay closed before the NK handshake")
            hs.readMessage(msg1, 0, msg1.size, scratch, 0)
            // NK message 2: responder -> initiator  (e, ee)
            val len2 = hs.writeMessage(scratch, 0, null, 0, 0)
            relay.send(scratch.copyOf(len2))

            if (hs.action != HandshakeState.SPLIT) {
                throw ServerNoiseException("NK handshake did not complete (action=${hs.action})")
            }
            // `h` BEFORE split/destroy — per-session unique, both ends compute the same value (CI-2, feeds S-E cb).
            val h = hs.handshakeHash.copyOf()
            val pair: CipherStatePair = hs.split()
            return NoiseJavaServerTunnel(relay, pair, h)
        } catch (e: ServerNoiseException) {
            throw e
        } catch (e: Exception) {
            // A wrong static / misroute / bad initiator ⇒ es/ee diverge ⇒ AEADBadTagException ⇒ FAIL CLOSED.
            throw ServerNoiseException("NK responder handshake failed (misroute / relay error / bad initiator)", e)
        } finally {
            hs.destroy() // wipe ephemeral/handshake material; the split CipherStatePair is independent
        }
    }
}

/**
 * Post-handshake encrypted duplex as the responder. `noise-java` [com.southernstorm.noise.protocol.CipherState]s are
 * stateful (per-message nonce) and not thread-safe; each direction is serialized so nonce order == on-wire order.
 * **Fail-closed & non-resyncing:** any decrypt failure RESETS the tunnel — no partial plaintext, no skip-and-continue.
 */
internal class NoiseJavaServerTunnel(
    private val relay: ServerRelayChannel,
    private val pair: CipherStatePair,
    private val h: ByteArray,
) : ServerNoiseTunnel {
    private val sendLock = Mutex()
    private val receiveLock = Mutex()
    private val sender = pair.sender
    private val receiver = pair.receiver
    @Volatile private var reset = false

    override val handshakeHash: ByteArray get() = h.copyOf() // defensive copy — never hand out the internal array

    override suspend fun send(plaintext: ByteArray) {
        if (reset) throw ServerNoiseException("tunnel is reset (a prior frame failed) — fail-closed")
        val maxPayload = NOISE_MAX_FRAME - sender.getMACLength()
        if (plaintext.size > maxPayload) {
            throw ServerNoiseException(
                "plaintext ${plaintext.size} B exceeds one Noise frame ($maxPayload B) — the framing layer must chunk",
            )
        }
        sendLock.withLock {
            val ct = ByteArray(plaintext.size + sender.getMACLength())
            val n = sender.encryptWithAd(null, plaintext, 0, ct, 0, plaintext.size)
            relay.send(if (n == ct.size) ct else ct.copyOf(n))
        }
    }

    override suspend fun receive(): ByteArray? {
        if (reset) throw ServerNoiseException("tunnel is reset (a prior frame failed) — fail-closed")
        val frame = relay.receive() ?: return null
        receiveLock.withLock {
            val out = ByteArray(frame.size)
            val n = try {
                receiver.decryptWithAd(null, frame, 0, out, 0, frame.size)
            } catch (e: Exception) {
                // ★ AC2 money-tooth: a dropped/reordered/duplicated/tampered frame → AEADBadTagException. RESET the
                // whole tunnel — NO resync, NO silent replay, NO partial plaintext. The channel is dead; a later
                // in-order frame is refused too. (A resync/skip here would accept reordered ciphertext = the RED.)
                resetTunnel()
                throw ServerNoiseException("Noise transport decrypt failed (tampered/reordered frame) — channel reset", e)
            }
            return out.copyOf(n)
        }
    }

    private fun resetTunnel() {
        reset = true
        runCatching { pair.destroy() }
    }

    override suspend fun close() {
        reset = true
        try {
            pair.destroy()
        } finally {
            relay.close()
        }
    }
}
