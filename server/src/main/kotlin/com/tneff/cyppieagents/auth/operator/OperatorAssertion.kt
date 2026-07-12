package com.tneff.cyppieagents.auth.operator

/**
 * CYP-469 (Phase-2 Operator-Auth, server) — the **server mirror** of Dev's client CYP-443 Slice-2 operator PoP
 * contract (`app/shared/.../net/hub/operator/DevicePoP.kt`). The verifier ([OperatorAssertionVerifier]) recomputes
 * the channel-bound challenge from ITS OWN live Noise `h` + `hubId` + the carried nonce and checks the operator's
 * device signature — the CP forges the identity token but **never** this (RR2-B; the operator holds the key).
 *
 * **These bytes MUST stay byte-identical to the client's [operatorAuthChallenge]** (they are not in `:core` yet — a
 * mirror, locked by [OperatorAssertionVerifierTest]'s golden vector). Transport-independent: verified with a stub
 * `h` in tests; the live-`h`-from-the-tunnel wiring is RR5-gated (CYP-458/459).
 */

/** The PoP purpose tag (doc 16 §7) — domain-separates this signature from any other use of the device-key. */
const val OPERATOR_AUTH_PURPOSE = "operator-auth"

/**
 * The channel-bound challenge the device-key attests — **byte-identical to the client** `operatorAuthChallenge`:
 * each field 4-byte-BE length-prefixed then concatenated (`len‖h · len‖hubId · len‖nonce · len‖purpose`), injective
 * so no field boundary is ambiguous. Bound to the **live tunnel `h`** (CI-2): a PoP for one session's `h` cannot be
 * replayed onto another — the server recomputes this from its own `h`/`hubId`/purpose + the carried nonce.
 */
fun operatorAuthChallenge(handshakeHash: ByteArray, hubId: String, nonce: ByteArray): ByteArray {
    val fields = listOf(
        handshakeHash,
        hubId.encodeToByteArray(),
        nonce,
        OPERATOR_AUTH_PURPOSE.encodeToByteArray(),
    )
    val out = ByteArray(fields.sumOf { 4 + it.size })
    var i = 0
    for (f in fields) {
        out[i++] = (f.size ushr 24).toByte()
        out[i++] = (f.size ushr 16).toByte()
        out[i++] = (f.size ushr 8).toByte()
        out[i++] = f.size.toByte()
        f.copyInto(out, i)
        i += f.size
    }
    return out
}

/**
 * The operator PoP as it arrives at the server — the wire mirror of the client `DevicePoP` (server-authoritative,
 * no client downgrade). Two OS-selected branches, both channel-bound via [operatorAuthChallenge].
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
