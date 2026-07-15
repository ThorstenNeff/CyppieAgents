package com.tneff.cyppieagents.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    /** CYP-576 follow-on (busy-guard) — the synchronous in-flight state set on [startGithub] entry, BEFORE the async
     *  `githubStart()` resolves, so the button is disabled immediately and a double-click can't launch two flows. */
    data object Starting : GithubUiState

    /** Web flavor (§6): the SPA navigates the browser to [url] and returns via `window.location`. */
    data class Redirecting(val url: String) : GithubUiState

    /**
     * CYP-474 §4 — the **Desktop-native loopback** flavor (RFC 8252 §7.3): the OS system browser is opened at
     * [url] (H3 — no embedded webview) and the app **waits for the localhost redirect** to return. Honest,
     * user-visible handoff ("Weiter im Browser …"); the host arms a loopback listener → [onGithubReturn]. CYP-576
     * follow-on §2: the [url] is now RENDERED (visible fallback) so an unopened browser is never a dead end.
     */
    data class BrowserHandoff(val url: String) : GithubUiState

    /** CYP-576 follow-on §3 — the handoff waited past the watchdog with no loopback return. Advisory (INFO, NOT an
     *  error): nothing is broken, it's just slow. [url] stays visible + a retry is offered. */
    data class TimedOut(val url: String) : GithubUiState

    /** The callback returned; completing via the normal gate. [native] = the §4 "Zurück zur App …" copy vs the web copy. */
    data class Returning(val native: Boolean = false) : GithubUiState

    /**
     * CYP-576 follow-on §4 — a REAL failure (the flow start failed, or the callback returned no session). Actionable:
     * retexted copy ("didn't complete… try again or sign in with email") + a retry. NEVER implies the user cancelled.
     */
    data object Error : GithubUiState

    /** CYP-576 follow-on §4 — S1b: the GitHub email collides with an existing account; Kratos requires login-first.
     *  A DISTINCT state (was wrongly folded into [Error]) that signposts the email sign-in, never a generic failure. */
    data object LoginRequired : GithubUiState

    /** CYP-576 follow-on §4 — the user CANCELLED at GitHub (`?error=access_denied` at the loopback). Neutral/INFO — no
     *  alarm, no "failed": the user deliberately stopped. Distinct from [Error] (Backend carries error≠cancel). */
    data object Cancelled : GithubUiState
}

/** CYP-576 follow-on (busy-guard) — an OIDC attempt is in flight; the "Mit GitHub anmelden" button is disabled and a
 *  new attempt is refused. [GithubUiState.TimedOut]/[GithubUiState.Error]/[GithubUiState.Cancelled] are NOT busy —
 *  they are retry-able. The VM guard and the render share this ONE predicate so they can't diverge. */
val GithubUiState.isBusy: Boolean
    get() = this is GithubUiState.Starting || this is GithubUiState.Redirecting ||
        this is GithubUiState.BrowserHandoff || this is GithubUiState.Returning

/**
 * Drives the login gate (auth-spec §2) over an [AuthRepository] (stub today; live REST after Backend's
 * seam — no VM/UI change). Every transition matches the §2.1 table; the server is the source of truth
 * for accept/reject/throttle and the VM **never guesses** an outcome or fakes success.
 *
 * Client-side field validation (empty→disabled, email shape, password mismatch) lives in the screens
 * (§3.2/§5.5); this VM owns only the server-driven transitions and the neutral messages.
 */
/** CYP-576 P1 (BUG-A/CYP-578): the desktop OIDC handoff watchdog. If no loopback return arrives within this window
 *  the VM leaves the "Continuing in your browser…" state for a retry-able Error instead of hanging forever (a bind
 *  failure / abandoned tab / any hiccup). The richer TimedOut(url) advisory is the UIUX follow-on. */
const val OIDC_HANDOFF_TIMEOUT_MS: Long = 30_000L

