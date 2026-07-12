package com.tneff.cyppieagents.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The **one** phase axis every form-bearing auth screen renders (auth-spec §5.4), so `idle`/`loading`/
 * `error`/`rate-limited` look identical everywhere. **There is no spinner** (repo convention: no
 * `CircularProgressIndicator`) — [Submitting] disables the controls and the button shows a pending label.
 */
sealed interface Phase {
    data object Idle : Phase
    data object Submitting : Phase
    /** [key] is an i18n key resolved at the screen (generic for auth errors — no enumeration leak). */
    data class Error(val key: String) : Phase
    /** Honest 429; [retryAfter] is a server-supplied human wait hint (`%1$s`), or null. */
    data class RateLimited(val retryAfter: String? = null) : Phase
}

/**
 * The login-gate state (auth-spec §2). One value the [AuthGate] reads to render either an auth screen
 * **or** — at [Verified] — the existing desktop. Text field contents are **not** held here (they live
 * as local `remember` in each screen, cleared on screen change by construction); the state carries only
 * *which* screen, its [Phase], and the server-driven **neutral** messages (sent-to email, resend
 * outcome, reset done, token-invalid). This keeps the honesty invariants in the state, not inferred.
 */
sealed interface AuthUiState {
    /** Boot session probe in flight (§5.3) — no login flash before the probe answers. */
    data object Loading : AuthUiState

    /** The login hub. [github] carries the P2/P4 GitHub-OIDC sub-state (auth-spec §6). */
    data class Unauthenticated(val phase: Phase = Phase.Idle, val github: GithubUiState = GithubUiState.Idle) : AuthUiState

    /** Register sub-screen. */
    data class Register(val phase: Phase = Phase.Idle) : AuthUiState

    /** Forgot-password request. [sentTo] set → render the **neutral** "if an account exists…" line. */
    data class ForgotRequest(val phase: Phase = Phase.Idle, val sentTo: String? = null) : AuthUiState

    /**
     * The recovery **code**-entry step (CYP-227): Kratos is `use: code`, so [requestReset] leads here (neutral —
     * shown whether or not the address exists) to collect the 6-digit code + the new password. [sentTo] labels the
     * address the code was emailed to; [prefilledCode] pre-fills the field from a code-carrying reset deep-link.
     * [tokenInvalid] = a wrong/expired code (re-enterable, not terminal); [done] = terminal success.
     */
    data class ResetSetNew(
        val phase: Phase = Phase.Idle,
        val sentTo: String? = null,
        val prefilledCode: String? = null,
        val tokenInvalid: Boolean = false,
        val done: Boolean = false,
    ) : AuthUiState

    /**
     * Authenticated-but-**unverified** — the **hard gate** (§-Ask 2a): the desktop is NOT shown, only
     * verify + resend + logout. [email] is echoed for orientation (never as an existence proof).
     */
    data class AuthedUnverified(
        val email: String,
        val phase: Phase = Phase.Idle,
        val resendResult: ResendResult? = null,
        /** CYP-278 — true iff this hard gate was reached via the REGISTER path (as opposed to a login/session
         *  that is unverified). Set identically for a fresh registration AND an email collision (both are the
         *  single [RegisterResult.Pending]), so it discriminates the PATH, never account existence → it cannot
         *  leak whether the email exists. Drives the dedicated, dual-purpose register-collision notice + the
         *  sign-in/reset affordances; the login/session-unverified paths keep the existing verify copy. */
        val fromRegister: Boolean = false,
    ) : AuthUiState

    /** Verify deep-link landing. [tokenInvalid] → honest error instead of a silent success. */
    data class VerifySuccess(val tokenInvalid: Boolean = false) : AuthUiState

    /** Verified → the [AuthGate] mounts the existing desktop. [tier] rides through to gate the desktop's
     *  operator surfaces (CYP-186); fail-closed default MEMBER. */
    data class Verified(val tier: UserTier = UserTier.MEMBER) : AuthUiState
}

