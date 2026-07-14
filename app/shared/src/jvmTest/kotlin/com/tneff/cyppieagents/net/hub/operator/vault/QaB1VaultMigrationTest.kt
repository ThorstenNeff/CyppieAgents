package com.tneff.cyppieagents.net.hub.operator.vault

import java.security.KeyPair
import java.security.KeyPairGenerator
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-542 / B1 QA (Team-1-Tester axes) — the **corrupt-Vault→fail-closed** + **Migration-Integrität** teeth, front-loaded
 * pre-gate against the REAL vault crypto (real [BcArgon2PassphraseKdf] Argon2id + real [JceAead] AES-256-GCM; only the
 * at-rest [VaultStore] is an in-memory fake I control). Team-2 owns Passphrase-Floor + Blocklist; the shared seam is
 * A6 (a rejected seal leaves NO partial artifact) + the migrated-key-rides-the-same-Argon2id-custody KDF assert (B6).
 *
 * Load-bearing teeth (PO-flagged): **corrupt≠missing** (③ — a corrupt vault must NEVER be treated as first-enroll, the
 * CYP-525-H1b key-substitution vector) · **no-oracle** (tampered-ct is indistinguishable from wrong-passphrase) ·
 * **anchor byte-preservation** (migration keeps priv+pub byte-identical → same enrolled anchor, no re-enroll) ·
 * **crash-atomicity** (a re-seal that does not verify ⇒ vault discarded + plaintext PRESERVED — never key loss).
 */
class QaB1VaultMigrationTest {

    // Real Argon2id + real AES-GCM — the tamper/no-oracle teeth need the REAL AEAD tag as the verifier.
    private val kdf = BcArgon2PassphraseKdf()
    private val aead = JceAead()
    private val clock = 1_000_000L

    /** A strong passphrase that passes the ≥64-bit enroll floor (asserted in [floorPrecondition] so a bad choice fails fast). */
    private fun pp() = "7Gq-wZ2r-mK9v-tL4n-bH8j-pR3x-qD6s".toCharArray()

    @BeforeTest fun floorPrecondition() {
        assertEquals(
            StrengthVerdict.OK, PassphraseStrength.verdict(pp(), CredentialPolicy.SOFTWARE_MIN_ENTROPY_BITS),
            "test-passphrase precondition: must pass the ≥64-bit enroll floor (else adjust the literal)",
        )
    }

    /** In-memory [VaultStore] I fully control: drop the write (botched seal), corrupt the sealed ciphertext, or make
     *  the present file unreadable. The tamper targets the **sealed** field specifically so the AEAD tag fails (not a
     *  parse-Corrupt) — the precise no-oracle / verify-fail lever. */
    private class FakeVaultStore : VaultStore {
        var blob: ByteArray? = null
        var dropWrites = false          // simulate a seal that never persisted (botched write) → open sees Missing
        var tamperSealed = false        // corrupt the ciphertext field on read-back → AEAD tag fails on open
        var forceUnreadable = false     // present-but-unreadable (read()==null ⇒ CORRUPT)
        override fun exists(): Boolean = blob != null
        override fun read(): ByteArray? {
            if (forceUnreadable) return null
            val b = blob ?: return null
            if (!tamperSealed) return b
            val parsed = VaultBlob.decode(b.decodeToString())
            val s = parsed.sealed
            val flipped = (if (s[0] == 'A') 'B' else 'A') + s.substring(1) // a valid-base64 but different ciphertext
            return parsed.copy(sealed = flipped).encode().encodeToByteArray()
        }
        override fun write(bytes: ByteArray) { if (!dropWrites) blob = bytes.copyOf() }
        override fun delete() { blob = null }
    }

    private fun vault(store: VaultStore) = OperatorSecretVault(store, kdf, aead, nowMs = { clock })

