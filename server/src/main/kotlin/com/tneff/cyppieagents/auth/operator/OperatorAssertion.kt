package com.tneff.cyppieagents.auth.operator

/**
 * CYP-469 (Phase-2 Operator-Auth, server) — the server verify-side PoP types. The verifier
 * ([OperatorAssertionVerifier]) recomputes the channel-bound challenge from ITS OWN live Noise `h` + `hubId` + the
 * carried nonce and checks the operator's device signature — the CP forges the identity token but **never** this
 * (RR2-B; the operator holds the key).
 *
 * **CYP-473 H2:** the challenge derivation is now the SINGLE `:core`
 * [com.tneff.cyppieagents.operator.operatorAuthChallenge] shared with the client (was hand-mirrored here) — no more
 * drift. Transport-independent: verified with a stub `h` in tests; the live-`h` tunnel wiring is RR5-gated.
 */

/**
 * The operator PoP as it arrives at the server — the wire mirror of the client `DevicePoP` (server-authoritative,
 * no client downgrade). Two OS-selected branches, both channel-bound via the `:core` `operatorAuthChallenge`.
 */
sealed interface OperatorDevicePoP {
    /** Cross-platform base: a raw Ed25519 signature by the software device-key over the challenge (verifier = plain
     *  Ed25519 verify). App-PIN UV is enforced client-side (not server-verifiable for this branch). */
    data class Raw(val signature: ByteArray) : OperatorDevicePoP

    /** Platform authenticator (CTAP2): sig over `authenticatorData ‖ SHA-256(challenge)`; the **UV flag** in
     *  authenticatorData MUST be set (server-verifiable); signCount lenient. */
    data class Fido2(
        val credentialId: ByteArray,
        val authenticatorData: ByteArray,
        val signature: ByteArray,
    ) : OperatorDevicePoP
}

/** The public-key algorithm of an enrolled device credential (the verifier picks the signature check accordingly). */
enum class DeviceKeyAlg { ED25519, ES256 }

/**
 * The enrolled operator device — the PoP anchor registered at First-Device-Enroll. Holds ONLY the public key /
 * credential id (never a private key; the operator's device holds that). [credentialId] is null for the Raw branch.
 */
data class EnrolledOperatorDevice(
    val deviceId: String,
    val alg: DeviceKeyAlg,
    /** Raw public key: 32-byte Ed25519, or the 65-byte uncompressed P-256 point for ES256. */
    val publicKey: ByteArray,
    /** Fido2 credential id (matched against the assertion); null for the Raw branch. */
    val credentialId: ByteArray? = null,
)
