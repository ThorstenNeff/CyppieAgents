package com.tneff.cyppieagents.net.hub.operator.ui

/**
 * CYP-460 — the operator-auth **error taxonomy** (§5.3, `desktop-remote-operator-tokens.json`). The one HARD
 * honesty rail (**H2**): a **LOCAL** failure (before / without a hub verdict — wrong PIN, lockout, biometric fail,
 * cancel, no key) is **retryable, neutral / error-tone, NEVER a terminal errorContainer**; only [HubRejected]
 * (maps to `net.hub.remote.RemoteFailure.AuthRejected`) is **terminal** — errorContainer, "sign in again", no
 * silent retry. This is the transport-independent classification the dialog + copy render against.
 */
sealed interface OperatorAuthError {
    /** Local: wrong PIN before the assertion, with the remaining-attempt count (Threat-Model-parametrised, Q2/Q3). */
    data class WrongPin(val attemptsLeft: Int) : OperatorAuthError
    /** Local: temporary lockout after N failures — its OWN tag ([OperatorAuthTags.LOCKED_OUT]), NOT an `error.<cause>`. */
    data class LockedOut(val retryAfter: String) : OperatorAuthError
    /** Local: platform biometrics failed → visible fallback to the PIN (Raw path, H1). */
    data object BiometricFailed : OperatorAuthError
    /** Local: the OS keychain is unavailable. */
    data object KeystoreUnavailable : OperatorAuthError
    /** Local: no operator device-key enrolled → the enrollment flow (not an auth failure). */
    data object NeedsEnroll : OperatorAuthError
    /** Local: the user cancelled the prompt. */
    data object Cancelled : OperatorAuthError
    /** **Hub-side, TERMINAL** — the hub's `OperatorAssertionVerifier` said no. errorContainer, re-login, no retry. */
    data object HubRejected : OperatorAuthError
}

/** H2: ONLY a hub reject is terminal. Every local failure is retryable (retry / fallback / enroll). */
val OperatorAuthError.isTerminal: Boolean
    get() = this is OperatorAuthError.HubRejected

/**
 * The testTag for this error (tags.md). `LockedOut` gets its own [OperatorAuthTags.LOCKED_OUT]; all others are
 * `remote.authStep.error.<cause>` with `<cause>` ∈ pinWrong / biometricFailed / keystoreUnavailable / authRejected /
 * needsEnroll / cancelled. Note `authRejected` (terminal) is a sibling value of the local causes — never merged.
 */
fun OperatorAuthError.tag(): String = when (this) {
    is OperatorAuthError.WrongPin -> OperatorAuthTags.error("pinWrong")
    is OperatorAuthError.LockedOut -> OperatorAuthTags.LOCKED_OUT
    OperatorAuthError.BiometricFailed -> OperatorAuthTags.error("biometricFailed")
    OperatorAuthError.KeystoreUnavailable -> OperatorAuthTags.error("keystoreUnavailable")
    OperatorAuthError.NeedsEnroll -> OperatorAuthTags.error("needsEnroll")
    OperatorAuthError.Cancelled -> OperatorAuthTags.error("cancelled")
    OperatorAuthError.HubRejected -> OperatorAuthTags.error("authRejected")
}