/**
 * The GitHub-OIDC sub-state on the login hub (auth-spec §6): its own honest sequence — [Redirecting] to
 * GitHub (controls disabled; the platform opens [Redirecting.url]), [Returning] while the callback completes,
 * [Error] on failure/cancel. A GitHub success is **not** special-cased into "logged in" — it flows through the
 * normal gate ([SessionState] → Verified / AuthedUnverified), so the S2 verified-gate applies unchanged.
 */
sealed interface GithubUiState {
    data object Idle : GithubUiState

    /** Web flavor (§6): the SPA navigates the browser to [url] and returns via `window.location`. */
    data class Redirecting(val url: String) : GithubUiState

    /**
     * CYP-474 §4 — the **Desktop-native loopback** flavor (RFC 8252 §7.3): the OS system browser is opened at
     * [url] (H3 — no embedded webview) and the app **waits for the localhost redirect** to return. Honest,
     * user-visible handoff ("Weiter im Browser …"); the host arms a loopback listener → [onGithubReturn].
     */
    data class BrowserHandoff(val url: String) : GithubUiState

    /** The callback returned; completing via the normal gate. [native] = the §4 "Zurück zur App …" copy vs the web copy. */
    data class Returning(val native: Boolean = false) : GithubUiState

    data object Error : GithubUiState
}

/**
 * Drives the login gate (auth-spec §2) over an [AuthRepository] (stub today; live REST after Backend's
 * seam — no VM/UI change). Every transition matches the §2.1 table; the server is the source of truth
 * for accept/reject/throttle and the VM **never guesses** an outcome or fakes success.
 *
 * Client-side field validation (empty→disabled, email shape, password mismatch) lives in the screens
 * (§3.2/§5.5); this VM owns only the server-driven transitions and the neutral messages.
 */