class AuthViewModel(
    private val repository: AuthRepository,
    /** CYP-474 §4: Desktop-native uses the RFC-8252 loopback flavor (system-browser + localhost return); web = false. */
    private val nativeOidcLoopback: Boolean = false,
    scope: CoroutineScope? = null,
    /** CYP-576 P1: app-generated `state` nonce provider for the native OIDC handoff (Backend security-rec). The desktop
     *  host injects a CSPRNG (`SecureRandom`); `null` (default) ⇒ no nonce (web/non-native/tests that don't exercise it). */
    private val newOidcState: () -> String? = { null },
    /** CYP-576 P1: the handoff watchdog window (see [OIDC_HANDOFF_TIMEOUT_MS]). Injectable so a test drives it fast. */
    private val handoffTimeoutMs: Long = OIDC_HANDOFF_TIMEOUT_MS,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope
    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    init { runScope.launch { checkSession() } }

    override fun onCleared() {
        clearPendingOidc() // Assist-C1/CYP-578: never leak the loopback server (port 47472) past this VM's life
        super.onCleared()
    }

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

    /** CYP-576 — the native API-flow `session_token_exchange_code` (init half), held between [startGithub] and
     *  [onGithubReturn] so the loopback `return_to_code` can be redeemed with it. One-shot: cleared after every return
     *  so a stale code is never replayed. Desktop-native only (null on the web redirect path). */
    private var pendingExchangeInitCode: String? = null

    /** CYP-576 P1 — the app-generated `state` nonce for the in-flight native attempt; the loopback callback must echo
     *  it or it's rejected (the loopback is unauth). One-shot, cleared with the init code. */
    private var pendingState: String? = null

    /** CYP-576 P1 — the handoff watchdog (breaks the "Continuing…" hang after [handoffTimeoutMs]). Cancelled on return. */
    private var handoffTimeoutJob: Job? = null

    /** CYP-576 follow-on (Assist-C1/CYP-578) — a stop-handle for THIS attempt's loopback HTTP server, registered by the
     *  desktop host after arming ([setLoopbackStopper]). The server is single-shot but must be GUARANTEED-stopped on
     *  every exit (timeout / error / cancel / retry / teardown), not only a successful callback — else port 47472 stays
     *  bound and the next attempt fails to re-bind (the user who aborts the tab + retries can't log in until restart). */
    private var loopbackStopper: (() -> Unit)? = null

    /** CYP-576 follow-on — the desktop host hands back the loopback server's stop-handle after arming. */
    fun setLoopbackStopper(stopper: (() -> Unit)?) { loopbackStopper = stopper }

    private fun clearPendingOidc() {
        pendingExchangeInitCode = null
        pendingState = null
        handoffTimeoutJob?.cancel()
        handoffTimeoutJob = null
        // Free port 47472 on EVERY abandon path so a retry re-binds cleanly (Assist-C1/CYP-578). Idempotent.
        loopbackStopper?.invoke()
        loopbackStopper = null
    }

    /** Initiate the GitHub OIDC login (§5): the platform opens [GithubUiState.Redirecting.url] on success. */
    fun startGithub() {
        val current = _state.value
        if (current !is AuthUiState.Unauthenticated) return
        // CYP-576 follow-on (busy-guard): refuse a second attempt already in flight, and mark in-flight SYNCHRONOUSLY
        // (before the async githubStart) so a double-click during the request can't launch two flows / collide loopbacks.
        if (current.github.isBusy) return
        clearPendingOidc() // Assist-C1/CYP-578: stop any prior attempt's loopback server so this one re-binds cleanly
        _state.value = current.copy(github = GithubUiState.Starting)
        runScope.launch {
            // CYP-576 P1: generate the state nonce BEFORE githubStart (it goes into the flow's return_to) and hold it.
            val state = if (nativeOidcLoopback) newOidcState() else null
            val r = runCatching { repository.githubStart(state) }
                .getOrElse { e -> if (e is CancellationException) throw e; GithubStart.Error }
            val github = when (r) {
                // CYP-474 §4: Desktop-native → the loopback handoff (system-browser + localhost return); web → redirect.
                is GithubStart.Redirect ->
                    if (nativeOidcLoopback) {
                        pendingExchangeInitCode = r.initCode // CYP-576: hold the init half for the token-exchange
                        pendingState = state
                        armHandoffTimeout() // CYP-576 P1: break the hang if the loopback never returns
                        GithubUiState.BrowserHandoff(r.url)
                    } else {
                        GithubUiState.Redirecting(r.url)
                    }
                // CYP-576 §4: S1b existing-email collision → login-first is a DISTINCT state (route to email), NOT a
                // generic failure (the regression); a real start failure → Error.
                GithubStart.LoginRequired -> GithubUiState.LoginRequired
                GithubStart.Error -> GithubUiState.Error
            }
            (_state.value as? AuthUiState.Unauthenticated)?.let { _state.value = it.copy(github = github) }
        }
    }

    /** CYP-576 P1 (BUG-A/CYP-578) — while awaiting the loopback return, arm a watchdog: if the state is STILL a
     *  BrowserHandoff after [handoffTimeoutMs] (bind failure / abandoned tab / any hiccup), leave the hang for a
     *  retry-able Error. The button re-enables (Error is not a busy state), so the operator can retry or use email. */
    private fun armHandoffTimeout() {
        handoffTimeoutJob?.cancel()
        handoffTimeoutJob = runScope.launch {
            delay(handoffTimeoutMs)
            (_state.value as? AuthUiState.Unauthenticated)?.let { st ->
                val gh = st.github
                if (gh is GithubUiState.BrowserHandoff) {
                    // §3: advisory (INFO, NOT error-red) + retry; keep the url visible. Assist-C1/CYP-578: stop the
                    // loopback server (clearPendingOidc) so port 47472 is free and a retry re-binds cleanly.
                    _state.value = st.copy(github = GithubUiState.TimedOut(gh.url))
                    clearPendingOidc()
                }
            }
        }
    }

    /**
     * The OIDC callback returned to the app → complete through the **normal gate** (no GitHub special-casing):
     * [session] maps the established session, so an OIDC identity's `verified=false` → [AuthedUnverified] (S2,
     * not one-click). Called by the platform's callback handler (deep-link / redirect return).
     */
    fun onGithubReturn(code: String? = null, state: String? = null, error: String? = null) {
        // CYP-576 P1 (Backend security-rec) — the loopback is unauthenticated, so a native return MUST echo THIS
        // attempt's `state` nonce. No pending attempt or a mismatch (incl. a bind-failure signalled as (null,null),
        // or a spurious/forged callback) ⇒ reject → retry-able Error, NEVER an exchange on a foreign callback.
        if (nativeOidcLoopback) {
            val expected = pendingState
            if (expected == null || state != expected) {
                clearPendingOidc()
                _state.value = AuthUiState.Unauthenticated(github = GithubUiState.Error)
                return
            }
            // CYP-576 §4 error≠cancel: the OIDC `error` param distinguishes a deliberate user cancel from a real
            // failure. `access_denied` = the user stopped → neutral Cancelled (no alarm); any other error = Error.
            if (error != null) {
                clearPendingOidc()
                _state.value = AuthUiState.Unauthenticated(
                    github = if (error == "access_denied") GithubUiState.Cancelled else GithubUiState.Error,
                )
                return
            }
        }
        _state.value = AuthUiState.Unauthenticated(github = GithubUiState.Returning(native = nativeOidcLoopback))
        val init = pendingExchangeInitCode
        clearPendingOidc() // one-shot: consume the attempt (also cancels the handoff watchdog)
        runScope.launch {
            val s = runCatching {
                if (nativeOidcLoopback && init != null && code != null) {
                    // CYP-576 desktop-native: redeem the init half (from startGithub) + the loopback return_to_code
                    // for a native session_token, then resolve the session. The credential is now in the app's own
                    // plumbing (not a browser cookie) → the normal verified-gate applies.
                    repository.githubTokenExchange(init, code)
                } else {
                    // Web redirect path: the browser set the same-origin session cookie → read it back as before.
                    repository.session()
                }
            }.getOrElse { e -> if (e is CancellationException) throw e; SessionState.None }
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
