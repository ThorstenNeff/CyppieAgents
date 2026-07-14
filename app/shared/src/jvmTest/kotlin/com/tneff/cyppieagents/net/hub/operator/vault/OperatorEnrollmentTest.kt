package com.tneff.cyppieagents.net.hub.operator.vault

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-542 / B1 — [OperatorEnrollment] teeth (the enroll-UI slice's business core): the diceware default, the distinct
 * strength verdict (AC-3), and enroll-or-migrate (a present CYP-525 plaintext key is migrated anchor-preserving, else
 * a fresh key is first-enrolled; weak/blocklisted refused; migration-fail preserves the plaintext).
 */
class OperatorEnrollmentTest {

    private class MemStore(var blob: ByteArray? = null) : VaultStore {
        override fun exists() = blob != null
        override fun read() = blob
        override fun write(bytes: ByteArray) { blob = bytes }
        override fun delete() { blob = null }
    }

    private class FakeCustody(private val kp: KeyPair?) : PlaintextKeyCustody {
        var present = kp != null; var deleted = false
        override fun exists() = present
        override fun load() = kp!!
        override fun delete() { deleted = true; present = false }
    }

    private val fakeKdf = PassphraseKdf { p, salt, _ ->
        MessageDigest.getInstance("SHA-256").apply { update(p.concatToString().encodeToByteArray()); update(salt) }.digest()
    }
    private val policy = CredentialPolicy.forCapability(OperatorAuthCapability.SOFTWARE_ONLY, 5, 30_000, 900_000)
    private val strong = "Zephyr7!mQ anchor-mint Kx9vB"
    private fun realKey() = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private fun vault(store: MemStore, aead: Aead = JceAead()) = OperatorSecretVault(store, fakeKdf, aead, { 0L })
    private fun diceware() = DicewareGenerator(List(DicewareGenerator.EFF_LARGE_SIZE) { "w$it" }, { n -> ByteArray(n) })

    @Test
    fun suggestPassphrase_diceware_orNullWhenAssetUnavailable() {
        val withList = OperatorEnrollment(vault(MemStore()), FakeCustody(null), diceware(), policy)
        assertNotNull(withList.suggestPassphrase()).let { assertTrue(it.count { c -> c == ' ' } == 5, "6-word diceware default") }
        val noList = OperatorEnrollment(vault(MemStore()), FakeCustody(null), diceware = null, policy)
        assertNull(noList.suggestPassphrase(), "no wordlist ⇒ no weak generated default (type-your-own only)")
    }

    @Test
    fun enroll_weakOrBlocklisted_refusedFailClosed_noSeal() {
        val store = MemStore()
        val e = OperatorEnrollment(vault(store), FakeCustody(null), diceware(), policy)
        assertIs<OperatorEnrollment.EnrollOutcome.TooWeak>(e.enroll("hunter2".toCharArray()))
        assertIs<OperatorEnrollment.EnrollOutcome.Blocklisted>(e.enroll("correct horse battery staple".toCharArray()))
        assertNull(store.blob, "a refused enroll seals nothing")
    }

    @Test
    fun enroll_withPlaintextKey_migrates_anchorPreserved() {
        val kp = realKey()
        val store = MemStore()
        val v = vault(store)
        val custody = FakeCustody(kp)
        val e = OperatorEnrollment(v, custody, diceware(), policy)
        assertIs<OperatorEnrollment.EnrollOutcome.Enrolled>(e.enroll(strong.toCharArray()))
        assertTrue(custody.deleted, "the plaintext is deleted after a verified re-seal")
        val opened = v.open(strong.toCharArray())
        assertIs<VaultOpen.Unlocked>(opened)
        assertContentEquals(kp.private.encoded, opened.privKeyPkcs8, "migrated — the SAME key")
        assertContentEquals(kp.public.encoded, v.devicePublicKey(), "the hub anchor (pubkey) is preserved")
    }

    @Test
    fun enroll_noPlaintext_firstEnrollsFreshKey() {
        val store = MemStore()
        val v = vault(store)
        val fresh = realKey()
        val e = OperatorEnrollment(v, FakeCustody(null), diceware(), policy, newKey = { fresh })
        assertIs<OperatorEnrollment.EnrollOutcome.Enrolled>(e.enroll(strong.toCharArray()))
        assertContentEquals(fresh.public.encoded, v.devicePublicKey(), "first-enroll sealed the fresh key (new anchor)")
    }

    @Test
    fun enroll_migrationVerifyFails_preservesPlaintext_noKeyLoss() {
        val faulty = object : Aead {
            val real = JceAead()
            override fun seal(k: ByteArray, n: ByteArray, p: ByteArray, a: ByteArray) = real.seal(k, n, p, a)
            override fun open(k: ByteArray, n: ByteArray, s: ByteArray, a: ByteArray) = ByteArray(48) // wrong
            override fun randomBytes(n: Int) = real.randomBytes(n)
        }
        val store = MemStore()
        val custody = FakeCustody(realKey())
        val e = OperatorEnrollment(vault(store, faulty), custody, diceware(), policy)
        assertIs<OperatorEnrollment.EnrollOutcome.MigrationFailed>(e.enroll(strong.toCharArray()))
        assertTrue(!custody.deleted, "a re-seal that does not verify NEVER deletes the plaintext (no key loss)")
    }
}