class AuthViewModel(
    private val repository: AuthRepository,
    /** CYP-474 §4: Desktop-native uses the RFC-8252 loopback flavor (system-browser + localhost return); web = false. */
    private val nativeOidcLoopback: Boolean = false,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope
    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    init { runScope.launch { checkSession() } }

    /** §7.1 boot probe → §2.1 (verified→desktop / unverified→hard gate / none→login). Fail-closed. */
    private suspend fun checkSession() {
        val s = runCatching { repository.session() }.getOrElse { e -> if (e is CancellationException) throw e; SessionState.None }
        _state.value = when (s) {
            is SessionState.Verified -> AuthUiState.Verified(s.tier)
            is SessionState.Unverified -> AuthUiState.AuthedUnverified(s.email)
            SessionState.None -> AuthUiState.Unauthenticated()
        }
    }

    // --- Navigation between sub-screens (no server call) ---
    fun goToLogin() { _state.value = AuthUiState.Unauthenticated() }
    fun goToRegister() { _state.value = AuthUiState.Register() }
    fun goToForgot() { _state.value = AuthUiState.ForgotRequest() }

    // --- Login (§7.2) ---
    fun login(email: String, password: String) {
        if (_state.value !is AuthUiState.Unauthenticated) return
        _state.value = AuthUiState.Unauthenticated(Phase.Submitting)
        runScope.launch {
            val r = runCatching { repository.login(email, password) }
                .getOrElse { e -> if (e is CancellationException) throw e; LoginResult.Rejected } // fail-closed → generic
            _state.value = when (r) {
                is LoginResult.Verified -> AuthUiState.Verified(r.tier) // tier rides the login success
                is LoginResult.Unverified -> AuthUiState.AuthedUnverified(r.email)
                LoginResult.Rejected -> AuthUiState.Unauthenticated(Phase.Error("auth_login_error_generic"))
                is LoginResult.RateLimited -> AuthUiState.Unauthenticated(Phase.RateLimited(r.retryAfter))
            }
        }
    }

    // --- Register (§7.3) — screen validates fields first; here only the server outcome ---
    fun register(email: String, password: String) {
        if (_state.value !is AuthUiState.Register) return
        _state.value = AuthUiState.Register(Phase.Submitting)
        runScope.launch {
            val r = runCatching { repository.register(email, password) }
                .getOrElse { e -> if (e is CancellationException) throw e; RegisterResult.InvalidInput }
            _state.value = when (r) {
                // CYP-278: fromRegister=true for BOTH a fresh registration AND an email collision (both are
                // RegisterResult.Pending) → the render is byte-identical, so this can never be an existence oracle.
                is RegisterResult.Pending -> AuthUiState.AuthedUnverified(r.email, fromRegister = true) // neutral, hard gate
                is RegisterResult.RateLimited -> AuthUiState.Register(Phase.RateLimited(r.retryAfter))
                RegisterResult.InvalidInput -> AuthUiState.Register(Phase.Error("auth_register_error_generic"))
            }
        }
    }

    // --- Forgot-password request (§7.4) — always neutral, never enumerating ---
    fun requestReset(email: String) {
        if (_state.value !is AuthUiState.ForgotRequest) return
        _state.value = AuthUiState.ForgotRequest(Phase.Submitting)
        runScope.launch {
            val r = runCatching { repository.requestReset(email) }
                .getOrElse { e -> if (e is CancellationException) throw e; ResetRequestResult.Accepted } // neutral even on failure
            _state.value = when (r) {
                // CYP-227: Kratos is code-recovery → advance to the code-entry step. Neutral, no enumeration: the
                // screen appears whether or not the address exists; only a valid code + account can succeed.
                ResetRequestResult.Accepted -> AuthUiState.ResetSetNew(sentTo = email)
                is ResetRequestResult.RateLimited -> AuthUiState.ForgotRequest(Phase.RateLimited(r.retryAfter))
            }
        }
    }

    // --- Reset deep-link (§2.2 / §7.5) — a code-carrying magic link pre-fills the code field (CYP-227 fallback) ---
    /** Entry from the platform reset deep-link handler. In code-recovery the link carries the code → pre-fill it. */
    fun openResetLink(token: String) {
        _state.value = AuthUiState.ResetSetNew(prefilledCode = token)
    }

    /**
     * CYP-227 — submit the emailed 6-digit recovery [code] + the [newPassword] to the held browser recovery flow
     * (`repository.setNewPassword` submits `method=code`, then sets the password on the elevated session). A
     * wrong/expired code → [ResetSetNew.tokenInvalid] on the SAME screen (re-enterable), never a terminal dead-end.
     */
    fun setNewPassword(code: String, newPassword: String) {
        val cur = _state.value as? AuthUiState.ResetSetNew ?: return
        if (code.isBlank()) { _state.value = cur.copy(tokenInvalid = true, phase = Phase.Idle); return }
        _state.value = cur.copy(phase = Phase.Submitting, tokenInvalid = false)
        runScope.launch {
            val r = runCatching { repository.setNewPassword(code, newPassword) }
                .getOrElse { e -> if (e is CancellationException) throw e; SetPasswordResult.TokenInvalid }
            _state.value = when (r) {
                SetPasswordResult.Ok -> AuthUiState.ResetSetNew(done = true)
                // Wrong/expired code — keep the entry screen (sentTo/prefill) so the user can re-type; not terminal.
                SetPasswordResult.TokenInvalid -> cur.copy(tokenInvalid = true, phase = Phase.Idle)
                is SetPasswordResult.RateLimited -> cur.copy(phase = Phase.RateLimited(r.retryAfter))
            }
        }
    }

    // --- Verify deep-link (§2.2 / §7.6) ---
    /** Entry from the platform verify deep-link handler. Token opaque, held nowhere renderable. */
    fun openVerifyLink(token: String) {
        _state.value = AuthUiState.Loading
        runScope.launch {
            val r = runCatching { repository.verifyEmail(token) }
                .getOrElse { e -> if (e is CancellationException) throw e; VerifyResult.TokenInvalid }
            _state.value = when (r) {
                VerifyResult.Ok -> AuthUiState.VerifySuccess()
                VerifyResult.TokenInvalid -> AuthUiState.VerifySuccess(tokenInvalid = true)
            }
        }
    }

    /** "Weiter" from [AuthUiState.VerifySuccess] → re-probe the session (§4.2). */
    fun continueAfterVerify() {
        _state.value = AuthUiState.Loading
        runScope.launch { checkSession() }
    }

    // --- Verify pending (hard gate, §4.1) ---
    fun resendVerification() {
        val cur = _state.value as? AuthUiState.AuthedUnverified ?: return
        _state.value = cur.copy(phase = Phase.Submitting, resendResult = null)
        runScope.launch {
            val r = runCatching { repository.resendVerification() }
                .getOrElse { e -> if (e is CancellationException) throw e; ResendResult.Accepted } // neutral on failure
            _state.value = cur.copy(phase = Phase.Idle, resendResult = r)
        }
    }

    // --- Logout (§7.8) — the always-available exit from the gate ---
    fun logout() {
        runScope.launch {
            runCatching { repository.logout() }
            _state.value = AuthUiState.Unauthenticated()
        }
    }

    // --- GitHub OIDC (§6 / P4 — CYP-185) ---

    /** Initiate the GitHub OIDC login (§5): the platform opens [GithubUiState.Redirecting.url] on success. */
    fun startGithub() {
        if (_state.value !is AuthUiState.Unauthenticated) return
        runScope.launch {
            val r = runCatching { repository.githubStart() }
                .getOrElse { e -> if (e is CancellationException) throw e; GithubStart.Error }
            val github = when (r) {
                // CYP-474 §4: Desktop-native → the loopback handoff (system-browser + localhost return); web → redirect.
                is GithubStart.Redirect ->
                    if (nativeOidcLoopback) GithubUiState.BrowserHandoff(r.url) else GithubUiState.Redirecting(r.url)
                // S1b: existing-email collision → Kratos requires login-first; surface (route to sign-in), NEVER merge.
                GithubStart.LoginRequired, GithubStart.Error -> GithubUiState.Error
            }
            (_state.value as? AuthUiState.Unauthenticated)?.let { _state.value = it.copy(github = github) }
        }
    }

    /**
     * The OIDC callback returned to the app → complete through the **normal gate** (no GitHub special-casing):
     * [session] maps the established session, so an OIDC identity's `verified=false` → [AuthedUnverified] (S2,
     * not one-click). Called by the platform's callback handler (deep-link / redirect return).
     */
    fun onGithubReturn() {
        _state.value = AuthUiState.Unauthenticated(github = GithubUiState.Returning(native = nativeOidcLoopback))
        runScope.launch {
            val s = runCatching { repository.session() }
                .getOrElse { e -> if (e is CancellationException) throw e; SessionState.None }
            _state.value = when (s) {
                is SessionState.Verified -> AuthUiState.Verified(s.tier)
                is SessionState.Unverified -> AuthUiState.AuthedUnverified(s.email) // S2 verified-gate
                SessionState.None -> AuthUiState.Unauthenticated(github = GithubUiState.Error) // no session established
            }
        }
    }

    /** Dismiss the GitHub redirect/error sub-state back to the plain login hub. */
    fun dismissGithub() {
        (_state.value as? AuthUiState.Unauthenticated)?.let { _state.value = it.copy(github = GithubUiState.Idle) }
    }

    /**
     * A 401 surfaced by a desktop call (session expired) → fail-closed back to Login (§2.1 last row).
     * Wired from the desktop's HTTP layer in a later real-swap; exposed now so the seam is complete.
     */
    fun onSessionExpired() { _state.value = AuthUiState.Unauthenticated() }
}
