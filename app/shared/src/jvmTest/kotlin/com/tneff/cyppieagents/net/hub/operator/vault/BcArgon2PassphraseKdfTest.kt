package com.tneff.cyppieagents.net.hub.operator.vault

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CYP-542 / B1 — the real Argon2id [BcArgon2PassphraseKdf] (BouncyCastle) + an **end-to-end** real-crypto proof
 * (Argon2id → KEK → AES-GCM seal/open → vault). Determinism uses reduced params for speed; one derive runs at the
 * FROZEN cost (m≥64 MiB/t≥3) to prove the ratified floor is usable, not just fast fixtures.
 */
class BcArgon2PassphraseKdfTest {

    private val kdf = BcArgon2PassphraseKdf()
    private val fast = Argon2Params(memoryKiB = 8 * 1024, iterations = 1, parallelism = 1) // fast for CI

    @Test
    fun deriveKek_isDeterministic_and32Bytes() {
        val salt = ByteArray(16) { it.toByte() }
        val a = kdf.deriveKek("correct horse battery staple".toCharArray(), salt, fast)
        val b = kdf.deriveKek("correct horse battery staple".toCharArray(), salt, fast)
        assertEquals(32, a.size, "KEK is 32 bytes (AES-256)")
        assertContentEquals(a, b, "same passphrase+salt+params ⇒ same KEK")
    }

    @Test
    fun differentPassphraseOrSalt_differentKek() {
        val salt1 = ByteArray(16) { 1 }
        val salt2 = ByteArray(16) { 2 }
        val base = kdf.deriveKek("passphrase-one".toCharArray(), salt1, fast)
        assertFalse(base.contentEquals(kdf.deriveKek("passphrase-two".toCharArray(), salt1, fast)), "passphrase changes the KEK")
        assertFalse(base.contentEquals(kdf.deriveKek("passphrase-one".toCharArray(), salt2, fast)), "salt changes the KEK")
    }

    @Test
    fun frozenParams_runAtRealCost_produceAKek() {
        // Not a perf assertion — proof the ratified floor (m≥64 MiB, t≥3, p=1) actually runs + yields a 32-byte KEK.
        val kek = kdf.deriveKek("a-strong-64-bit-passphrase-xyz".toCharArray(), ByteArray(16) { 9 }, Argon2Params.FROZEN)
        assertEquals(32, kek.size)
    }

    @Test
    fun endToEnd_realArgon2_realAesGcm_vault_roundtripsAndFailsClosed() {
        // The FULL real crypto stack: Argon2id KEK → AES-256-GCM seal → vault → open. Reduced params for speed.
        val store = object : VaultStore {
            var blob: ByteArray? = null
            override fun exists() = blob != null
            override fun read() = blob
            override fun write(bytes: ByteArray) { blob = bytes }
            override fun delete() { blob = null }
        }
        val vault = OperatorSecretVault(store = store, kdf = kdf, aead = JceAead(), nowMs = { 0L }, params = fast)
        val priv = ByteArray(48) { (it * 3 + 1).toByte() }
        vault.enroll("a-real-strong-passphrase-64bit".toCharArray(), priv, ByteArray(44) { it.toByte() })

        val ok = vault.open("a-real-strong-passphrase-64bit".toCharArray())
        assertIs<VaultOpen.Unlocked>(ok)
        assertContentEquals(priv, ok.privKeyPkcs8, "real Argon2id+AES-GCM decrypts the exact enrolled key")
        assertIs<VaultOpen.WrongPassphrase>(vault.open("a-wrong-passphrase-000000000000".toCharArray()))
    }
}
