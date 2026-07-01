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

    /** The login hub. */
    data class Unauthenticated(val phase: Phase = Phase.Idle) : AuthUiState

    /** Register sub-screen. */
    data class Register(val phase: Phase = Phase.Idle) : AuthUiState

    /** Forgot-password request. [sentTo] set → render the **neutral** "if an account exists…" line. */
    data class ForgotRequest(val phase: Phase = Phase.Idle, val sentTo: String? = null) : AuthUiState

    /** Set-new-password (reached via the reset deep-link). [tokenInvalid]/[done] are terminal cues. */
    data class ResetSetNew(
        val phase: Phase = Phase.Idle,
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
    ) : AuthUiState

    /** Verify deep-link landing. [tokenInvalid] → honest error instead of a silent success. */
    data class VerifySuccess(val tokenInvalid: Boolean = false) : AuthUiState

    /** Verified → the [AuthGate] mounts the existing desktop. */
    data object Verified : AuthUiState
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
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope
    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    /** The reset deep-link token, held only in memory, never rendered/logged (auth-spec §2.2). */
    private var resetToken: String? = null

    init { runScope.launch { checkSession() } }

    /** §7.1 boot probe → §2.1 (verified→desktop / unverified→hard gate / none→login). Fail-closed. */
    private suspend fun checkSession() {
        val s = runCatching { repository.session() }.getOrElse { e -> if (e is CancellationException) throw e; SessionState.None }
        _state.value = when (s) {
            is SessionState.Verified -> AuthUiState.Verified
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
                LoginResult.Verified -> AuthUiState.Verified
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
                is RegisterResult.Pending -> AuthUiState.AuthedUnverified(r.email) // neutral, hard gate
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
                ResetRequestResult.Accepted -> AuthUiState.ForgotRequest(sentTo = email)
                is ResetRequestResult.RateLimited -> AuthUiState.ForgotRequest(Phase.RateLimited(r.retryAfter))
            }
        }
    }

    // --- Reset deep-link (§2.2 / §7.5) ---
    /** Entry from the platform reset deep-link handler. The token is held, never rendered. */
    fun openResetLink(token: String) {
        resetToken = token
        _state.value = AuthUiState.ResetSetNew()
    }

    fun setNewPassword(newPassword: String) {
        val cur = _state.value as? AuthUiState.ResetSetNew ?: return
        val token = resetToken ?: run {
            _state.value = cur.copy(tokenInvalid = true, phase = Phase.Idle); return
        }
        _state.value = cur.copy(phase = Phase.Submitting)
        runScope.launch {
            val r = runCatching { repository.setNewPassword(token, newPassword) }
                .getOrElse { e -> if (e is CancellationException) throw e; SetPasswordResult.TokenInvalid }
            _state.value = when (r) {
                SetPasswordResult.Ok -> AuthUiState.ResetSetNew(done = true)
                SetPasswordResult.TokenInvalid -> AuthUiState.ResetSetNew(tokenInvalid = true)
                is SetPasswordResult.RateLimited -> AuthUiState.ResetSetNew(phase = Phase.RateLimited(r.retryAfter))
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

    /**
     * A 401 surfaced by a desktop call (session expired) → fail-closed back to Login (§2.1 last row).
     * Wired from the desktop's HTTP layer in a later real-swap; exposed now so the seam is complete.
     */
    fun onSessionExpired() { _state.value = AuthUiState.Unauthenticated() }
}
