package com.tneff.cyppieagents.net.hub.operator.ui

import androidx.compose.runtime.Composable
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.remote_pop_biometric_failed
import kmpcyppieagents.app.shared.generated.resources.remote_pop_enroll_title
import kmpcyppieagents.app.shared.generated.resources.remote_pop_keystore_unavailable
import kmpcyppieagents.app.shared.generated.resources.remote_pop_locked
import kmpcyppieagents.app.shared.generated.resources.remote_pop_rejected
import kmpcyppieagents.app.shared.generated.resources.remote_pop_wrong_pin
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-460 — the taxonomy → frozen-copy mapping (`desktop-remote-operator-keys.md`). Local errors get neutral /
 * error-tone copy; [OperatorAuthError.HubRejected] gets the terminal "sign in again" copy. No content-bearing
 * secret ever in copy — `%1$s` is only a count (`WrongPin`) or a wait (`LockedOut`), never a PIN/fingerprint value.
 */
@Composable
fun operatorAuthErrorCopy(error: OperatorAuthError): String = when (error) {
    is OperatorAuthError.WrongPin -> stringResource(Res.string.remote_pop_wrong_pin, error.attemptsLeft.toString())
    is OperatorAuthError.LockedOut -> stringResource(Res.string.remote_pop_locked, error.retryAfter)
    OperatorAuthError.BiometricFailed -> stringResource(Res.string.remote_pop_biometric_failed)
    OperatorAuthError.KeystoreUnavailable -> stringResource(Res.string.remote_pop_keystore_unavailable)
    OperatorAuthError.NeedsEnroll -> stringResource(Res.string.remote_pop_enroll_title)
    OperatorAuthError.Cancelled -> "" // cancel just dismisses the prompt — no error banner
    OperatorAuthError.HubRejected -> stringResource(Res.string.remote_pop_rejected)
}
