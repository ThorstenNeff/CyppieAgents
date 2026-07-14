package com.tneff.cyppieagents.auth

/**
 * In-memory [AuthRepository] the login-gate screens are built against until the Backend seam lands
 * (auth-spec §7; the CommApi/`FakeCommApi` and `ConfigRepository`-stub pattern). It is **scriptable**:
 * every outcome is a public settable lambda/field so a test — or the dev-run default — can drive any
 * branch of the §2 state machine deterministically, with **no timers and no randomness** (KMP-common
 * has no wall clock in the shared code path, and hermetic tests must be reproducible).
 *
 * Defaults describe a plausible **happy path** so the running app is click-through demoable:
 * boot = no session → Login; a non-blank login → Verified; register → neutral Pending; reset/verify/
 * resend → neutral accepted. The honesty contract (neutral/generic/rate-limited) is expressed through
 * the result *types*, never through leaky wording — a test flips a lambda to exercise the honest
 * rejection / 429 / token-invalid paths.
 */
class StubAuthRepository(
    /** Boot session (mutated by [logout] to [SessionState.None]). */
    var sessionState: SessionState = SessionState.None,
    var loginResult: (email: String, password: String) -> LoginResult = { _, _ -> LoginResult.Verified() },
    var registerResult: (email: String, password: String) -> RegisterResult = { email, _ -> RegisterResult.Pending(email) },
    var requestResetResult: (email: String) -> ResetRequestResult = { _ -> ResetRequestResult.Accepted },
    var setNewPasswordResult: (token: String, newPassword: String) -> SetPasswordResult = { _, _ -> SetPasswordResult.Ok },
    var verifyEmailResult: (token: String) -> VerifyResult = { _ -> VerifyResult.Ok },
    var resendVerificationResult: () -> ResendResult = { ResendResult.Accepted },
    var githubStartResult: () -> GithubStart = { GithubStart.Redirect("https://github.test/login/oauth/authorize") },
    /** CYP-576 — the native token-exchange outcome (init+return codes → resolved session). Default = Verified MEMBER. */
    var githubTokenExchangeResult: (initCode: String, returnToCode: String) -> SessionState = { _, _ -> SessionState.Verified(UserTier.MEMBER) },
) : AuthRepository {

    override suspend fun session(): SessionState = sessionState

    override suspend fun login(email: String, password: String): LoginResult =
        loginResult(email, password)

    override suspend fun register(email: String, password: String): RegisterResult =
        registerResult(email, password)

    override suspend fun requestReset(email: String): ResetRequestResult =
        requestResetResult(email)

    override suspend fun setNewPassword(token: String, newPassword: String): SetPasswordResult =
        setNewPasswordResult(token, newPassword)

    override suspend fun verifyEmail(token: String): VerifyResult =
        verifyEmailResult(token)

    override suspend fun resendVerification(): ResendResult =
        resendVerificationResult()

    override suspend fun githubStart(returnToState: String?): GithubStart = githubStartResult()

    override suspend fun githubTokenExchange(initCode: String, returnToCode: String): SessionState =
        githubTokenExchangeResult(initCode, returnToCode)

    override suspend fun logout() {
        sessionState = SessionState.None
    }
}
