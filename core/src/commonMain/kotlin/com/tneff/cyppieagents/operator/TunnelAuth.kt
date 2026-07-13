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
 *
 * [devicePublicKey] (CYP-525) — the operator's **raw-32B** Ed25519 device public key, carried ONLY for **TOFU
 * first-enroll**: on an empty hub store the gate anchors this key (after proving possession via [pop]) as the device
 * the operator authenticates with thereafter. Additive + nullable (default `null`) — steady-state connects (device
 * already enrolled) omit it, and the wire stays backward-compatible. **Raw-32B is the ratified wire form** — the client
 * converts its X.509 `KeyPair.public.encoded` via [ed25519SpkiToRaw] before sending; the hub reads it via
 * [ed25519PublicKeyToRaw]. The device **owner** is NEVER this payload — it is the CpJwt-authenticated operator
 * (CT-2b), so a present key can only enroll under a valid operator session (no land-grab).
 */
@Serializable
data class TunnelAuthRequest(
    val cpJwt: String,
    val pop: OperatorPoPWire,
    val nonce: ByteArray,
    val devicePublicKey: ByteArray? = null,
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
    /**
     * CYP-525 GE5/GE7 — **hub-authoritative** first-vs-recurring signal. `true` iff this connect performed a TOFU
     * first-enroll (the hub store was not yet Finalized). The client shows the RecoveryCodesReveal iff this is `true`,
     * regardless of its own local `isEnrolled` (a client that thinks it is enrolled but whose provisional the hub
     * discarded is told `firstEnroll=true` and re-reveals FRESH codes). Additive/nullable-safe (default `false` =
     * steady-state). On `true`, an [EnrollResponse] follows on the tunnel before the byte-bridge, then the client
     * confirms with a [SavedAck] and the hub Finalizes (persist code-hashes + set the anchor, atomically).
     */
    val firstEnroll: Boolean = false,
)

/**
 * CYP-525 GE5/GE7 — the hub's **one-time backup-code reveal**, sent immediately after a first-enroll grant
 * (`firstEnroll=true`), **before** the byte-bridge, **E2E over the Noise tunnel** (the relay is blind). The ONLY time
 * the code plaintexts exist on the wire; the hub keeps only salted-SHA-256 hashes (persisted durably ONLY at Finalize).
 */
@Serializable
data class EnrollResponse(val backupCodes: List<String>)

/**
 * CYP-525 GE5/GE7 — the client's **user-saved** confirmation (NOT a mere receipt): the operator explicitly confirmed
 * they saved the codes. Only on this does the hub **Finalize** (persist code-hashes durably AND set the device anchor,
 * atomically, anchor last). No `SavedAck` (drop/close/restart) → the provisional enroll is discarded (never anchored) →
 * the next connect re-runs TOFU with FRESH codes. This is the no-lockout gate.
 */
@Serializable
data class SavedAck(val ok: Boolean = true)
