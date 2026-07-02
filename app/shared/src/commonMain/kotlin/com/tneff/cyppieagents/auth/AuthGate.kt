package com.tneff.cyppieagents.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_auth_password
import kmpcyppieagents.app.shared.generated.resources.a11y_auth_password_confirm
import kmpcyppieagents.app.shared.generated.resources.auth_forgot_body
import kmpcyppieagents.app.shared.generated.resources.auth_forgot_sent
import kmpcyppieagents.app.shared.generated.resources.auth_forgot_title
import kmpcyppieagents.app.shared.generated.resources.auth_github_button
import kmpcyppieagents.app.shared.generated.resources.auth_github_error
import kmpcyppieagents.app.shared.generated.resources.auth_github_redirect
import kmpcyppieagents.app.shared.generated.resources.auth_github_returning
import kmpcyppieagents.app.shared.generated.resources.auth_link_forgot
import kmpcyppieagents.app.shared.generated.resources.auth_or_divider
import kmpcyppieagents.app.shared.generated.resources.auth_link_to_login
import kmpcyppieagents.app.shared.generated.resources.auth_link_to_register
import kmpcyppieagents.app.shared.generated.resources.auth_loading
import kmpcyppieagents.app.shared.generated.resources.auth_login_error_generic
import kmpcyppieagents.app.shared.generated.resources.auth_login_title
import kmpcyppieagents.app.shared.generated.resources.auth_logout
import kmpcyppieagents.app.shared.generated.resources.auth_password_confirm_label
import kmpcyppieagents.app.shared.generated.resources.auth_password_label
import kmpcyppieagents.app.shared.generated.resources.auth_register_email_invalid
import kmpcyppieagents.app.shared.generated.resources.auth_register_error_generic
import kmpcyppieagents.app.shared.generated.resources.auth_register_password_rule
import kmpcyppieagents.app.shared.generated.resources.auth_register_pw_mismatch
import kmpcyppieagents.app.shared.generated.resources.auth_register_title
import kmpcyppieagents.app.shared.generated.resources.auth_reset_new_label
import kmpcyppieagents.app.shared.generated.resources.auth_reset_success
import kmpcyppieagents.app.shared.generated.resources.auth_reset_title
import kmpcyppieagents.app.shared.generated.resources.auth_reset_token_invalid
import kmpcyppieagents.app.shared.generated.resources.auth_submit_forgot
import kmpcyppieagents.app.shared.generated.resources.auth_submit_login
import kmpcyppieagents.app.shared.generated.resources.auth_submit_register
import kmpcyppieagents.app.shared.generated.resources.auth_submit_reset
import kmpcyppieagents.app.shared.generated.resources.auth_submitting
import kmpcyppieagents.app.shared.generated.resources.auth_verify_continue
import kmpcyppieagents.app.shared.generated.resources.auth_verify_gate_hint
import kmpcyppieagents.app.shared.generated.resources.auth_verify_pending_body
import kmpcyppieagents.app.shared.generated.resources.auth_verify_resend
import kmpcyppieagents.app.shared.generated.resources.auth_verify_resend_done
import kmpcyppieagents.app.shared.generated.resources.auth_verify_success_body
import kmpcyppieagents.app.shared.generated.resources.auth_verify_success_title
import kmpcyppieagents.app.shared.generated.resources.auth_verify_title
import kmpcyppieagents.app.shared.generated.resources.auth_verify_token_invalid
import org.jetbrains.compose.resources.stringResource

/**
 * The login gate (auth-spec §2): reads **one** [AuthUiState] and renders either an auth screen **or** —
 * at [AuthUiState.Verified] — the existing desktop via [content]. It **wraps** the desktop and touches
 * no desktop composable (auth-spec §8.1): `App()` swaps `AgentShell(...)` for `AuthGate { AgentShell(...) }`.
 *
 * The `auth.gate` node is the always-present root; the desktop mounts only at `Verified`. The hard
 * verify-gate (§-Ask 2a) is structural here — an [AuthUiState.AuthedUnverified] simply never reaches the
 * `content()` branch, so an unverified user cannot see the desktop.
 */
