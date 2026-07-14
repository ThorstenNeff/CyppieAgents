package com.tneff.cyppieagents.net.hub.operator.vault

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CYP-542 / B1 (D6, H-3) — the **key-loss-critical** migration: the CYP-525 plaintext key → passphrase-sealed vault,
 * **anchor byte-identical**, **seal → verify → then delete**. The integrity tooth (Tester's B1 QA axis) proves the
 * re-sealed vault decrypts byte-equal to the original key + the public anchor is unchanged; the fail-safe teeth prove a
 * botched re-seal NEVER deletes the plaintext (no silent key loss).
 */
class OperatorKeyMigrationTest {

    private class MemStore(var blob: ByteArray? = null) : VaultStore {
        override fun exists() = blob != null
        override fun read() = blob
        override fun write(bytes: ByteArray) { blob = bytes }
        override fun delete() { blob = null }
    }

    private class FakeCustody(private val kp: KeyPair?, private val loadThrows: Boolean = false) : PlaintextKeyCustody {
        var present = kp != null
        var deleted = false
        override fun exists() = present
        override fun load(): KeyPair = if (loadThrows) throw RuntimeException("unreadable key file") else kp!!
        override fun delete() { deleted = true; present = false }
    }

    private val fakeKdf = PassphraseKdf { p, salt, _ ->
        MessageDigest.getInstance("SHA-256").apply { update(p.concatToString().encodeToByteArray()); update(salt) }.digest()
    }

    private fun realKey() = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private fun vault(store: MemStore, aead: Aead = JceAead()) = OperatorSecretVault(store, fakeKdf, aead, { 0L })

    @Test
    fun migrate_reSealVerifiesByteIdentical_deletesPlaintext_preservesAnchor() {
        val kp = realKey()
        val store = MemStore()
        val v = vault(store)
        val custody = FakeCustody(kp)
        val outcome = OperatorKeyMigration(custody, v).migrateIfNeeded("a-strong-passphrase-64bit".toCharArray())

        assertIs<OperatorKeyMigration.Outcome.Migrated>(outcome)
        assertTrue(custody.deleted, "the plaintext is deleted ONLY after a verified re-seal")
        // Integrity: the vault decrypts byte-identical to the original private key.
        val opened = v.open("a-strong-passphrase-64bit".toCharArray())
        assertIs<VaultOpen.Unlocked>(opened)
        assertContentEquals(kp.private.encoded, opened.privKeyPkcs8, "re-sealed key decrypts byte-identical to the original")
        // Anchor: the enrolled public key is byte-identical ⇒ the hub pin is unchanged (no re-enroll).
        assertContentEquals(kp.public.encoded, v.devicePublicKey(), "the public anchor is preserved byte-identically")
    }

    @Test
    fun reSealFailsVerify_preservesPlaintext_rollsBackVault_noKeyLoss() {
        // A faulty AEAD: seals fine but open() returns WRONG bytes ⇒ the verify step must catch it and NOT delete.
        val realAead = JceAead()
        val faultyAead = object : Aead {
            override fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray) = realAead.seal(key, nonce, plaintext, aad)
            override fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray) = ByteArray(48) { 0 } // wrong
            override fun randomBytes(n: Int) = realAead.randomBytes(n)
        }
        val store = MemStore()
        val v = vault(store, faultyAead)
        val custody = FakeCustody(realKey())
        val outcome = OperatorKeyMigration(custody, v).migrateIfNeeded("Basalt5#harbor Qw2nV zephyr".toCharArray())

        assertIs<OperatorKeyMigration.Outcome.Failed>(outcome)
        assertTrue(!custody.deleted, "a re-seal that does not verify NEVER deletes the plaintext (no silent key loss)")
        assertEquals(VaultState.Missing, v.state(), "the unverified vault is rolled back ⇒ migration retries next launch")
    }

    @Test
    fun unreadablePlaintext_failsSafe_notDeleted() {
        val store = MemStore()
        val custody = FakeCustody(kp = realKey(), loadThrows = true)
        val outcome = OperatorKeyMigration(custody, vault(store)).migrateIfNeeded("x-64bit".toCharArray())
        assertIs<OperatorKeyMigration.Outcome.Failed>(outcome)
        assertTrue(!custody.deleted, "an unreadable plaintext is never deleted")
    }

    @Test
    fun noMigration_whenVaultAlreadyExists() {
        val store = MemStore()
        val v = vault(store)
        v.enroll("existing-64bit".toCharArray(), realKey().private.encoded, realKey().public.encoded)
        val custody = FakeCustody(realKey())
        assertIs<OperatorKeyMigration.Outcome.NoMigrationNeeded>(OperatorKeyMigration(custody, v).migrateIfNeeded("x".toCharArray()))
        assertTrue(!custody.deleted, "an existing vault is never overwritten by migration")
    }

    @Test
    fun noMigration_whenNoPlaintext() {
        val custody = FakeCustody(kp = null)
        assertIs<OperatorKeyMigration.Outcome.NoMigrationNeeded>(OperatorKeyMigration(custody, vault(MemStore())).migrateIfNeeded("x".toCharArray()))
    }
}
