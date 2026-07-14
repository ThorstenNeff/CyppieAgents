package com.tneff.cyppieagents.net.hub.operator.vault

import com.tneff.cyppieagents.net.hub.operator.PersistentOperatorDeviceKey
import java.security.KeyPair
import java.security.KeyPairGenerator

/**
 * CYP-542 / B1 — the operator **set-passphrase** orchestration (the enroll-UI slice's business core; the Compose
 * dialog + the connect-flow VM drive it). Ties: the diceware one-click default (#1), the distinct strength verdict
 * (AC-3), and **enroll-or-migrate** — a present CYP-525 plaintext key is **migrated** (re-sealed, anchor preserved,
 * seal→verify→delete via [OperatorKeyMigration]), else a fresh key is **first-enrolled**. All fail-closed; the floor
 * is enforced here (typed causes for the UI) AND again at [OperatorSecretVault.enroll] (F-#4 non-bypassable core).
 */
class OperatorEnrollment(
    private val vault: OperatorSecretVault,
    private val plaintextCustody: PlaintextKeyCustody,
    private val diceware: DicewareGenerator?,
    private val policy: CredentialPolicy,
    private val newKey: () -> KeyPair = { KeyPairGenerator.getInstance("Ed25519").generateKeyPair() },
) {
    /** The strong one-click default (diceware ≥77 bit), or `null` if the EFF wordlist asset is unavailable
     *  (⇒ the UX offers only type-your-own — never a weak generated default). The caller zeroizes it after use. */
    fun suggestPassphrase(): CharArray? = diceware?.generate()

    /** The distinct enroll verdict for the meter + error copy (AC-3): OK / TOO_WEAK / BLOCKLISTED. */
    fun validate(passphrase: CharArray): StrengthVerdict = PassphraseStrength.verdict(passphrase, policy.minEntropyBits)

    /**
     * Set the operator passphrase (the caller zeroizes it after). Validate (typed refusal for the UI) → migrate the
     * plaintext CYP-525 key if present (anchor preserved) else first-enroll a fresh key. Never seals a weak/blocklisted
     * passphrase; a migration that can't verify preserves the plaintext (no key loss).
     */
    fun enroll(passphrase: CharArray): EnrollOutcome {
        when (validate(passphrase)) {
            StrengthVerdict.TOO_WEAK -> return EnrollOutcome.TooWeak
            StrengthVerdict.BLOCKLISTED -> return EnrollOutcome.Blocklisted
            StrengthVerdict.OK -> Unit
        }
        return when (OperatorKeyMigration(plaintextCustody, vault).migrateIfNeeded(passphrase)) {
            OperatorKeyMigration.Outcome.Migrated -> EnrollOutcome.Enrolled // CYP-525 key re-sealed, hub anchor intact
            OperatorKeyMigration.Outcome.Failed -> EnrollOutcome.MigrationFailed // plaintext preserved (fail-safe)
            OperatorKeyMigration.Outcome.NoMigrationNeeded ->
                if (vault.state() != VaultState.Missing) {
                    EnrollOutcome.AlreadyEnrolled // a vault exists — not a first-enroll
                } else {
                    val kp = newKey() // no plaintext to migrate ⇒ first-enroll a fresh Ed25519 (new hub-TOFU anchor)
                    vault.enroll(passphrase, kp.private.encoded, kp.public.encoded)
                    EnrollOutcome.Enrolled
                }
        }
    }

    sealed interface EnrollOutcome {
        /** The vault is sealed (migrated or first-enrolled) — the operator can now authenticate with this passphrase. */
        data object Enrolled : EnrollOutcome
        data object TooWeak : EnrollOutcome
        data object Blocklisted : EnrollOutcome
        /** The migration re-seal did not verify — the plaintext key is PRESERVED (no key loss); retry / OOB-recover. */
        data object MigrationFailed : EnrollOutcome
        data object AlreadyEnrolled : EnrollOutcome
    }
}

/** The jvm [PlaintextKeyCustody] over the CYP-525 [PersistentOperatorDeviceKey] file (migration source). */
class PersistentPlaintextKeyCustody(private val key: PersistentOperatorDeviceKey) : PlaintextKeyCustody {
    override fun exists(): Boolean = key.exists()
    override fun load(): KeyPair = key.loadOrNull() ?: error("plaintext operator key present but unreadable")
    override fun delete() = key.delete()
}
