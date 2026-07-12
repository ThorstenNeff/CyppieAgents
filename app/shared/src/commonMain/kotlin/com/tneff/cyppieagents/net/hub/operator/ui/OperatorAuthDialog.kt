package com.tneff.cyppieagents.net.hub.operator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.auth.AuthPasswordField
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.remote_pop_biometric_title
import kmpcyppieagents.app.shared.generated.resources.remote_pop_enroll_pin
import kmpcyppieagents.app.shared.generated.resources.remote_pop_enroll_session_only
import kmpcyppieagents.app.shared.generated.resources.remote_pop_enroll_title
import kmpcyppieagents.app.shared.generated.resources.remote_pop_path_hint
import kmpcyppieagents.app.shared.generated.resources.remote_pop_pin_body
import kmpcyppieagents.app.shared.generated.resources.remote_pop_pin_title
import org.jetbrains.compose.resources.stringResource

/** The dialog step (transport-independent — no tunnel here). */
sealed interface OperatorAuthStep {
    /** Raw path (cross-platform incl. Linux): app-PIN entry. */
    data class Pin(val pathName: String) : OperatorAuthStep
    /** Fido2 path (progressive): a platform-biometric prompt. */
    data class Biometric(val pathName: String) : OperatorAuthStep
    /** First setup — carries the Q5 session-only disclosure while DEVICE_SECURE is named-not-built. */
    data class Enroll(val sessionOnly: Boolean) : OperatorAuthStep
}

/**
 * CYP-460 — the Desktop-Native operator-auth dialog **content** (frozen `desktop-remote-operator-*`; UIUX §-QA
 * follows this build). Transport-independent: renders the step + error taxonomy and collects the PIN; submit rides
 * the field's IME-Done, and the enclosing dialog owns the action-button chrome (no button-label keys are frozen).
 *
 * Honesty rails: **errorContainer ONLY for the terminal hub reject** ([OperatorAuthError.isTerminal]); local
 * failures render **error-tone (not errorContainer) and stay retryable** (the PIN field remains enabled); a
 * biometric failure shows the **PIN fallback** (H1); enroll shows the **session-only disclosure** (Q5). No
 * tertiary/green as status; colour is never the sole signal (label + tag + a11y). H3 (no embedded webview) is the
 * login step's concern, not this content.
 */
@Composable
fun OperatorAuthDialog(
    step: OperatorAuthStep,
    error: OperatorAuthError?,
    pin: String,
    onPinChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val terminal = error?.isTerminal == true
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Path hint names the REAL path (PIN vs a biometric) — never biometric optics without biometrics (H1).
        if (step !is OperatorAuthStep.Enroll) {
            val pathName = when (step) {
                is OperatorAuthStep.Pin -> step.pathName
                is OperatorAuthStep.Biometric -> step.pathName
                is OperatorAuthStep.Enroll -> ""
            }
            Text(
                stringResource(Res.string.remote_pop_path_hint, pathName),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, // neutral — never a status colour
                modifier = Modifier.testTag(OperatorAuthTags.PATH_HINT),
            )
        }

        when (step) {
            is OperatorAuthStep.Pin -> {
                Text(stringResource(Res.string.remote_pop_pin_body), style = MaterialTheme.typography.bodyMedium)
                PinField(pin, onPinChange, enabled = !terminal, isError = error != null && !terminal, onSubmit)
            }
            is OperatorAuthStep.Biometric -> {
                Text(
                    stringResource(Res.string.remote_pop_biometric_title, step.pathName),
                    modifier = Modifier.testTag(OperatorAuthTags.BIOMETRIC_PROMPT),
                )
                // A biometric failure falls back to the PIN field (H1) — visible, never silent.
                if (error is OperatorAuthError.BiometricFailed) {
                    PinField(pin, onPinChange, enabled = true, isError = false, onSubmit)
                }
            }
            is OperatorAuthStep.Enroll -> {
                Text(stringResource(Res.string.remote_pop_enroll_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(Res.string.remote_pop_enroll_pin),
                    modifier = Modifier.testTag(OperatorAuthTags.ENROLL_PIN_SET),
                )
                if (step.sessionOnly) {
                    // Q5: honest "this session only" — no hardware/device-persistence promise while DEVICE_SECURE is named-not-built.
                    Text(
                        stringResource(Res.string.remote_pop_enroll_session_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(OperatorAuthTags.ENROLL),
                    )
                }
            }
        }

        // The H2 split: TERMINAL hub reject = errorContainer (no retry affordance); LOCAL = error-tone, retryable.
        val copy = error?.let { operatorAuthErrorCopy(it) }
        if (error != null && !copy.isNullOrEmpty()) {
            if (terminal) {
                Text(
                    copy,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.errorContainer).padding(12.dp)
                        .testTag(error.tag()),
                )
            } else {
                Text(
                    copy,
                    color = MaterialTheme.colorScheme.error, // error-tone, NOT the errorContainer surface
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(error.tag()),
                )
                if (error is OperatorAuthError.WrongPin) {
                    Text(
                        error.attemptsLeft.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(OperatorAuthTags.ATTEMPTS),
                    )
                }
            }
        }
    }
}

@Composable
private fun PinField(pin: String, onPinChange: (String) -> Unit, enabled: Boolean, isError: Boolean, onSubmit: () -> Unit) {
    // Reuse AuthPasswordField (masked, reveal via text-label — no a11y leak). Submit rides IME-Done.
    AuthPasswordField(
        value = pin,
        onValueChange = onPinChange,
        enabled = enabled,
        isError = isError,
        label = Res.string.remote_pop_pin_title,
        contentDesc = Res.string.remote_pop_pin_body,
        fieldTag = OperatorAuthTags.PIN_FIELD,
        revealTag = OperatorAuthTags.PIN_REVEAL,
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
    )
}
