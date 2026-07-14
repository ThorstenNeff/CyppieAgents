package com.tneff.cyppieagents.net.hub.operator.vault

/**
 * CYP-542 / B1 — the commonMain contract the connect-flow VM (and the CYP-460 dialog it drives) hold to run the
 * operator **set-passphrase** step, so the state machine lives in `commonMain` while the crypto impl (Argon2id vault
 * seal / migrate) stays jvm-only ([OperatorEnrollment] implements this). Kept an interface (not the concrete jvm
 * class) so `RemoteConnectComponents`/`HubConnectViewModel` can hold it without a jvm dependency; `null` ⇒ INERT.
 *
 * F-#4 (non-bypassable): the strength floor + blocklist are enforced in the CORE ([enroll] → [OperatorSecretVault.enroll],
 * `verdict()` fail-closed) — [validate] here is ONLY for the meter/error copy (a UI signal), never THE gate.
 */
interface OperatorEnrollController {
    /** The strong one-click default (diceware ≥77 bit), or `null` if the EFF wordlist asset is unavailable
     *  (⇒ the UX offers only type-your-own — never a weak generated default). The caller zeroizes it after use. */
    fun suggestPassphrase(): CharArray?

    /** The distinct enroll verdict for the meter + error copy (AC-3): OK / TOO_WEAK / BLOCKLISTED. Advisory — the
     *  authoritative refusal is [enroll]'s core-enforcement, never this signal (F-#4). */
    fun validate(passphrase: CharArray): StrengthVerdict

    /**
     * Set the operator passphrase (the caller zeroizes it after). `suspend` because the impl runs Argon2id (deliberately
     * heavy) off the caller's dispatcher. Validate → migrate a present CYP-525 plaintext key (anchor preserved) else
     * first-enroll a fresh key. Never seals a weak/blocklisted passphrase; a migration that can't verify preserves the
     * plaintext (no key loss).
     */
    suspend fun enroll(passphrase: CharArray): EnrollOutcome
}

/** The typed [OperatorEnrollController.enroll] outcome the UI routes on (distinct causes for distinct copy, H1). */
sealed interface EnrollOutcome {
    /** The vault is sealed (migrated or first-enrolled) — the operator can now authenticate with this passphrase. */
    data object Enrolled : EnrollOutcome
    data object TooWeak : EnrollOutcome
    data object Blocklisted : EnrollOutcome
    /** The migration re-seal did not verify — the plaintext key is PRESERVED (no key loss); retry / OOB-recover. */
    data object MigrationFailed : EnrollOutcome
    data object AlreadyEnrolled : EnrollOutcome
}
