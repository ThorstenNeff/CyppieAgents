package com.tneff.cyppieagents.operator

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * CYP-459 (Epic CYP-427 Phase-2) — the **RR3 tunnel-auth wire contract** (`:core`, shared byte-identically by the hub
 * server gate and the client `authenticate()` — CYP-486). Immediately after the Noise handshake, before ANY HTTP
 * byte, the client sends **one** [TunnelAuthRequest] on the tunnel; the hub verifies it against the LIVE handshake
 * hash `h` and replies **one** [TunnelAuthGrant]; only on `granted` does the byte-bridge start (else the tunnel is
 * closed — reject is terminal). This is an **orthogonal second gate at the tunnel boundary**: the loopback bridge
 * stays a dumb byte-pump and the route re-verifies its own credential (T2), so RR3 trust is never laundered into a
 * route.
 *
 * **[OperatorPoPWire] consolidates** the previously hand-mirrored `DevicePoP` (client) / `OperatorDevicePoP` (server)
 * into ONE `:core` wire form (the CYP-473-H2 precedent — kills mirror drift). Each side keeps its internal type and
 * maps at the wire edge (additive-safe). Both branches are channel-bound to `h` via [operatorAuthChallenge].
 */
@Serializable
sealed interface OperatorPoPWire {
    /** Cross-platform base: a raw Ed25519 signature by the software device-key over the channel-bound challenge. */
    @Serializable
    @SerialName("raw")
    data class Raw(val signature: ByteArray) : OperatorPoPWire

    /** Platform authenticator (CTAP2): sig over `authenticatorData ‖ SHA-256(challenge)`; the UV flag MUST be set. */
    @Serializable
    @SerialName("fido2")
    data class Fido2(
        val credentialId: ByteArray,
        val authenticatorData: ByteArray,
        val signature: ByteArray,
    ) : OperatorPoPWire
}

/**
 * The operator's first tunnel message: the CP-minted identity JWT ([cpJwt]), the device proof-of-possession ([pop],
 * channel-bound to the live `h`), and the freshness [nonce] (the PoP challenge input, single-use at the hub).
 */
@Serializable
data class TunnelAuthRequest(
    val cpJwt: String,
    val pop: OperatorPoPWire,
    val nonce: ByteArray,
)

/**
 * The hub's grant reply. [granted] = the AND of the CpJwt verify (a∧b∧c incl. channel-binding) and the PoP verify,
 * both against the live `h`. [reason] is a **stable, non-secret** reject code (for the client's honest error) — never
 * an oracle: the code is identical whether a forged marker is present or absent (the differential zero-privilege claim).
 */
@Serializable
data class TunnelAuthGrant(
    val granted: Boolean,
    val reason: String? = null,
)
