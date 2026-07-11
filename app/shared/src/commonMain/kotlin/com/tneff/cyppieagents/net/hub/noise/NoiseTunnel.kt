package com.tneff.cyppieagents.net.hub.noise

/**
 * CYP-443 Slice 1 (Epic CYP-427 Phase-2 Remote) — the Noise-E2E transport seams. Design: doc 16 §7 (RR1=NK,
 * RR6-ii) + CYP-440 §3.1. **The tunnel is a transparent, ordered byte substrate** (PO 2026-07-11): the existing
 * hub HTTP/1.1 + WS protocol runs over it **unchanged**, like HTTP over TLS — no new mux, no WS-in-WS. Remote is
 * **always Noise** (RR8): there is no plaintext path here.
 */

/** The pinned suite (RR1) — pinned by the protocol name, never negotiated. CR1-spike-verified against noise-java. */
const val NOISE_NK_SUITE = "Noise_NK_25519_ChaChaPoly_BLAKE2s"

/**
 * The framed, ordered duplex pipe to the hub **via the relay** — both the Noise handshake and the post-handshake
 * app traffic flow over it. Phase-2 actual = the relay WebSocket (opaque rendezvous, RR4/RR5); tests use an
 * in-memory pair. Ordered + reliable (the relay WS gives that); a `null` receive = the peer closed (fail-closed).
 */
interface RelayChannel {
    suspend fun send(frame: ByteArray)
    /** Next inbound frame, or `null` when the channel is closed/EOF. */
    suspend fun receive(): ByteArray?
    suspend fun close()
}

/**
 * The post-handshake **encrypted** duplex over a [RelayChannel]. Exposes the Noise **handshake hash `h`** — the
 * mandatory §7 seam: the operator PoP is channel-bound to THIS `h` (CI-2), so without it Slice 2 cannot bind the
 * PoP and must fail closed. Each [send]/[receive] is one Noise transport message (AEAD, per-message nonce handled
 * by the cipher state). Byte-stream / length-prefix framing over these messages is the separate framing-spec that
 * feeds the CR3 Ktor engine (reconciled with Backend before that engine is wired).
 */
interface NoiseTunnel {
    /** The Noise handshake hash `h` (32 B, BLAKE2s). Defensive copy — the caller must not mutate the internal state. */
    val handshakeHash: ByteArray
    suspend fun send(plaintext: ByteArray)
    /** Next decrypted message, or `null` when the tunnel/relay closed. */
    suspend fun receive(): ByteArray?
    suspend fun close()
}

/**
 * The Noise_NK **initiator** (RR1), client strand. Consumes the **pinned** hub X25519 static (CI-1 — never the
 * CP-registry key once a pin exists), runs the NK handshake over [RelayChannel], and returns the [NoiseTunnel]
 * exposing `h`. Desktop-JVM actual = `NoiseJavaClientTransport` (Signal noise-java); native/web follow (RR6-ii).
 * Modeled as an **interface** (not `expect`/`actual`) so commonMain code + tests inject a fake and only the JVM
 * composition root wires the real crypto — the platform binding lives at the edge, not in every source set.
 */
interface ClientNoiseTransport {
    /**
     * Fail-closed: a wrong/absent hub key makes the `es`/`ee` derivation fail → the handshake breaks
     * (misroute/MITM caught, CI-1) → [NoiseHandshakeException]. ONE suite ([NOISE_NK_SUITE]), no negotiation;
     * all version/param bytes bound in [prologue]; ephemerals never reused.
     */
    suspend fun connect(
        pinnedHubStatic: ByteArray,
        relay: RelayChannel,
        prologue: ByteArray = ByteArray(0),
    ): NoiseTunnel
}

/** The handshake did not complete — wrong/absent hub key, a broken/closed relay, or a protocol error. Fail-closed. */
class NoiseHandshakeException(message: String, cause: Throwable? = null) : Exception(message, cause)
