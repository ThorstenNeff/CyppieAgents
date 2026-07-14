package com.tneff.cyppieagents.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.net.hub.operator.vault.EnrollOutcome

/**
 * CYP-542 / B1 — the operator-UV step renderers for [HubConnectUiState.PassphrasePrompt] (auth-time) and
 * [HubConnectUiState.SetPassphrase] (AC-1 enroll). **Minimal-but-wired placeholders**: the full CYP-460
 * `OperatorAuthDialog` U1 delta — a secure `CharArray`-backed passphrase field-pair, the live strength meter
 * ([com.tneff.cyppieagents.net.hub.operator.ui.enrollStrengthUi]), and the diceware one-click reveal (A0: reuse the
 * hardened `RecoveryCodesReveal` reveal/copy/zeroize path) with the DE/EN strings — lands in the (a) commit which the
 * PO arbiters on a clean render env (render-oracle `b03d4b7c`). These keep the flow navigable + compile the exhaustive
 * `when`; **no plaintext passphrase is stored** here (the diceware path is `CharArray`-only) so nothing insecure ships
 * ahead of the hardened (a) field. The VM logic behind these (AC-1/2/4) is fully exercised by the (b) VM tests.
 */
@Composable
internal fun PassphrasePromptStep(viewModel: HubConnectViewModel) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.testTag(REMOTE_PASSPHRASE_PROMPT_STEP),
    ) {
        // TODO(a): CYP-460 secure passphrase field + submit → viewModel.submitPassphrase(field.toCharArray()); strings (d).
        Text("App-Passphrase eingeben") // TODO(d): stringResource
        OutlinedButton(
            onClick = viewModel::cancelPassphrase,
            modifier = Modifier.fillMaxWidth().testTag(REMOTE_PASSPHRASE_PROMPT_CANCEL),
        ) { Text("Abbrechen") } // TODO(d): stringResource — cancel ⇒ Denied(CANCELLED) ⇒ LOST (AC-4 abort path)
    }
}

@Composable
internal fun SetPassphraseStep(state: HubConnectUiState.SetPassphrase, viewModel: HubConnectViewModel) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.testTag(REMOTE_SET_PASSPHRASE_STEP),
    ) {
        Text("Dieses Gerät einrichten (App-Passphrase setzen)") // TODO(d): stringResource
        // TODO(a): the type-your-own secure field + live strength meter (enrollStrengthUi) per render-oracle b03d4b7c.
        when (state.outcome) {
            EnrollOutcome.TooWeak -> Text("Zu schwach") // TODO(a/d): WARN-amber tone + enroll_error_too_weak
            EnrollOutcome.Blocklisted -> Text("Unsicher") // TODO(a/d): error tone + enroll_error_blocklisted
            EnrollOutcome.MigrationFailed -> Text("Migration fehlgeschlagen") // TODO(d): retry copy
            EnrollOutcome.AlreadyEnrolled -> Text("Bereits eingerichtet") // TODO(d)
            EnrollOutcome.Enrolled, null -> Unit
        }
        val enrolling = state.phase == EnrollPhase.ENROLLING
        // The diceware one-click default — CharArray-only, enrolled directly (no plaintext String stored). AC-2 preArm
        // + auto-reconnect happens in the VM on Enrolled.
        Button(
            onClick = { if (!enrolling) viewModel.suggestPassphrase()?.let(viewModel::setEnrollPassphrase) },
            enabled = !enrolling,
            modifier = Modifier.fillMaxWidth().testTag(REMOTE_SET_PASSPHRASE_SUGGEST),
        ) { Text(if (enrolling) "Wird eingerichtet…" else "Sichere Passphrase vorschlagen") } // TODO(d)
        OutlinedButton(
            onClick = viewModel::cancelEnroll,
            enabled = !enrolling,
            modifier = Modifier.fillMaxWidth().testTag(REMOTE_SET_PASSPHRASE_CANCEL),
        ) { Text("Abbrechen") } // TODO(d)
    }
}

// Placeholder step tags — the (a) commit reconciles these with OperatorAuthTags (remote.authStep.*) per the spec.
internal const val REMOTE_PASSPHRASE_PROMPT_STEP = "remote.authStep.passphrasePrompt"
internal const val REMOTE_PASSPHRASE_PROMPT_CANCEL = "remote.authStep.passphrasePrompt.cancel"
internal const val REMOTE_SET_PASSPHRASE_STEP = "remote.authStep.setPassphrase"
internal const val REMOTE_SET_PASSPHRASE_SUGGEST = "remote.authStep.setPassphrase.suggest"
internal const val REMOTE_SET_PASSPHRASE_CANCEL = "remote.authStep.setPassphrase.cancel"
