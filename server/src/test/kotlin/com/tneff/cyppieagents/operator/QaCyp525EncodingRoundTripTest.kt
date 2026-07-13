package com.tneff.cyppieagents.operator

import com.tneff.cyppieagents.auth.operator.AssertionResult
import com.tneff.cyppieagents.auth.operator.DeviceKeyAlg
import com.tneff.cyppieagents.auth.operator.EnrolledOperatorDevice
import com.tneff.cyppieagents.auth.operator.OperatorAssertionVerifier
import com.tneff.cyppieagents.auth.operator.OperatorDevicePoP
import com.tneff.cyppieagents.crypto.RawKeys
import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-525 Axis-1 — QA independent SPKI→raw encoding round-trip, the anti-"grün-gebaut-nie-CONNECTED" tooth.
 *
 * Fixture-fidelity delta over the existing CYP-525 teeth ([[prod-wiring-fixture-fidelity]]): `Cyp525Ed25519KeyTest`
 * exercises the byte-helper on SYNTHETIC bytes; `Cyp525DeviceEnrollGateTest` drives the gate with the SERVER's own
 * `RawKeys.ed25519Sign`/raw material on BOTH sides. NEITHER exercises the REAL cross-stack path that decides CONNECTED:
 * the **client** is JCA (`KeyPairGenerator("Ed25519")` → `public.encoded` = X.509 SPKI-44B; `Signature("Ed25519")`),
 * the **server** verifies with `RawKeys.ed25519Verify` (raw-32B, reconstructed via `EdECPublicKeySpec`). If JCA's SPKI
 * byte layout and the server's raw decode disagree, a perfectly-signed real-client PoP verifies against the wrong bytes
 * and NEVER CONNECTs — green helper + green raw-only gate, dead live path. These teeth drive JCA-client ↔ RawKeys-server
 * end-to-end across the encode→wire→persist→verify boundary, with a DIVERGENT fixture (SPKI-44B ≠ raw-32B, never an echo).
 */
class QaCyp525EncodingRoundTripTest {

    private val h = ByteArray(32) { (it * 7).toByte() }
    private val hubId = "hub_c1d6f5ffd892a03d"
    private val rpId = "api.cyppie-agents.com"

    /** A REAL client device key + a REAL JCA signature over the operator-auth challenge (mirrors KeystoreOperatorDeviceKeyStore). */
    private fun jcaEd25519() = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private fun jcaSign(priv: java.security.PrivateKey, msg: ByteArray) =
        Signature.getInstance("Ed25519").run { initSign(priv); update(msg); sign() }

    @Test
    fun realJcaClientKey_spkiToRaw_verifiesServerSide_endToEnd() {
        val kp = jcaEd25519()
        val spki = kp.public.encoded // the REAL client wire-source (X.509 SPKI)
        // DIVERGENT fixture: SPKI (44B) is NOT the raw (32B) — the encodings genuinely differ (not a same-byte echo).
        assertEquals(ED25519_SPKI_LEN, spki.size, "real JCA Ed25519 public.encoded is SPKI-44B")
        val raw = ed25519SpkiToRaw(spki) // the REAL client-side conversion before sending raw-32B on the wire
        assertEquals(ED25519_RAW_LEN, raw.size)
        assertFalse(spki.contentEquals(raw), "SPKI ≠ raw — a same-byte fixture would be vacuous")

        val nonce = ByteArray(32) { 3 }
        val challenge = operatorAuthChallenge(h, hubId, nonce)
        val sig = jcaSign(kp.private, challenge)

        // ★ the load-bearing assertion: the REAL server verify primitive accepts the JCA-client signature under the
        // SPKI→raw-decoded key. If this is red, the live client reaches AUTHENTICATING and never CONNECTs.
        assertTrue(RawKeys.ed25519Verify(raw, challenge, sig), "JCA-client sig verifies against the SPKI→raw key (CONNECTED reachable)")
    }

