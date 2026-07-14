package com.tneff.cyppieagents.net.hub.operator.vault

import java.security.KeyPair

/**
 * CYP-542 / B1 (D6, H-3 — **key-loss-critical**) — one-time migration of the CYP-525 **plaintext** operator device key
 * (`~/.cyppie/operator-device.key`, PKCS#8 `0600`) into the passphrase-sealed [OperatorSecretVault], **preserving the
 * hub anchor byte-identically** (the same Ed25519 key ⇒ the same enrolled public key ⇒ **no re-enroll**, identity intact).
 *
 * **The order is the safety (Assist B3): seal → verify → THEN delete.** The plaintext file is deleted ONLY after the
 * re-sealed vault is proven to decrypt **byte-identical** to the original private key. A botched re-seal (verify fails)
 * ⇒ the vault is discarded (rolled back so migration retries) and the plaintext is **PRESERVED** — never a window where
 * the only copy is an unverified ciphertext (that would be silent key loss = identity gone). Fail-safe at every branch.
 *
 * Testable seam ([PlaintextKeyCustody]) so Team-1-Tester drives the B1 migration-integrity QA axis without real files.
 */
class OperatorKeyMigration(
    private val plaintext: PlaintextKeyCustody,
    private val vault: OperatorSecretVault,
) {
    sealed interface Outcome {
        /** A vault already exists, or there is no plaintext key to migrate (a fresh install ⇒ normal first-enroll). */
        data object NoMigrationNeeded : Outcome
        data object Migrated : Outcome
        /** The re-seal did not verify (or the plaintext was unreadable). The plaintext is **preserved**, the vault
         *  rolled back — no key loss; migration retries next launch. */
        data object Failed : Outcome
    }

    /**
     * Migrate iff a plaintext key exists and no vault does yet. [passphrase] seals the vault; the caller zeroizes it.
     * Never deletes the plaintext until the re-seal verifies byte-identical (H-3).
     */
    fun migrateIfNeeded(passphrase: CharArray): Outcome {
        if (vault.state() != VaultState.Missing) return Outcome.NoMigrationNeeded // a vault (or corrupt) exists — not migration
        if (!plaintext.exists()) return Outcome.NoMigrationNeeded                  // fresh install → normal enroll, not migration

        val kp: KeyPair = runCatching { plaintext.load() }.getOrNull() ?: return Outcome.Failed // unreadable ⇒ never delete
        val originalPriv = kp.private.encoded // PKCS#8 — transient plaintext (migration must read it to re-seal); zeroized below
        try {
            // SEAL: re-seal the SAME key (public key = anchor stays byte-identical ⇒ hub pin unchanged, no re-enroll).
            vault.enroll(passphrase, originalPriv, kp.public.encoded)

            // VERIFY before delete (H-3): the vault must decrypt byte-identical to the original private key.
            val opened = vault.open(passphrase)
            val verified = opened is VaultOpen.Unlocked && opened.privKeyPkcs8.contentEquals(originalPriv)
            (opened as? VaultOpen.Unlocked)?.privKeyPkcs8?.fill(0) // zeroize the verification copy (H-1)
            if (!verified) {
                vault.discard() // roll back the unverified vault; plaintext PRESERVED → retry next launch (no key loss)
                return Outcome.Failed
            }

            // Only now, proven, delete the plaintext (the sealed vault is the sole copy AND it verified).
            plaintext.delete()
            return Outcome.Migrated
        } finally {
            originalPriv.fill(0) // H-1: zeroize the transient plaintext private key
        }
    }
}

/**
 * The CYP-525 plaintext device-key custody as a seam (jvm impl wraps [PersistentOperatorDeviceKey] + the key file;
 * tests inject a fake). [load] returns the key pair or throws (⇒ migration fails safe, keeps the plaintext).
 */
interface PlaintextKeyCustody {
    fun exists(): Boolean
    fun load(): KeyPair
    fun delete()
}
