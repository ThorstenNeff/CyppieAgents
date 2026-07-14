package com.tneff.cyppieagents.net.hub.operator.ui

/**
 * CYP-460 — testTag contract for the Desktop-Native operator-auth dialog (frozen `desktop-remote-operator-tags.md`).
 * Deepens the existing `remote` area (CYP-429): `remote.login.*` + `remote.authStep.*`. Shared API with QA (CYP-7) —
 * coordinated via the PO. `error.<cause>` carries the fail-closed taxonomy; `authRejected` is the terminal cause.
 */
object OperatorAuthTags {
    const val LOGIN_BROWSER_HANDOFF = "remote.login.browserHandoff"
    const val LOGIN_BROWSER_RETURN = "remote.login.browserReturn"

    const val PATH_HINT = "remote.authStep.pathHint"
    const val PIN_FIELD = "remote.authStep.pinField"
    const val PIN_REVEAL = "remote.authStep.pinReveal"
    const val BIOMETRIC_PROMPT = "remote.authStep.biometricPrompt"
    const val ENROLL = "remote.authStep.enroll" // reuse CYP-429
    const val ENROLL_PIN_SET = "remote.authStep.enrollPinSet"
    const val ATTEMPTS = "remote.authStep.attempts"
    const val LOCKED_OUT = "remote.authStep.lockedOut"

    // CYP-542 / B1 — the U1 set-passphrase enroll step (D4 replaces the enroll text-stub). `enrollStrength` = the
    // strength meter (D5 dampened for blocklist); `enrollSuggest` = the diceware one-click default button (#1).
    const val ENROLL_PASSPHRASE_FIELD = "remote.authStep.enrollPassphraseField"
    const val ENROLL_STRENGTH = "remote.authStep.enrollStrength"
    const val ENROLL_SUGGEST = "remote.authStep.enrollSuggest"

    /** `remote.authStep.error.<cause>`, cause ∈ pinWrong / biometricFailed / keystoreUnavailable / authRejected /
     *  needsEnroll / cancelled / **tooWeak** / **blocklisted** (CYP-542 D3 setup-time causes, H1-distinct). */
    fun error(cause: String) = "remote.authStep.error.$cause"
}
