package com.tneff.cyppieagents.net.hub.operator

import com.tneff.cyppieagents.operator.operatorAuthChallenge
import kotlinx.coroutines.test.runTest
import java.security.KeyPair
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-443 Slice 2 — the Raw path honesty teeth (real Ed25519). UV-gated signing produces a [DevicePoP.Raw] whose
 * signature **verifies over the channel-bound challenge and binds THIS `h`** (a signature made for one `h` fails
 * verification against another — the Reviewer's core replay tooth, client side). UV-denied is **fail-closed** (no
 * signature; retryable, not a hub reject). No key ⇒ NotEnrolled. This is the CI-verified branch on Linux.
 */
class KeystoreOperatorDeviceKeyStoreTest {

    private val key: KeyPair = KeystoreOperatorDeviceKeyStore.generateDeviceKey()
    private fun uv(outcome: UvOutcome) = UserVerification { outcome }

    private fun ed25519Verifies(pub: java.security.PublicKey, message: ByteArray, signature: ByteArray): Boolean {
        val v = Signature.getInstance("Ed25519")
        v.initVerify(pub)
        v.update(message)
        return v.verify(signature)
    }

    @Test
    fun uvVerified_signsRaw_thatVerifiesOverTheChallenge() = runTest {
        val store = KeystoreOperatorDeviceKeyStore(uv(UvOutcome.Verified), key)
        val challenge = operatorAuthChallenge(ByteArray(32) { 0x0A }, "hub-1", byteArrayOf(1, 2, 3))
        val raw = assertIs<DevicePoP.Raw>(assertIs<PopResult.Signed>(store.sign(challenge)).pop)
        assertTrue(ed25519Verifies(key.public, challenge, raw.signature), "the Raw PoP verifies over the challenge")
    }

    @Test
    fun signature_bindsThisH_replayOntoAnotherHFails() = runTest {
        val store = KeystoreOperatorDeviceKeyStore(uv(UvOutcome.Verified), key)
        val nonce = byteArrayOf(7, 7, 7)
        val challengeA = operatorAuthChallenge(ByteArray(32) { 0x0A }, "hub-1", nonce)
        val challengeB = operatorAuthChallenge(ByteArray(32) { 0x0B }, "hub-1", nonce) // a DIFFERENT session h
        val raw = assertIs<DevicePoP.Raw>(assertIs<PopResult.Signed>(store.sign(challengeA)).pop)
        assertTrue(ed25519Verifies(key.public, challengeA, raw.signature), "valid for its own h")
        assertTrue(
            !ed25519Verifies(key.public, challengeB, raw.signature),
            "a PoP made for h_A does NOT verify against h_B (channel-binding / no cross-session replay)",
        )
    }

    @Test
    fun uvDenied_failsClosed_noSignature_retryableTaxonomy() = runTest {
        val store = KeystoreOperatorDeviceKeyStore(uv(UvOutcome.Denied(UvFailReason.WRONG_PIN)), key)
        val out = store.sign(operatorAuthChallenge(ByteArray(32), "h", byteArrayOf(0)))
        assertEquals(PopResult.UvFailed(UvFailReason.WRONG_PIN), out, "denied UV ⇒ no signature, a retryable local outcome")
    }

    @Test
    fun uvUnavailable_isAuthenticatorUnavailable() = runTest {
        val store = KeystoreOperatorDeviceKeyStore(uv(UvOutcome.Unavailable), key)
        assertEquals(
            PopResult.AuthenticatorUnavailable,
            store.sign(operatorAuthChallenge(ByteArray(32), "h", byteArrayOf(0))),
        )
    }

    @Test
    fun notEnrolled_whenNoKey() = runTest {
        val store = KeystoreOperatorDeviceKeyStore(uv(UvOutcome.Verified), keyPair = null)
        assertEquals(PopResult.NotEnrolled, store.sign(operatorAuthChallenge(ByteArray(32), "h", byteArrayOf(0))))
        assertTrue(!store.isEnrolled())
        assertNull(store.devicePublicKey())
    }

    @Test
    fun fido2Store_onLinux_isUnavailable_fallsBackToRaw() = runTest {
        // The progressive-enhancement branch is honestly unavailable on this platform → OS-selection uses Raw.
        assertEquals(PopResult.AuthenticatorUnavailable, Fido2OperatorDeviceKeyStore().sign(ByteArray(4)))
        assertTrue(!Fido2OperatorDeviceKeyStore().isEnrolled())
    }
}
