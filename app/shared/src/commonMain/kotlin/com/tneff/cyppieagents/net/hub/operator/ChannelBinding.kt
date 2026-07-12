package com.tneff.cyppieagents.net.hub.operator

import com.tneff.cyppieagents.net.hub.trust.Sha256
import com.tneff.cyppieagents.operator.channelBindingInput
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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

/**
 * CYP-513/514 — the production [ChannelBinding]: `cb = base64url-no-pad(SHA-256(channelBindingInput(h, hubId)))`.
 * The byte-critical INPUT (`h ‖ hubId.utf8`, h-first, no length-prefix) is the shared `:core`
 * [com.tneff.cyppieagents.operator.channelBindingInput] — the SAME function the hub's RR3 verifier hashes, so
 * client and hub are compiler-tied (no cross-side drift). The SHA-256 + base64url-no-pad are standard, pinned by
 * the golden-vector tooth against the hub-verifier's vector.
 */
@OptIn(ExperimentalEncodingApi::class)
fun coreChannelBinding(): ChannelBinding = ChannelBinding { handshakeHash, hubId ->
    Base64.UrlSafe.encode(Sha256.digest(channelBindingInput(handshakeHash, hubId))).trimEnd('=')
}