@Composable
fun AuthGate(
    viewModel: AuthViewModel,
    modifier: Modifier = Modifier,
    /** Platform hook to open the GitHub OIDC redirect URL externally (browser/custom-tab) — §6 keeps the
     *  OAuth dance out of commonMain. Default no-op; the platform entry points wire the real open. */
    onOpenExternalUrl: (String) -> Unit = {},
    /** The verified desktop — receives the session's [UserTier] (CYP-186) so it can gate operator surfaces. */
    content: @Composable (UserTier) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    Box(modifier = modifier.testTag(AuthTags.GATE)) {
        when (val s = state) {
            AuthUiState.Loading -> LoadingScreen()
            is AuthUiState.Unauthenticated -> LoginScreen(s, viewModel, onOpenExternalUrl)
            is AuthUiState.Register -> RegisterScreen(s, viewModel)
            is AuthUiState.ForgotRequest -> ForgotScreen(s, viewModel)
            is AuthUiState.ResetSetNew -> ResetScreen(s, viewModel)
            is AuthUiState.AuthedUnverified -> VerifyPendingScreen(s, viewModel)
            is AuthUiState.VerifySuccess -> VerifySuccessScreen(s, viewModel)
            is AuthUiState.Verified -> content(s.tier)
        }
    }
}

// --- Bootstrap / Loading (§5.3) ---

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(Res.string.auth_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(AuthTags.LOADING),
        )
    }
}

// --- Login (§3.1) ---