    @Test
    fun fullGatePath_verifyAny_acceptsEnrolledRawFromRealClientKey() {
        val kp = jcaEd25519()
        val raw = ed25519PublicKeyToRaw(kp.public.encoded) // the HUB defensive reader, exactly as Rr3TunnelGate.firstEnrollThenGrant
        val nonce = ByteArray(32) { 9 }
        val sig = jcaSign(kp.private, operatorAuthChallenge(h, hubId, nonce))
        val device = EnrolledOperatorDevice("op-ab7c54e3", DeviceKeyAlg.ED25519, raw)
        val result = OperatorAssertionVerifier().verifyAny(
            OperatorDevicePoP.Raw(sig), listOf(device), h, hubId, nonce, rpId,
        )
        assertTrue(result is AssertionResult.Verified, "the full RR3 verifyAny path accepts a real-client PoP against the enrolled raw key: $result")
    }

    // ── Non-vacuity: the SPKI→raw conversion is LOAD-BEARING, and the binding is to THIS key ──

    @Test
    fun verifyingAgainstSpkiBytes_insteadOfRaw_FAILS_provesConversionLoadBearing() {
        val kp = jcaEd25519()
        val spki = kp.public.encoded
        val nonce = ByteArray(32) { 5 }
        val challenge = operatorAuthChallenge(h, hubId, nonce)
        val sig = jcaSign(kp.private, challenge)
        // Feeding the SPKI-44B where a raw-32B is expected (the exact encoding slip) must NOT verify — this is the trap
        // the conversion prevents. (RawKeys is fail-closed on a wrong-length key → false, never a silent pass.)
        assertFalse(RawKeys.ed25519Verify(spki, challenge, sig), "SPKI-as-raw must NOT verify — the SPKI→raw conversion is load-bearing")
    }

    @Test
    fun gateWithUnnormalizedSpkiEnrolled_isRejected() {
        val kp = jcaEd25519()
        val nonce = ByteArray(32) { 6 }
        val sig = jcaSign(kp.private, operatorAuthChallenge(h, hubId, nonce))
        // A device mistakenly enrolled with the SPKI-44B as `publicKey` (skipping normalization) → verifyAny rejects.
        val badDevice = EnrolledOperatorDevice("op-x", DeviceKeyAlg.ED25519, kp.public.encoded /* 44B, un-normalized */)
        val result = OperatorAssertionVerifier().verifyAny(OperatorDevicePoP.Raw(sig), listOf(badDevice), h, hubId, nonce, rpId)
        assertFalse(result is AssertionResult.Verified, "an un-normalized SPKI-44B enrolled key must not verify (bad_signature): $result")
    }

    @Test
    fun differentKey_doesNotVerify_identityBinding() {
        val signer = jcaEd25519()
        val other = jcaEd25519()
        val nonce = ByteArray(32) { 7 }
        val challenge = operatorAuthChallenge(h, hubId, nonce)
        val sig = jcaSign(signer.private, challenge)
        // The PoP is bound to the SIGNER's key; a different enrolled key must reject (no cross-key acceptance).
        assertFalse(
            RawKeys.ed25519Verify(ed25519SpkiToRaw(other.public.encoded), challenge, sig),
            "a signature from key A must NOT verify under key B (identity binding)",
        )
    }

    @Test
    fun challengeMismatch_doesNotVerify_channelBinding() {
        val kp = jcaEd25519()
        val nonce = ByteArray(32) { 8 }
        val raw = ed25519SpkiToRaw(kp.public.encoded)
        val sig = jcaSign(kp.private, operatorAuthChallenge(h, hubId, nonce))
        // A signature bound to THIS (h,hubId,nonce) must not verify against a different challenge (channel-binding).
        val otherChallenge = operatorAuthChallenge(ByteArray(32) { 1 }, hubId, nonce)
        assertFalse(RawKeys.ed25519Verify(raw, otherChallenge, sig), "the PoP binds to its own h — a different challenge must not verify")
    }
}
