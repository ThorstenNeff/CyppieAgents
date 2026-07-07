package com.tneff.cyppieagents.auth

/**
 * `testTag` contract for the end-user auth / login-gate (CYP-176), exactly per `docs/design/auth-tags.md`.
 * Test-Contract v0.5 §2: prefixless `<area>[.<scopeId>].<element>`, segment values `[A-Za-z0-9-]+`
 * (camelCase, no dots). **Area `auth` is new** — 0 collision against the existing 13 `*Tags.kt`.
 * 48 tags (incl. the dedicated register/reset `rateLimited` nodes, spec refine 1982acb).
 * **Shared API with QA (CYP-7) — do not rename silently; coordinate via the PO.**
 *
 * Deep-link verify/reset tokens are **never** materialised as a tag segment (auth-spec §2.2).
 */
object AuthTags {
    const val AREA = "auth"

    // --- Gate / bootstrap ---
    const val GATE = "auth.gate"
    const val LOADING = "auth.loading"

    // --- Login (scopeId = login) ---
    const val LOGIN_FORM = "auth.login.form"
    const val LOGIN_EMAIL = "auth.login.email"
    const val LOGIN_PASSWORD = "auth.login.password"
    const val LOGIN_PASSWORD_REVEAL = "auth.login.passwordReveal"
    const val LOGIN_SUBMIT = "auth.login.submit"
    const val LOGIN_TO_REGISTER = "auth.login.toRegister"
    const val LOGIN_TO_FORGOT = "auth.login.toForgot"
    const val LOGIN_ERROR = "auth.login.error"
    const val LOGIN_RATE_LIMITED = "auth.login.rateLimited"

    /** P2 — "Sign in with GitHub" button (rendered when the P2 OIDC slice lands; auth-spec §6). */
    const val LOGIN_GITHUB = "auth.login.github"

    // --- Register (scopeId = register) ---
    const val REGISTER_FORM = "auth.register.form"
    const val REGISTER_EMAIL = "auth.register.email"
    const val REGISTER_PASSWORD = "auth.register.password"
    const val REGISTER_PASSWORD_REVEAL = "auth.register.passwordReveal"
    const val REGISTER_PASSWORD_CONFIRM = "auth.register.passwordConfirm"
    const val REGISTER_SUBMIT = "auth.register.submit"
    const val REGISTER_TO_LOGIN = "auth.register.toLogin"
    const val REGISTER_ERROR = "auth.register.error"
    const val REGISTER_RATE_LIMITED = "auth.register.rateLimited"

    // --- Email verification (scopeId = verify) ---
    const val VERIFY_PENDING = "auth.verify.pending"
    const val VERIFY_EMAIL = "auth.verify.email"
    const val VERIFY_GATE_HINT = "auth.verify.gateHint"
    const val VERIFY_RESEND = "auth.verify.resend"
    const val VERIFY_RESEND_RESULT = "auth.verify.resendResult"
    const val VERIFY_LOGOUT = "auth.verify.logout"
    const val VERIFY_SUCCESS = "auth.verify.success"
    const val VERIFY_CONTINUE = "auth.verify.continue"
    const val VERIFY_ERROR = "auth.verify.error"
    // CYP-278 — the register-collision notice's affordance line (shown only on the register-path verify gate;
    // canonical `to<Target>` idiom, verify scope). Coordinated with QA/CYP-7; mirror in docs/design/auth-tags.md.
    const val VERIFY_TO_LOGIN = "auth.verify.toLogin"
    const val VERIFY_TO_FORGOT = "auth.verify.toForgot"

    // --- Forgot password (scopeId = forgot) ---
    const val FORGOT_FORM = "auth.forgot.form"
    const val FORGOT_EMAIL = "auth.forgot.email"
    const val FORGOT_SUBMIT = "auth.forgot.submit"
    const val FORGOT_TO_LOGIN = "auth.forgot.toLogin"
    const val FORGOT_SENT = "auth.forgot.sent"
    const val FORGOT_RATE_LIMITED = "auth.forgot.rateLimited"

    // --- Reset password / set-new (scopeId = reset) ---
    const val RESET_FORM = "auth.reset.form"
    const val RESET_CODE = "auth.reset.code"
    const val RESET_TO_FORGOT = "auth.reset.toForgot"
    const val RESET_PASSWORD = "auth.reset.password"
    const val RESET_PASSWORD_REVEAL = "auth.reset.passwordReveal"
    const val RESET_PASSWORD_CONFIRM = "auth.reset.passwordConfirm"
    const val RESET_SUBMIT = "auth.reset.submit"
    const val RESET_SUCCESS = "auth.reset.success"
    const val RESET_TOKEN_INVALID = "auth.reset.tokenInvalid"
    const val RESET_ERROR = "auth.reset.error"
    const val RESET_RATE_LIMITED = "auth.reset.rateLimited"

    // --- P2 GitHub OIDC states (scopeId = github; rendered when the P2 slice lands) ---
    const val GITHUB_REDIRECTING = "auth.github.redirecting"
    const val GITHUB_RETURNING = "auth.github.returning"
    const val GITHUB_ERROR = "auth.github.error"
}
