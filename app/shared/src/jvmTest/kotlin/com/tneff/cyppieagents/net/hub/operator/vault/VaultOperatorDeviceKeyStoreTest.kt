package com.tneff.cyppieagents.net.hub.operator.vault

import com.tneff.cyppieagents.net.hub.operator.CachingUserVerification
import com.tneff.cyppieagents.net.hub.operator.DevicePoP
import com.tneff.cyppieagents.net.hub.operator.PopResult
import com.tneff.cyppieagents.net.hub.operator.UvReason
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CYP-542 / B1 (§4.4) — the load-bearing seam end-to-end: passphrase → vault (Argon2id-fake + real AES-GCM) →
 * decrypted-key-hold → real Ed25519 sign, behind the shared [CachingUserVerification]. Pins **1 real passphrase
 * prompt authorizes N signings** (the dogfood property, now real), the bounded key-hold + re-prompt after the window,
 * the ③ corrupt-vault fail-closed (no prompt), and that the RIGHT enrolled key signs (the sig verifies).
 */
class VaultOperatorDeviceKeyStoreTest {

    private class MemStore(var blob: ByteArray? = null) : VaultStore {
        override fun exists() = blob != null
        override fun read() = blob
        override fun write(bytes: ByteArray) { blob = bytes }
        override fun delete() { blob = null }
    }

    private class CountingPrompt(private val passphrase: String?) : PassphrasePrompt {
        var prompts = 0
        override suspend fun prompt(reason: UvReason): CharArray? { prompts++; return passphrase?.toCharArray() }
    }

    private class Rig(val store: MemStore, now: () -> Long, prompt: CountingPrompt) {
        val nowRef = now
        val keyHold = DecryptedKeyHold(now)
        val prompt = prompt
        private val kdf = PassphraseKdf { p, salt, _ ->
            MessageDigest.getInstance("SHA-256").apply { update(p.concatToString().encodeToByteArray()); update(salt) }.digest()
        }
        val vault = OperatorSecretVault(store, kdf, JceAead(), now)
        val uv = CachingUserVerification(
            PassphraseUserVerification(vault, prompt, keyHold, now, reuseWindowMs = 120_000L), 120_000L, now,
        )
        val keystore = VaultOperatorDeviceKeyStore(vault, uv, keyHold)
    }

    private fun enrollRealKey(rig: Rig, passphrase: String): java.security.PublicKey {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        rig.vault.enroll(passphrase.toCharArray(), kp.private.encoded, kp.public.encoded)
        return kp.public
    }

    private fun challenge() = "operator-auth-challenge".encodeToByteArray()

    private fun verifySig(pub: java.security.PublicKey, msg: ByteArray, sig: ByteArray): Boolean =
        Signature.getInstance("Ed25519").apply { initVerify(pub); update(msg) }.verify(sig)

    @Test
    fun oneUvForN_throughVault_singlePrompt_allSignAndVerify() = runTest {
        val prompt = CountingPrompt("a-strong-passphrase-64bit")
        val rig = Rig(MemStore(), { 0L }, prompt)
        val pub = enrollRealKey(rig, "a-strong-passphrase-64bit")
        val sigs = (1..3).map {
            val r = rig.keystore.sign(challenge())
            assertIs<PopResult.Signed>(r)
            (r.pop as DevicePoP.Raw).signature
        }
        assertEquals(1, prompt.prompts, "N signings ⇒ ONE passphrase prompt (1-UV-for-N through the real vault)")
        assertTrue(sigs.all { verifySig(pub, challenge(), it) }, "every signature verifies with the enrolled key")
    }

    @Test
    fun wrongPassphrase_uvFailed_wrongPin_noSignature() = runTest {
        val rig = Rig(MemStore(), { 0L }, CountingPrompt("the-wrong-passphrase"))
        enrollRealKey(rig, "the-right-passphrase-64bit")
        val r = rig.keystore.sign(challenge())
        assertIs<PopResult.UvFailed>(r)
        assertEquals(com.tneff.cyppieagents.net.hub.operator.UvFailReason.WRONG_PIN, r.reason)
    }

    @Test
    fun cancelled_uvFailed_cancelled() = runTest {
        val rig = Rig(MemStore(), { 0L }, CountingPrompt(null)) // null = operator cancelled
        enrollRealKey(rig, "some-passphrase-64bit")
        val r = rig.keystore.sign(challenge())
        assertIs<PopResult.UvFailed>(r)
        assertEquals(com.tneff.cyppieagents.net.hub.operator.UvFailReason.CANCELLED, r.reason)
    }

    @Test
    fun corruptVault_signUnavailable_noPrompt_enrolledStillTrue() = runTest {
        val prompt = CountingPrompt("whatever")
        val rig = Rig(MemStore(blob = "corrupt-not-a-blob".encodeToByteArray()), { 0L }, prompt)
        assertTrue(rig.keystore.isEnrolled(), "③ a corrupt vault EXISTS ⇒ isEnrolled true (must NOT route to enroll)")
        assertIs<PopResult.AuthenticatorUnavailable>(rig.keystore.sign(challenge()))
        assertEquals(0, prompt.prompts, "③ fail-closed: a corrupt vault never even prompts (→ OOB-recovery)")
    }

    @Test
    fun missingVault_notEnrolled() = runTest {
        val rig = Rig(MemStore(), { 0L }, CountingPrompt("x"))
        assertTrue(!rig.keystore.isEnrolled())
        assertIs<PopResult.NotEnrolled>(rig.keystore.sign(challenge()))
    }

    @Test
    fun afterWindow_keyHoldZeroizes_rePrompts() = runTest {
        var now = 1_000L
        val prompt = CountingPrompt("pass-64bit-strong")
        val rig = Rig(MemStore(), { now }, prompt)
        enrollRealKey(rig, "pass-64bit-strong")
        assertIs<PopResult.Signed>(rig.keystore.sign(challenge())) // prompt 1
        now += 120_001L // past the reuse window ⇒ cache expires + key-hold zeroizes
        assertIs<PopResult.Signed>(rig.keystore.sign(challenge())) // prompt 2 (re-decrypt)
        assertEquals(2, prompt.prompts, "past the window a fresh passphrase ceremony is required (bounded reuse)")
    }
}
