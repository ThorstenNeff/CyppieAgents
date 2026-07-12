package com.tneff.cyppieagents.auth.operator

import com.tneff.cyppieagents.crypto.RawKeys
import com.tneff.cyppieagents.operator.operatorAuthChallenge
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.concurrent.ConcurrentHashMap

/** The verdict of an operator assertion check — fail-closed: anything not [Verified] is a reject with a stable code. */
sealed interface AssertionResult {
    data class Verified(val deviceId: String) : AssertionResult
    /** [reason] is a stable, non-secret code (for the hub's own logs). */
    data class Rejected(val reason: String) : AssertionResult
}

/** Single-use nonce ledger (freshness): [useOnce] returns true the FIRST time a nonce is seen, false on replay. */
fun interface NonceLedger {
    fun useOnce(nonce: ByteArray): Boolean
}

/** In-memory single-use ledger (unbounded is fine for tests; prod bounds/expires it). Thread-safe. */
class InMemoryNonceLedger : NonceLedger {
    private val seen = ConcurrentHashMap.newKeySet<String>()
    override fun useOnce(nonce: ByteArray): Boolean = seen.add(nonce.toHex())
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/**
 * CYP-469 — the **server** `OperatorAssertionVerifier`: verifies an operator device PoP ([OperatorDevicePoP]),
 * channel-bound to the live Noise `h`. **Fail-closed**, transport-independent (the `h` is an input — a stub in tests,
 * the live tunnel hash under RR5-gated wiring). Mirror of Dev's client CYP-443 Slice-2 verification core.
 *
 * Checks, all required:
 *  1. **nonce single-use** ([NonceLedger]) — a replayed nonce → reject;
 *  2. recompute `challenge = operatorAuthChallenge(h, hubId, nonce)` (channel-bound — a PoP for another session's `h`
 *     yields a different challenge → the signature fails: the replay tooth);
 *  3. **Raw** → plain Ed25519 verify over the challenge; **Fido2** → the **UV flag MUST be set** (server-verifiable),
 *     signCount lenient, and the signature verifies over `authenticatorData ‖ SHA-256(challenge)` under the enrolled
 *     credential (Ed25519 or ES256), with a matching credential id.
 */
class OperatorAssertionVerifier(
    private val nonces: NonceLedger = InMemoryNonceLedger(),
) {
    fun verify(
        pop: OperatorDevicePoP,
        device: EnrolledOperatorDevice,
        handshakeHash: ByteArray,
        hubId: String,
        nonce: ByteArray,
        /** CYP-473 H1 — the expected WebAuthn RP ID; the Fido2 `authData.rpIdHash` MUST equal `SHA-256(this)`. Unused by the Raw branch (no authData). */
        expectedRpId: String,
    ): AssertionResult {
        if (!nonces.useOnce(nonce)) return AssertionResult.Rejected("nonce_replayed")
        val challenge = operatorAuthChallenge(handshakeHash, hubId, nonce)
        return when (pop) {
            is OperatorDevicePoP.Raw ->
                if (verifySig(device, challenge, pop.signature)) AssertionResult.Verified(device.deviceId)
                else AssertionResult.Rejected("bad_signature")
            is OperatorDevicePoP.Fido2 -> verifyFido2(pop, device, challenge, expectedRpId)
        }
    }

    private fun verifyFido2(
        pop: OperatorDevicePoP.Fido2,
        device: EnrolledOperatorDevice,
        challenge: ByteArray,
        expectedRpId: String,
    ): AssertionResult {
        val credId = device.credentialId
        if (credId == null || !pop.credentialId.contentEquals(credId)) return AssertionResult.Rejected("credential_mismatch")
        val authData = pop.authenticatorData
        // authenticatorData = rpIdHash[32] ‖ flags[1] ‖ signCount[4] ‖ …
        if (authData.size < 37) return AssertionResult.Rejected("authdata_malformed")
        // ★ CYP-473 H1 (WebAuthn §7.2 step 13): rpIdHash MUST equal SHA-256(expectedRpId) — an assertion from a
        // credential scoped to another RP does not authenticate here (defence-in-depth beside the channel-binding).
        if (!authData.copyOfRange(0, 32).contentEquals(sha256(expectedRpId.encodeToByteArray()))) {
            return AssertionResult.Rejected("rpid_mismatch")
        }
        // ★ UV flag (bit 2, 0x04) MANDATORY — user verification is required, not merely user presence.
        if (authData[32].toInt() and 0x04 == 0) return AssertionResult.Rejected("uv_required")
        // signCount = authData[33..36] — LENIENT (many authenticators keep it 0); deliberately not enforced.
        val signed = authData + sha256(challenge) // CTAP: sig over authenticatorData ‖ clientDataHash(=SHA-256(challenge))
        return if (verifySig(device, signed, pop.signature)) AssertionResult.Verified(device.deviceId)
        else AssertionResult.Rejected("bad_signature")
    }

    private fun verifySig(device: EnrolledOperatorDevice, message: ByteArray, signature: ByteArray): Boolean =
        when (device.alg) {
            DeviceKeyAlg.ED25519 -> RawKeys.ed25519Verify(device.publicKey, message, signature)
            DeviceKeyAlg.ES256 -> es256Verify(device.publicKey, message, signature)
        }

    /** ES256 (P-256 ECDSA over SHA-256): [publicKey] = 65-byte uncompressed point (0x04‖x‖y); [signature] = DER ECDSA. */
    private fun es256Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = runCatching {
        if (publicKey.size != 65 || publicKey[0].toInt() != 0x04) return false
        val x = BigInteger(1, publicKey.copyOfRange(1, 33))
        val y = BigInteger(1, publicKey.copyOfRange(33, 65))
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val pub = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
        Signature.getInstance("SHA256withECDSA").run { initVerify(pub); update(message); verify(signature) }
    }.getOrDefault(false)

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
}