@Composable
private fun LoginScreen(
    state: AuthUiState.Unauthenticated,
    vm: AuthViewModel,
    onOpenExternalUrl: (String) -> Unit = {},
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val submitting = state.phase is Phase.Submitting
    val canSubmit = !submitting && email.isNotBlank() && password.isNotBlank()
    val submit = { if (canSubmit) vm.login(email.trim(), password) }

    AuthFormCard(AuthTags.LOGIN_FORM) {
        AuthTitle(stringResource(Res.string.auth_login_title))
        AuthEmailField(
            value = email, onValueChange = { email = it }, enabled = !submitting, isError = false,
            tag = AuthTags.LOGIN_EMAIL, imeAction = ImeAction.Next,
        )
        AuthPasswordField(
            value = password, onValueChange = { password = it }, enabled = !submitting, isError = false,
            label = Res.string.auth_password_label, contentDesc = Res.string.a11y_auth_password,
            fieldTag = AuthTags.LOGIN_PASSWORD, revealTag = AuthTags.LOGIN_PASSWORD_REVEAL,
            imeAction = ImeAction.Done, keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        Button(
            onClick = submit, enabled = canSubmit,
            modifier = Modifier.fillMaxWidth().testTag(AuthTags.LOGIN_SUBMIT),
        ) { Text(stringResource(if (submitting) Res.string.auth_submitting else Res.string.auth_submit_login)) }

        when (val p = state.phase) {
            is Phase.Error -> AnnouncingHint(
                stringResource(Res.string.auth_login_error_generic), HintTone.ERROR,
                AuthTags.LOGIN_ERROR, LiveRegionMode.Assertive,
            )
            is Phase.RateLimited -> AnnouncingHint(
                rateLimitedText(p.retryAfter), HintTone.EFFECT_DEFERRED,
                AuthTags.LOGIN_RATE_LIMITED, LiveRegionMode.Assertive,
            )
            else -> {}
        }

        TextButton(
            onClick = vm::goToRegister, enabled = !submitting,
            modifier = Modifier.testTag(AuthTags.LOGIN_TO_REGISTER),
        ) { Text(stringResource(Res.string.auth_link_to_register)) }
        TextButton(
            onClick = vm::goToForgot, enabled = !submitting,
            modifier = Modifier.testTag(AuthTags.LOGIN_TO_FORGOT),
        ) { Text(stringResource(Res.string.auth_link_forgot)) }

        // --- P4 (auth-spec §6): "or" divider + GitHub OIDC (CYP-185) ---
        val github = state.github
        val githubBusy = github is GithubUiState.Redirecting || github is GithubUiState.Returning
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text(
            text = stringResource(Res.string.auth_or_divider),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = vm::startGithub, enabled = !submitting && !githubBusy,
            modifier = Modifier.fillMaxWidth().testTag(AuthTags.LOGIN_GITHUB),
        ) { Text(stringResource(Res.string.auth_github_button)) }

        when (github) {
            is GithubUiState.Redirecting -> {
                // "Continuing to GitHub…" — controls disabled; the platform opens the external OAuth URL.
                Text(
                    text = stringResource(Res.string.auth_github_redirect),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().testTag(AuthTags.GITHUB_REDIRECTING),
                )
                LaunchedEffect(github.url) { onOpenExternalUrl(github.url) }
            }
            GithubUiState.Returning -> Text(
                text = stringResource(Res.string.auth_github_returning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().testTag(AuthTags.GITHUB_RETURNING),
            )
            GithubUiState.Error -> AnnouncingHint(
                stringResource(Res.string.auth_github_error), HintTone.ERROR,
                AuthTags.GITHUB_ERROR, LiveRegionMode.Assertive,
            )
            GithubUiState.Idle -> {}
        }
    }
}

// --- Register (§3.2) ---

@Composable
private fun RegisterScreen(state: AuthUiState.Register, vm: AuthViewModel) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val submitting = state.phase is Phase.Submitting
    val emailInvalid = email.isNotBlank() && !looksLikeEmail(email)
    val mismatch = confirm.isNotBlank() && password != confirm
    val canSubmit = !submitting && email.isNotBlank() && password.isNotBlank() &&
        confirm.isNotBlank() && !emailInvalid && !mismatch
    val submit = { if (canSubmit) vm.register(email.trim(), password) }

    AuthFormCard(AuthTags.REGISTER_FORM) {
        AuthTitle(stringResource(Res.string.auth_register_title))
        AuthEmailField(
            value = email, onValueChange = { email = it }, enabled = !submitting, isError = emailInvalid,
            tag = AuthTags.REGISTER_EMAIL, imeAction = ImeAction.Next,
        )
        AuthPasswordField(
            value = password, onValueChange = { password = it }, enabled = !submitting, isError = mismatch,
            label = Res.string.auth_password_label, contentDesc = Res.string.a11y_auth_password,
            fieldTag = AuthTags.REGISTER_PASSWORD, revealTag = AuthTags.REGISTER_PASSWORD_REVEAL,
            imeAction = ImeAction.Next,
        )
        AuthPasswordField(
            value = confirm, onValueChange = { confirm = it }, enabled = !submitting, isError = mismatch,
            label = Res.string.auth_password_confirm_label, contentDesc = Res.string.a11y_auth_password_confirm,
            fieldTag = AuthTags.REGISTER_PASSWORD_CONFIRM, revealTag = null,
            imeAction = ImeAction.Done, keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        // Honest minimum requirement (no fake strength meter).
        AuthInfoNote(stringResource(Res.string.auth_register_password_rule))
        Button(
            onClick = submit, enabled = canSubmit,
            modifier = Modifier.fillMaxWidth().testTag(AuthTags.REGISTER_SUBMIT),
        ) { Text(stringResource(if (submitting) Res.string.auth_submitting else Res.string.auth_submit_register)) }

        // Error node (auth.register.error): client validation first, then the generic server error.
        // A 429 has its own dedicated node (auth.register.rateLimited, amber) — honest throttling is
        // test-anchored the same on all four throttleable actions (spec refine 1982acb).
        when {
            emailInvalid -> AnnouncingHint(
                stringResource(Res.string.auth_register_email_invalid), HintTone.ERROR,
                AuthTags.REGISTER_ERROR, LiveRegionMode.Assertive,
            )
            mismatch -> AnnouncingHint(
                stringResource(Res.string.auth_register_pw_mismatch), HintTone.ERROR,
                AuthTags.REGISTER_ERROR, LiveRegionMode.Assertive,
            )
            state.phase is Phase.Error -> AnnouncingHint(
                stringResource(Res.string.auth_register_error_generic), HintTone.ERROR,
                AuthTags.REGISTER_ERROR, LiveRegionMode.Assertive,
            )
            state.phase is Phase.RateLimited -> AnnouncingHint(
                rateLimitedText(state.phase.retryAfter), HintTone.EFFECT_DEFERRED,
                AuthTags.REGISTER_RATE_LIMITED, LiveRegionMode.Assertive,
            )
        }

        TextButton(
            onClick = vm::goToLogin, enabled = !submitting,
            modifier = Modifier.testTag(AuthTags.REGISTER_TO_LOGIN),
        ) { Text(stringResource(Res.string.auth_link_to_login)) }
    }
}

// --- Email verify — Pending / hard gate (§4.1) ---

@Composable
private fun VerifyPendingScreen(state: AuthUiState.AuthedUnverified, vm: AuthViewModel) {
    val submitting = state.phase is Phase.Submitting
    AuthFormCard(AuthTags.VERIFY_PENDING) {
        AuthTitle(stringResource(Res.string.auth_verify_title))
        // Neutral body echoing the email for orientation — never an existence proof (§-Ask 1).
        Text(
            text = stringResource(Res.string.auth_verify_pending_body, state.email),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().testTag(AuthTags.VERIFY_EMAIL),
        )
        // Neutral gate note — unverified is not a user error (GATED, not ERROR).
        TonedHint(stringResource(Res.string.auth_verify_gate_hint), HintTone.GATED, AuthTags.VERIFY_GATE_HINT)
        Button(
            onClick = vm::resendVerification, enabled = !submitting,
            modifier = Modifier.fillMaxWidth().testTag(AuthTags.VERIFY_RESEND),
        ) { Text(stringResource(if (submitting) Res.string.auth_submitting else Res.string.auth_verify_resend)) }

        when (val r = state.resendResult) {
            ResendResult.Accepted -> AnnouncingHint(
                stringResource(Res.string.auth_verify_resend_done), HintTone.INFO,
                AuthTags.VERIFY_RESEND_RESULT, LiveRegionMode.Polite,
            )
            is ResendResult.RateLimited -> AnnouncingHint(
                rateLimitedText(r.retryAfter), HintTone.EFFECT_DEFERRED,
                AuthTags.VERIFY_RESEND_RESULT, LiveRegionMode.Polite,
            )
            null -> {}
        }

        // The exit — never locked in (§-Ask 2a).
        TextButton(
            onClick = vm::logout, enabled = !submitting,
            modifier = Modifier.testTag(AuthTags.VERIFY_LOGOUT),
        ) { Text(stringResource(Res.string.auth_logout)) }
    }
}

// --- Email verify — Success / token-invalid (§4.2) ---

@Composable
private fun VerifySuccessScreen(state: AuthUiState.VerifySuccess, vm: AuthViewModel) {
    AuthFormCard(AuthTags.VERIFY_SUCCESS) {
        AuthTitle(stringResource(Res.string.auth_verify_success_title))
        if (state.tokenInvalid) {
            AnnouncingHint(
                stringResource(Res.string.auth_verify_token_invalid), HintTone.ERROR,
                AuthTags.VERIFY_ERROR, LiveRegionMode.Assertive,
            )
        } else {
            // No success-green in the DS (Anti-Hype) — INFO for "confirmed" (auth-spec §10).
            AuthInfoNote(stringResource(Res.string.auth_verify_success_body))
        }
        Button(
            onClick = vm::continueAfterVerify,
            modifier = Modifier.fillMaxWidth().testTag(AuthTags.VERIFY_CONTINUE),
        ) { Text(stringResource(Res.string.auth_verify_continue)) }
    }
}

// --- Forgot password — request (§5.1) ---

@Composable
private fun ForgotScreen(state: AuthUiState.ForgotRequest, vm: AuthViewModel) {
    var email by remember { mutableStateOf("") }
    val submitting = state.phase is Phase.Submitting
    val canSubmit = !submitting && email.isNotBlank()
    val submit = { if (canSubmit) vm.requestReset(email.trim()) }

    AuthFormCard(AuthTags.FORGOT_FORM) {
        AuthTitle(stringResource(Res.string.auth_forgot_title))
        Text(
            text = stringResource(Res.string.auth_forgot_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
        AuthEmailField(
            value = email, onValueChange = { email = it }, enabled = !submitting, isError = false,
            tag = AuthTags.FORGOT_EMAIL, imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        Button(
            onClick = submit, enabled = canSubmit,
            modifier = Modifier.fillMaxWidth().testTag(AuthTags.FORGOT_SUBMIT),
        ) { Text(stringResource(if (submitting) Res.string.auth_submitting else Res.string.auth_submit_forgot)) }

        // Neutral "if an account exists…" — never enumerating; 429 honest, never a fake "sent".
        if (state.sentTo != null) {
            AnnouncingHint(
                stringResource(Res.string.auth_forgot_sent, state.sentTo), HintTone.INFO,
                AuthTags.FORGOT_SENT, LiveRegionMode.Polite,
            )
        }
        (state.phase as? Phase.RateLimited)?.let { p ->
            AnnouncingHint(
                rateLimitedText(p.retryAfter), HintTone.EFFECT_DEFERRED,
                AuthTags.FORGOT_RATE_LIMITED, LiveRegionMode.Assertive,
            )
        }

        TextButton(
            onClick = vm::goToLogin, enabled = !submitting,
            modifier = Modifier.testTag(AuthTags.FORGOT_TO_LOGIN),
        ) { Text(stringResource(Res.string.auth_link_to_login)) }
    }
}

// --- Reset password — set-new / terminal states (§5.2) ---

@Composable
private fun ResetScreen(state: AuthUiState.ResetSetNew, vm: AuthViewModel) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val submitting = state.phase is Phase.Submitting
    val mismatch = confirm.isNotBlank() && password != confirm
    val canSubmit = !submitting && password.isNotBlank() && confirm.isNotBlank() && !mismatch
    val submit = { if (canSubmit) vm.setNewPassword(password) }

    AuthFormCard(AuthTags.RESET_FORM) {
        AuthTitle(stringResource(Res.string.auth_reset_title))
        when {
            // Success terminal: shown here (reset-scoped tag) with a path back to sign-in.
            state.done -> {
                AnnouncingHint(
                    stringResource(Res.string.auth_reset_success), HintTone.INFO,
                    AuthTags.RESET_SUCCESS, LiveRegionMode.Polite,
                )
                TextButton(onClick = vm::goToLogin) { Text(stringResource(Res.string.auth_link_to_login)) }
            }
            // Token invalid/expired: honest error + a path to request a fresh link.
            state.tokenInvalid -> {
                AnnouncingHint(
                    stringResource(Res.string.auth_reset_token_invalid), HintTone.ERROR,
                    AuthTags.RESET_TOKEN_INVALID, LiveRegionMode.Assertive,
                )
                TextButton(onClick = vm::goToForgot) { Text(stringResource(Res.string.auth_link_forgot)) }
            }
            else -> {
                AuthPasswordField(
                    value = password, onValueChange = { password = it }, enabled = !submitting, isError = mismatch,
                    label = Res.string.auth_reset_new_label, contentDesc = Res.string.a11y_auth_password,
                    fieldTag = AuthTags.RESET_PASSWORD, revealTag = AuthTags.RESET_PASSWORD_REVEAL,
                    imeAction = ImeAction.Next,
                )
                AuthPasswordField(
                    value = confirm, onValueChange = { confirm = it }, enabled = !submitting, isError = mismatch,
                    label = Res.string.auth_password_confirm_label, contentDesc = Res.string.a11y_auth_password_confirm,
                    fieldTag = AuthTags.RESET_PASSWORD_CONFIRM, revealTag = null,
                    imeAction = ImeAction.Done, keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                AuthInfoNote(stringResource(Res.string.auth_register_password_rule))
                Button(
                    onClick = submit, enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth().testTag(AuthTags.RESET_SUBMIT),
                ) { Text(stringResource(if (submitting) Res.string.auth_submitting else Res.string.auth_submit_reset)) }

                (state.phase as? Phase.RateLimited)?.let { p ->
                    AnnouncingHint(
                        rateLimitedText(p.retryAfter), HintTone.EFFECT_DEFERRED,
                        AuthTags.RESET_RATE_LIMITED, LiveRegionMode.Assertive,
                    )
                }
            }
        }
    }
}

/**
 * A deliberately loose "obviously invalid" e-mail check (auth-spec §3.2 "offensichtlich ungültig") — it
 * gates the client-side hint only; the server is the real validator. Requires `<local>@<domain>.<tld>`
 * with non-empty parts.
 */
private fun looksLikeEmail(s: String): Boolean {
    val at = s.indexOf('@')
    if (at <= 0) return false
    val dot = s.indexOf('.', at + 1)
    return dot in (at + 2) until (s.length - 1)
}
