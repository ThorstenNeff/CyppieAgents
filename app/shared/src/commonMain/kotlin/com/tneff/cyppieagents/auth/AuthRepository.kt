package com.tneff.cyppieagents.auth

/**
 * End-user authentication data port for the login gate (CYP-176). The client speaks **one**
 * `AuthRepository` seam (analogous to `ConfigRepository`/`AgentManagementRepository`): the Dev screens
 * are built against the [StubAuthRepository] today; the Backend delivers the real REST implementation
 * later with **no VM/UI change** (the CYP-119→CYP-120 real-swap pattern).
 *
 * **Field/type names are provisional** (auth-spec §7: "kursiv/offen bis Backend-Gegenlesen") — kept
 * deliberately small and swappable until Backend's design pass folds the wire contract in.
 *
 * The seam carries the **honesty contract** (auth-spec §1 / §7, PO-frozen §9):
 * - **No account enumeration:** [login] rejects **generically** ([LoginResult.Rejected]); [register]
 *   and [requestReset] answer **neutrally** and identically whether or not an account exists.
 * - **Rate-limit is honest (429):** every operation can return a `RateLimited` — never a fake "sent".
 * - **Deep-link tokens are opaque:** [setNewPassword]/[verifyEmail] take a token that is **never**
 *   rendered, logged, or materialised as a tag/key.
 *
 * > **Load-bearing backend coupling (auth-spec §7):** enumeration-safety lives or dies **server-side**
 * > — `login` must reply constant-time + uniform, and `register`/`requestReset` must answer independent
 * > of account existence. The neutral UI wording here is only half the mitigation.
 */
interface AuthRepository {
    /** §7.1 — boot session probe. Fail-closed: a network error resolves to [SessionState.None]. */
    suspend fun session(): SessionState

    /** §7.2 — credentials → verified / unverified / **generic** rejection / rate-limited. */
    suspend fun login(email: String, password: String): LoginResult

    /** §7.3 — **neutral** account creation (never "already exists"). */
    suspend fun register(email: String, password: String): RegisterResult

    /** §7.4 — **always** neutral-accepted (never "no such account"), or rate-limited. */
    suspend fun requestReset(email: String): ResetRequestResult

    /** §7.5 — opaque deep-link token + new password. */
    suspend fun setNewPassword(token: String, newPassword: String): SetPasswordResult

    /** §7.6 — opaque deep-link verify token. */
    suspend fun verifyEmail(token: String): VerifyResult

    /** §7.7 — resend the verification mail for the current (unverified) session; **neutral**. */
    suspend fun resendVerification(): ResendResult

    /** §7.8 — the always-available exit from the gate. */
    suspend fun logout()

    /**
     * §5 / P4 (CYP-185) — initiate the GitHub OIDC login: drive the Kratos login flow with
     * `{method:"oidc", provider:"github"}`; Kratos does state+PKCE and returns the GitHub redirect URL (the
     * platform opens it, then the session is read back via [session]). The platform never touches the OAuth
     * dance. Security default (S2): an OIDC identity is `verified=false` → [session] returns
     * [SessionState.Unverified] → the "verify your email" gate, **not** one-click access.
     */
    suspend fun githubStart(): GithubStart
}

/** §5 outcome of initiating the GitHub OIDC login. */
sealed interface GithubStart {
    /** The external GitHub OAuth [url] to open; after the callback the session is read via [AuthRepository.session]. */
    data class Redirect(val url: String) : GithubStart

    /** S1b — the GitHub email collides with an existing account; Kratos requires **login-first** (ownership
     *  proof) before linking. The client routes to sign-in — it never silently merges. */
    data object LoginRequired : GithubStart

    /** Initiation failed (flow error / cancelled). */
    data object Error : GithubStart
}

/** §7.1 boot probe outcome. [None] on no/invalid session **and** on network failure (fail-closed). */
sealed interface SessionState {
    data object None : SessionState
    data class Unverified(val email: String) : SessionState
    data object Verified : SessionState
}

/** §7.2. [Rejected] is **generic** (no enumeration); [retryAfter] is a server-supplied human hint. */
sealed interface LoginResult {
    data object Verified : LoginResult
    data class Unverified(val email: String) : LoginResult
    data object Rejected : LoginResult
    data class RateLimited(val retryAfter: String? = null) : LoginResult
}

/** §7.3. [Pending] carries the email only for **neutral** orientation, never as an existence proof. */
sealed interface RegisterResult {
    data class Pending(val email: String) : RegisterResult
    data class RateLimited(val retryAfter: String? = null) : RegisterResult
    data object InvalidInput : RegisterResult
}

/** §7.4. [Accepted] is returned **regardless** of account existence (enumeration-safe). */
sealed interface ResetRequestResult {
    data object Accepted : ResetRequestResult
    data class RateLimited(val retryAfter: String? = null) : ResetRequestResult
}

/** §7.5. */
sealed interface SetPasswordResult {
    data object Ok : SetPasswordResult
    data object TokenInvalid : SetPasswordResult
    data class RateLimited(val retryAfter: String? = null) : SetPasswordResult
}

/** §7.6. */
sealed interface VerifyResult {
    data object Ok : VerifyResult
    data object TokenInvalid : VerifyResult
}

/** §7.7. Doubles as the VM-held neutral resend outcome shown at `auth.verify.resendResult`. */
sealed interface ResendResult {
    data object Accepted : ResendResult
    data class RateLimited(val retryAfter: String? = null) : ResendResult
}
