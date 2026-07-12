package com.tneff.cyppieagents.auth.operator

import com.tneff.cyppieagents.crypto.RawKeys
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * CYP-469 — the server `OperatorAssertionVerifier` teeth. Hermetic (S-C `RawKeys` Ed25519, runtime-verified). The
 * Raw branch is the fully-testable path (Linux/CI has no platform authenticator); the Fido2 branch's CTAP logic
 * (UV-flag + channel-binding + `authData‖SHA-256(challenge)`) is exercised with an Ed25519 credential fixture.
 *
 * ★ [replay_assertionForOneHash_rejectedAgainstAnother] is the headline (server mirror of Dev's replay-core): an
 *   assertion bound to `h_A` does NOT verify against `h_B` (mutation: drop `h` from [operatorAuthChallenge] → RED).
 * ★ [fido2_uvFlagMissing_rejected] — UV is mandatory. ★ [nonce_singleUse_replayRejected] — freshness.
 */
class OperatorAssertionVerifierTest {

    private val hubId = "hub_abcdef0123456789"
    private val hA = byteArrayOf(1, 2, 3, 4, 5)
    private val hB = byteArrayOf(9, 9, 9, 9, 9)
    private val nonce1 = byteArrayOf(0x11, 0x22, 0x33)

    private class RawDevice(val device: EnrolledOperatorDevice, val seed: ByteArray)

    private fun enrollRawDevice(id: String = "dev-1"): RawDevice {
        val kp = RawKeys.generateEd25519()
        return RawDevice(EnrolledOperatorDevice(id, DeviceKeyAlg.ED25519, kp.publicRaw), kp.privateRaw)
    }

    private fun rawPop(seed: ByteArray, h: ByteArray, nonce: ByteArray) =
        OperatorDevicePoP.Raw(RawKeys.ed25519Sign(seed, operatorAuthChallenge(h, hubId, nonce)))

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    /** A CTAP-shaped assertion (Ed25519 credential fixture): authData = rpIdHash[32] ‖ flags ‖ signCount[4]. */
    private fun fido2Pop(seed: ByteArray, credId: ByteArray, uv: Boolean, h: ByteArray, nonce: ByteArray): OperatorDevicePoP.Fido2 {
        val authData = ByteArray(37)
        authData[32] = (0x01 or (if (uv) 0x04 else 0x00)).toByte() // UP always; UV bit only when uv
        // signCount authData[33..36] left 0 (lenient)
        val signed = authData + sha256(operatorAuthChallenge(h, hubId, nonce))
        return OperatorDevicePoP.Fido2(credId, authData, RawKeys.ed25519Sign(seed, signed))
    }

    // ---- Raw branch ----

    @Test fun raw_validAssertion_verifies() {
        val d = enrollRawDevice()
        val r = OperatorAssertionVerifier().verify(rawPop(d.seed, hA, nonce1), d.device, hA, hubId, nonce1)
        assertIs<AssertionResult.Verified>(r)
        assertEquals("dev-1", r.deviceId)
    }

    /** ★ HEADLINE — channel-binding: a PoP for h_A does NOT verify against h_B (fresh verifiers → nonce is fresh both). */
    @Test fun replay_assertionForOneHash_rejectedAgainstAnother() {
        val d = enrollRawDevice()
        val pop = rawPop(d.seed, hA, nonce1) // signed over challenge(h_A, nonce1)
        assertIs<AssertionResult.Verified>(OperatorAssertionVerifier().verify(pop, d.device, hA, hubId, nonce1))
        val onB = OperatorAssertionVerifier().verify(pop, d.device, hB, hubId, nonce1)
        assertIs<AssertionResult.Rejected>(onB) // challenge(h_B) != what was signed → signature fails
        assertEquals("bad_signature", onB.reason)
    }

    @Test fun raw_wrongDeviceKey_rejected() {
        val d = enrollRawDevice()
        val attacker = RawKeys.generateEd25519()
        val forged = OperatorDevicePoP.Raw(RawKeys.ed25519Sign(attacker.privateRaw, operatorAuthChallenge(hA, hubId, nonce1)))
        val r = OperatorAssertionVerifier().verify(forged, d.device, hA, hubId, nonce1)
        assertIs<AssertionResult.Rejected>(r)
    }

    @Test fun nonce_singleUse_replayRejected() {
        val d = enrollRawDevice()
        val v = OperatorAssertionVerifier()
        assertIs<AssertionResult.Verified>(v.verify(rawPop(d.seed, hA, nonce1), d.device, hA, hubId, nonce1))
        val replay = v.verify(rawPop(d.seed, hA, nonce1), d.device, hA, hubId, nonce1)
        assertIs<AssertionResult.Rejected>(replay)
        assertEquals("nonce_replayed", replay.reason)
    }

    // ---- Fido2 branch ----

    @Test fun fido2_uvSet_verifies() {
        val kp = RawKeys.generateEd25519()
        val credId = byteArrayOf(0x0A, 0x0B)
        val device = EnrolledOperatorDevice("dev-fido", DeviceKeyAlg.ED25519, kp.publicRaw, credentialId = credId)
        val r = OperatorAssertionVerifier().verify(fido2Pop(kp.privateRaw, credId, uv = true, hA, nonce1), device, hA, hubId, nonce1)
        assertIs<AssertionResult.Verified>(r)
    }

    /** ★ UV is mandatory (server-verifiable): a Fido2 assertion with the UV flag CLEAR is rejected. */
    @Test fun fido2_uvFlagMissing_rejected() {
        val kp = RawKeys.generateEd25519()
        val credId = byteArrayOf(0x0A, 0x0B)
        val device = EnrolledOperatorDevice("dev-fido", DeviceKeyAlg.ED25519, kp.publicRaw, credentialId = credId)
        val r = OperatorAssertionVerifier().verify(fido2Pop(kp.privateRaw, credId, uv = false, hA, nonce1), device, hA, hubId, nonce1)
        assertIs<AssertionResult.Rejected>(r)
        assertEquals("uv_required", r.reason)
    }

    @Test fun fido2_channelBound_rejectedAgainstOtherH() {
        val kp = RawKeys.generateEd25519()
        val credId = byteArrayOf(0x0A, 0x0B)
        val device = EnrolledOperatorDevice("dev-fido", DeviceKeyAlg.ED25519, kp.publicRaw, credentialId = credId)
        val pop = fido2Pop(kp.privateRaw, credId, uv = true, hA, nonce1)
        assertIs<AssertionResult.Rejected>(OperatorAssertionVerifier().verify(pop, device, hB, hubId, nonce1))
    }

    /** The ES256 (P-256 ECDSA) credential path — the common real-authenticator alg — verifies end-to-end. */
    @Test fun fido2_es256Credential_verifies() {
        val kpg = java.security.KeyPairGenerator.getInstance("EC")
        kpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        val pub = kp.public as java.security.interfaces.ECPublicKey
        val raw = byteArrayOf(0x04) + to32(pub.w.affineX) + to32(pub.w.affineY)
        val credId = byteArrayOf(0x0C, 0x0D)
        val device = EnrolledOperatorDevice("dev-es", DeviceKeyAlg.ES256, raw, credentialId = credId)

        val authData = ByteArray(37).also { it[32] = (0x01 or 0x04).toByte() } // UP+UV
        val signed = authData + sha256(operatorAuthChallenge(hA, hubId, nonce1))
        val sig = java.security.Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update(signed); sign() }
        val pop = OperatorDevicePoP.Fido2(credId, authData, sig)

        assertIs<AssertionResult.Verified>(OperatorAssertionVerifier().verify(pop, device, hA, hubId, nonce1))
    }

    private fun to32(v: java.math.BigInteger): ByteArray {
        val b = v.toByteArray() // big-endian, may carry a leading 0x00 sign byte or be shorter than 32
        val out = ByteArray(32)
        val src = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b
        src.copyInto(out, 32 - src.size)
        return out
    }

    @Test fun fido2_credentialMismatch_rejected() {
        val kp = RawKeys.generateEd25519()
        val device = EnrolledOperatorDevice("dev-fido", DeviceKeyAlg.ED25519, kp.publicRaw, credentialId = byteArrayOf(1, 2))
        val pop = fido2Pop(kp.privateRaw, byteArrayOf(9, 9), uv = true, hA, nonce1) // different credId
        val r = OperatorAssertionVerifier().verify(pop, device, hA, hubId, nonce1)
        assertIs<AssertionResult.Rejected>(r)
        assertEquals("credential_mismatch", r.reason)
    }

    // ---- First-Device-Enroll ----

    @Test fun firstDeviceEnroll_succeeds_thenSecondIsRecoverySeam() {
        val enroll = OperatorDeviceEnrollment(InMemoryOperatorDeviceStore())
        val kp = RawKeys.generateEd25519()
        val first = enroll.enrollFirstDevice(EnrolledOperatorDevice("dev-1", DeviceKeyAlg.ED25519, kp.publicRaw))
        assertIs<EnrollResult.Enrolled>(first)
        // a SECOND enroll is the Q6-gated recovery seam — a plain enroll must NOT overwrite the anchor.
        val second = enroll.enrollFirstDevice(EnrolledOperatorDevice("dev-2", DeviceKeyAlg.ED25519, RawKeys.generateEd25519().publicRaw))
        assertIs<EnrollResult.Rejected>(second)
        assertEquals("already_enrolled_recovery_is_q6_seam", second.reason)
    }

    @Test fun enroll_badPublicKey_rejected() {
        val enroll = OperatorDeviceEnrollment(InMemoryOperatorDeviceStore())
        val r = enroll.enrollFirstDevice(EnrolledOperatorDevice("dev-1", DeviceKeyAlg.ED25519, ByteArray(16))) // not 32B
        assertIs<EnrollResult.Rejected>(r)
        assertEquals("bad_public_key", r.reason)
    }

    // ---- byte-compat lock (mirror of the client operatorAuthChallenge) ----

    @Test fun operatorAuthChallenge_goldenVector_locksClientByteCompat() {
        // h=[01 02], hubId="h", nonce=[ff] → len-prefixed(h) · len-prefixed("h") · len-prefixed(nonce) · len-prefixed("operator-auth")
        val golden = "000000020102000000016800000001ff0000000d6f70657261746f722d61757468"
        val actual = operatorAuthChallenge(byteArrayOf(1, 2), "h", byteArrayOf(0xff.toByte()))
        assertContentEquals(hex(golden), actual, "server challenge must stay byte-identical to the client encoding")
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }
}
