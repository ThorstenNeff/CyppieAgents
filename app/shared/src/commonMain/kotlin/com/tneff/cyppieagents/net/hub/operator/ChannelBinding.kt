package com.tneff.cyppieagents.net.hub.operator

/**
 * CYP-496 — the Noise channel-binding `cb = base64url(SHA-256(h ‖ hubId))` the [CpJwtProvider] sends to the CP so
 * the minted ticket is bound to THIS session's handshake hash `h` (the hub's RR3 gate re-derives it and checks
 * `cb == SHA-256(live-h ‖ hubId)`, anti-cross-session-replay).
 *
 * An **injected seam** so the byte-critical derivation is ONE source of truth: the real impl is the shared `:core`
 * `channelBinding` helper (CYP-514, Backend-authored and used by the hub verifier too — compiler-guaranteed
 * byte-identical, so the client never re-implements the crypto and cannot drift). Tests inject a fake.
 */
fun interface ChannelBinding {
    fun compute(handshakeHash: ByteArray, hubId: String): String
}
