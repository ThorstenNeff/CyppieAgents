package com.tneff.cyppieagents.net.hub.operator.vault

import com.tneff.cyppieagents.net.hub.operator.PersistentOperatorDeviceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyPair
import java.security.KeyPairGenerator

/**
 * CYP-542 / B1 — the jvm [OperatorEnrollController]: the operator **set-passphrase** orchestration (the enroll-UI
 * slice's business core; the Compose dialog + the connect-flow VM drive it via the commonMain interface). Ties: the
 * diceware one-click default (#1), the distinct strength verdict (AC-3), and **enroll-or-migrate** — a present CYP-525
 * plaintext key is **migrated** (re-sealed, anchor preserved, seal→verify→delete via [OperatorKeyMigration]), else a
 * fresh key is **first-enrolled**. All fail-closed; the floor is enforced here (typed causes for the UI) AND again at
 * [OperatorSecretVault.enroll] (F-#4 non-bypassable core).
 */
class OperatorEnrollment(
    private val vault: OperatorSecretVault,
    private val plaintextCustody: PlaintextKeyCustody,
    private val diceware: DicewareGenerator?,
    private val policy: CredentialPolicy,
    private val newKey: () -> KeyPair = { KeyPairGenerator.getInstance("Ed25519").generateKeyPair() },
) : OperatorEnrollController {

    override fun suggestPassphrase(): CharArray? = diceware?.generate()

    override fun validate(passphrase: CharArray): StrengthVerdict =
        PassphraseStrength.verdict(passphrase, policy.minEntropyBits)

    /**
     * Validate (typed refusal for the UI) → migrate the plaintext CYP-525 key if present (anchor preserved) else
     * first-enroll a fresh key. Runs on [Dispatchers.Default] (Argon2id is deliberately heavy — never the caller's
     * UI dispatcher). Never seals a weak/blocklisted passphrase; a migration that can't verify preserves the plaintext.
     */
    override suspend fun enroll(passphrase: CharArray): EnrollOutcome = withContext(Dispatchers.Default) {
        when (validate(passphrase)) {
            StrengthVerdict.TOO_WEAK -> return@withContext EnrollOutcome.TooWeak
            StrengthVerdict.BLOCKLISTED -> return@withContext EnrollOutcome.Blocklisted
            StrengthVerdict.OK -> Unit
        }
        when (OperatorKeyMigration(plaintextCustody, vault).migrateIfNeeded(passphrase)) {
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
}

/** The jvm [PlaintextKeyCustody] over the CYP-525 [PersistentOperatorDeviceKey] file (migration source). */
class PersistentPlaintextKeyCustody(private val key: PersistentOperatorDeviceKey) : PlaintextKeyCustody {
    override fun exists(): Boolean = key.exists()
    override fun load(): KeyPair = key.loadOrNull() ?: error("plaintext operator key present but unreadable")
    override fun delete() = key.delete()
}