    private fun ed25519(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    /** A [PlaintextKeyCustody] over a real Ed25519 key that records delete()/load-fail — the CYP-525 plaintext source. */
    private class FakeCustody(private val kp: KeyPair?, private val throwOnLoad: Boolean = false) : PlaintextKeyCustody {
        var deleted = false
        override fun exists(): Boolean = kp != null || throwOnLoad
        override fun load(): KeyPair = if (throwOnLoad || kp == null) error("plaintext unreadable") else kp
        override fun delete() { deleted = true }
    }

    // ════════════════════════════ Axis A — corrupt-Vault → fail-closed ════════════════════════════

    @Test
    fun a0_healthyVault_enrollThenOpen_roundTrips() {
        val store = FakeVaultStore()
        val v = vault(store)
        val kp = ed25519()
        v.enroll(pp(), kp.private.encoded, kp.public.encoded)
        assertEquals(VaultState.Enrolled, v.state(), "a sealed vault reports Enrolled")
        val opened = v.open(pp())
        assertTrue(opened is VaultOpen.Unlocked, "the correct passphrase unlocks")
        assertContentEquals(kp.private.encoded, (opened as VaultOpen.Unlocked).privKeyPkcs8, "unlock returns the exact private key")
    }

    @Test
    fun a1_corruptBlob_isCorruptNotMissing_neverReEnroll() {
        // ★③ the H1b key-substitution guard: a present-but-unparseable vault MUST be CORRUPT, never MISSING (which would
        //   trigger a silent first-enroll = an attacker who corrupts the file forces a key they control).
        val store = FakeVaultStore().apply { blob = "totally|not|a|valid|vault|blob".encodeToByteArray() }
        val v = vault(store)
        assertEquals(VaultState.Corrupt, v.state(), "★ an unparseable present vault is CORRUPT, not Missing (no silent re-enroll)")
        assertEquals(VaultOpen.Corrupt, v.open(pp()), "★ open() on a corrupt vault is fail-closed Corrupt, never re-enroll")
    }

    @Test
    fun a2_tamperedCiphertext_isFailClosed_noOracle_vsWrongPassphrase() {
        // ★ no-oracle: a tampered (but well-formed) ciphertext under the CORRECT passphrase yields the SAME outcome as an
        //   actual wrong passphrase — the AEAD tag catches both, indistinguishably; never Unlocked.
        val store = FakeVaultStore()
        val v = vault(store)
        val kp = ed25519()
        v.enroll(pp(), kp.private.encoded, kp.public.encoded)

        store.tamperSealed = true
        val tamperedOutcome = v.open(pp())                 // correct passphrase, tampered ciphertext
        store.tamperSealed = false
        val wrongPassOutcome = v.open("wrong-wrong-wrong-wrong-wrong-1".toCharArray()) // untampered, wrong passphrase

        assertFalse(tamperedOutcome is VaultOpen.Unlocked, "★ a tampered ciphertext NEVER unlocks (fail-closed)")
        assertEquals(
            wrongPassOutcome::class, tamperedOutcome::class,
            "★ no oracle: tampered-ct and wrong-passphrase are the SAME fail-closed outcome class (indistinguishable)",
        )
    }

    @Test
    fun a5_emptyAndUnreadable_areCorrupt_failClosed() {
        val empty = FakeVaultStore().apply { blob = ByteArray(0) }
        assertEquals(VaultState.Corrupt, vault(empty).state(), "a zero-length present vault is Corrupt (not Missing)")
        val unreadable = FakeVaultStore().apply { blob = "x".encodeToByteArray(); forceUnreadable = true }
        assertEquals(VaultState.Corrupt, vault(unreadable).state(), "a present-but-unreadable vault is Corrupt, fail-closed")
    }

    @Test
    fun a6_seam_rejectedWeakSeal_leavesNoPartialArtifact() {
        // My integrity half of the Team-2 seam: the CORE floor enforcement (F-#4) refuses a weak passphrase at the seal
        // choke-point; I assert NOTHING is written (no 0-byte / partial envelope that would later read as opaque-Corrupt).
        val store = FakeVaultStore()
        val v = vault(store)
        val kp = ed25519()
        assertFailsWith<IllegalArgumentException>("a below-floor passphrase is refused fail-closed at the core seal") {
            v.enroll("short".toCharArray(), kp.private.encoded, kp.public.encoded)
        }
        assertNull(store.blob, "★ a rejected seal leaves NO partial vault artifact (store untouched)")
        assertEquals(VaultState.Missing, v.state(), "the vault is still Missing after a refused enroll (clean first-enroll path intact)")
    }

    // ════════════════════════════ Axis B — Migration-Integrität ════════════════════════════

    @Test
    fun b0b1_migration_preservesAnchorByteIdentical_andSupersedesPlaintext() {
        // ★ anchor byte-preservation: migrate the plaintext key → the SAME priv+pub survive byte-identically (the enrolled
        //   hub anchor is unchanged ⇒ no re-enroll), and the plaintext is deleted (leak removed) — only AFTER verify.
        val kp = ed25519()
        val store = FakeVaultStore()
        val custody = FakeCustody(kp)
        val outcome = OperatorKeyMigration(custody, vault(store)).migrateIfNeeded(pp())

        assertEquals(OperatorKeyMigration.Outcome.Migrated, outcome, "the migration completes")
        val v = vault(store)
        assertContentEquals(kp.public.encoded, v.devicePublicKey(), "★ the enrolled anchor (X.509 pub) is byte-identical after migration")
        val opened = v.open(pp())
        assertContentEquals(kp.private.encoded, (opened as VaultOpen.Unlocked).privKeyPkcs8, "★ the private key survives byte-identical")
        assertTrue(custody.deleted, "the plaintext source is deleted (leak removed) AFTER the re-seal verified")
    }

    @Test
    fun b3_reSealThatDoesNotVerify_discardsVault_preservesPlaintext_noKeyLoss() {
        // ★ H-3 crash-atomicity: a botched re-seal (the seal did not persist/verify) MUST roll back the vault AND keep the
        //   plaintext — never a window where the only copy is an unverified/absent ciphertext (silent key loss).
        val kp = ed25519()
        val store = FakeVaultStore().apply { dropWrites = true } // the seal write never persists → open sees Missing → verify fails
        val custody = FakeCustody(kp)
        val outcome = OperatorKeyMigration(custody, vault(store)).migrateIfNeeded(pp())

        assertEquals(OperatorKeyMigration.Outcome.Failed, outcome, "★ a re-seal that does not verify FAILS (never falsely Migrated)")
        assertFalse(custody.deleted, "★ the plaintext key is PRESERVED (never deleted on a failed migration) — no key loss")
        assertNull(store.blob, "the unverified/absent vault is discarded (rolled back for retry next launch)")
    }

    @Test
    fun b3b_unreadablePlaintext_failsSafe_neverDeletes() {
        val store = FakeVaultStore()
        val custody = FakeCustody(kp = null, throwOnLoad = true)
        val outcome = OperatorKeyMigration(custody, vault(store)).migrateIfNeeded(pp())
        assertEquals(OperatorKeyMigration.Outcome.Failed, outcome, "an unreadable plaintext key ⇒ Failed (fail-safe)")
        assertFalse(custody.deleted, "★ an unreadable plaintext is NEVER deleted (no key loss)")
        assertNull(store.blob, "no vault is written on a load failure")
    }

    @Test
    fun bIdempotent_existingVaultOrNoPlaintext_isNoMigration() {
        // A vault already exists ⇒ NoMigrationNeeded (never re-migrate over a real vault / never delete plaintext blindly).
        val kp = ed25519()
        val enrolled = FakeVaultStore()
        vault(enrolled).enroll(pp(), kp.private.encoded, kp.public.encoded)
        val custody1 = FakeCustody(ed25519())
        assertEquals(
            OperatorKeyMigration.Outcome.NoMigrationNeeded,
            OperatorKeyMigration(custody1, vault(enrolled)).migrateIfNeeded(pp()),
            "an existing vault ⇒ no migration",
        )
        assertFalse(custody1.deleted, "an existing vault never deletes the plaintext")

        // No plaintext at all ⇒ fresh install ⇒ NoMigrationNeeded (normal first-enroll, not migration).
        assertEquals(
            OperatorKeyMigration.Outcome.NoMigrationNeeded,
            OperatorKeyMigration(FakeCustody(kp = null), vault(FakeVaultStore())).migrateIfNeeded(pp()),
            "no plaintext ⇒ no migration (fresh install)",
        )
    }

    @Test
    fun b6_migratedKey_ridesArgon2idCustody_notLegacyKdf() {
        // ★ KDF consistency (PO): after migration the key is held by the Argon2id vault — the persisted blob carries the
        //   FROZEN Argon2id cost params (m=64 MiB, t=3, p=1), NOT a legacy PBKDF2 envelope.
        val kp = ed25519()
        val store = FakeVaultStore()
        OperatorKeyMigration(FakeCustody(kp), vault(store)).migrateIfNeeded(pp())
        val blob = VaultBlob.decode(store.blob!!.decodeToString())
        assertEquals(Argon2Params.FROZEN.memoryKiB, blob.memKiB, "★ migrated vault uses Argon2id FROZEN memory cost (64 MiB), not PBKDF2")
        assertEquals(Argon2Params.FROZEN.iterations, blob.iter, "★ Argon2id FROZEN iterations (t≥3)")
        assertEquals(Argon2Params.FROZEN.parallelism, blob.par, "★ Argon2id FROZEN parallelism (p=1)")
        assertTrue(blob.v >= 1 && blob.sealed.isNotEmpty(), "the migrated blob is a well-formed sealed envelope")
    }
}
