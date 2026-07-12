package com.tneff.cyppieagents.transport

/**
 * CYP-457 (S1) — the server-side Noise-transport **seams** (compiling interfaces only; the Noise_NK **responder**
 * crypto-core impl — noise-java on the S-C `dhKey` static — is the RR5-gated build, NOT here). These mirror the
 * client seams (`app/shared/.../net/hub/noise`: `RelayChannel` + `NoiseTunnel`) 1:1 so both ends speak the LOCKED
 * `CYP-443-tunnel-framing-spec.md`. Everything here is INERT until an explicit Phase-2-Remote-GO.
 */

/**
 * L0 — the ordered, reliable duplex **frame** pipe to the hub via the relay (mirror of the client `RelayChannel`).
 * Each [send] is one L1 Noise message; `receive()==null` = the channel closed (fail-closed). The relay is untrusted
 * (ciphertext + sizes/timing only).
 */
interface ServerRelayChannel {
    suspend fun send(frame: ByteArray)
    /** Next inbound frame, or `null` when the channel is closed/EOF. */
    suspend fun receive(): ByteArray?
    suspend fun close()
}

/**
 * L1/L2 — the post-handshake **encrypted duplex** as the Noise_NK **responder** (mirror of the client `NoiseTunnel`).
 * Exposes the handshake hash `h` — the S-E channel-binding seam (`cb == base64url(SHA-256(h ‖ hubId))`). Each
 * [send]/[receive] is one Noise transport message (AEAD, sequential per-message nonce owned by the cipher state); a
 * dropped/reordered/duplicated frame → `AEADBadTagException` → the tunnel **resets** (fail-closed, no resync).
 */
interface ServerNoiseTunnel {
    /** The Noise handshake hash `h` (32 B, BLAKE2s). Available post-handshake. Defensive copy — do not mutate. */
    val handshakeHash: ByteArray
    suspend fun send(plaintext: ByteArray)
    /** Next decrypted message, or `null` when the tunnel/relay closed. */
    suspend fun receive(): ByteArray?
    suspend fun close()
}

/**
 * CYP-457 — the responder terminator seam: given an [ServerRelayChannel] (L0), run the NK responder handshake on the
 * hub's `dhKey` static and yield the [ServerNoiseTunnel] (L2 + `h`). **Interface only** — the noise-java impl is the
 * RR5-gated crypto-core (CYP-457 build).
 */
interface ServerNoiseTerminator {
    /** Complete the NK responder handshake over [relay] and return the encrypted duplex. Fail-closed on any error. */
    suspend fun terminate(relay: ServerRelayChannel): ServerNoiseTunnel
}
