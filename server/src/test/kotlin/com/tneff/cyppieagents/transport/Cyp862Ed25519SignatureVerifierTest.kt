package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.crypto.RawKeys
import com.tneff.cyppieagents.model.ExperimentalFederation
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-862 (S-Fed-1, DARK) — teeth for the real Ed25519 [Ed25519SignatureVerifier] (replaces the CYP-860 deferred
 * seam). Keys/signatures generated via [RawKeys] (JDK-native RFC-8032 EdEC), base64-encoded through the SAME encoder
 * the verifier decodes with. No canonical RFC-8032 KAT vector exists in-repo to source, so RFC-8032 conformance is
 * pinned by the determinism + 64-byte-length property (a distinctive RFC-8032 trait), alongside the standard
 * valid/tampered/wrong-key/malformed teeth — the same convention as the existing HubIdentityCryptoTest.
 */
@OptIn(ExperimentalFederation::class)
class Cyp862Ed25519SignatureVerifierTest {

    private fun enc(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    @Test
    fun validSignature_verifiesTrue() {
        val kp = RawKeys.generateEd25519()
        val msg = "federation-peer-attestation".encodeToByteArray()
        val sig = RawKeys.ed25519Sign(kp.privateRaw, msg)
        assertTrue(Ed25519SignatureVerifier.verify(enc(kp.publicRaw), msg, enc(sig)))
    }

    @Test
    fun tamperedMessage_verifiesFalse() {
        val kp = RawKeys.generateEd25519()
        val sig = RawKeys.ed25519Sign(kp.privateRaw, "original".encodeToByteArray())
        assertFalse(Ed25519SignatureVerifier.verify(enc(kp.publicRaw), "tampered".encodeToByteArray(), enc(sig)))
    }

    @Test
    fun tamperedSignature_verifiesFalse() {
        val kp = RawKeys.generateEd25519()
        val msg = "m".encodeToByteArray()
        val sig = RawKeys.ed25519Sign(kp.privateRaw, msg).copyOf()
        sig[0] = (sig[0] + 1).toByte() // flip one byte of the signature
        assertFalse(Ed25519SignatureVerifier.verify(enc(kp.publicRaw), msg, enc(sig)))
    }

    @Test
    fun wrongKey_verifiesFalse() {
        val signer = RawKeys.generateEd25519()
        val other = RawKeys.generateEd25519()
        val msg = "m".encodeToByteArray()
        val sig = RawKeys.ed25519Sign(signer.privateRaw, msg)
        assertFalse(Ed25519SignatureVerifier.verify(enc(other.publicRaw), msg, enc(sig)))
    }

    @Test
    fun malformedBase64_failsClosed_noThrow() {
        val kp = RawKeys.generateEd25519()
        val sig = RawKeys.ed25519Sign(kp.privateRaw, "m".encodeToByteArray())
        // malformed base64 in the PUBLIC key → false (not an exception)
        assertFalse(Ed25519SignatureVerifier.verify("not valid base64 !!!", "m".encodeToByteArray(), enc(sig)))
        // malformed base64 in the SIGNATURE → false
        assertFalse(Ed25519SignatureVerifier.verify(enc(kp.publicRaw), "m".encodeToByteArray(), "@@@notbase64@@@"))
    }

    @Test
    fun wrongLengthKey_failsClosed() {
        val kp = RawKeys.generateEd25519()
        val sig = RawKeys.ed25519Sign(kp.privateRaw, "m".encodeToByteArray())
        // validly base64 but only 5 bytes → not a 32-byte Ed25519 public → false, never throws
        assertFalse(Ed25519SignatureVerifier.verify(enc(byteArrayOf(1, 2, 3, 4, 5)), "m".encodeToByteArray(), enc(sig)))
    }

    /**
     * RFC-8032 conformance (no in-repo KAT vector to source): Ed25519 is DETERMINISTIC — signing the same
     * (seed, message) twice yields byte-identical signatures (no random nonce, unlike ECDSA) — and the signature is
     * exactly 64 bytes. Pins that the delegated primitive follows the RFC-8032 construction, and that a fresh valid
     * signature verifies.
     */
    @Test
    fun ed25519_deterministicAnd64ByteSig_rfc8032Conformance() {
        val kp = RawKeys.generateEd25519()
        val msg = "rfc8032".encodeToByteArray()
        val s1 = RawKeys.ed25519Sign(kp.privateRaw, msg)
        val s2 = RawKeys.ed25519Sign(kp.privateRaw, msg)
        assertTrue(s1.contentEquals(s2), "RFC-8032 Ed25519 is deterministic — same seed+msg → identical signature")
        assertEquals(64, s1.size, "RFC-8032 Ed25519 signature is 64 bytes")
        assertTrue(Ed25519SignatureVerifier.verify(enc(kp.publicRaw), msg, enc(s1)))
    }
}
