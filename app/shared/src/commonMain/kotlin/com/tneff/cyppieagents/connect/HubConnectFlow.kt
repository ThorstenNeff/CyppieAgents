package com.tneff.cyppieagents.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.auth.AuthFormCard
import com.tneff.cyppieagents.auth.AuthTitle
import com.tneff.cyppieagents.eventlog.severityColor
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_settings_apikey_input
import kmpcyppieagents.app.shared.generated.resources.a11y_settings_apikey_reveal
import kmpcyppieagents.app.shared.generated.resources.hubconnect_creds_invalid
import kmpcyppieagents.app.shared.generated.resources.hubconnect_creds_keystore
import kmpcyppieagents.app.shared.generated.resources.hubconnect_creds_masked
import kmpcyppieagents.app.shared.generated.resources.hubconnect_creds_placeholder
import kmpcyppieagents.app.shared.generated.resources.hubconnect_creds_privacy
import kmpcyppieagents.app.shared.generated.resources.hubconnect_creds_title
import kmpcyppieagents.app.shared.generated.resources.hubconnect_creds_unreachable
import kmpcyppieagents.app.shared.generated.resources.hubconnect_creds_validated
import kmpcyppieagents.app.shared.generated.resources.hubconnect_creds_validating
import kmpcyppieagents.app.shared.generated.resources.hubconnect_hubs_register
import kmpcyppieagents.app.shared.generated.resources.hubconnect_prepare_title
import kmpcyppieagents.app.shared.generated.resources.hubconnect_ready_enter
import kmpcyppieagents.app.shared.generated.resources.hubconnect_ready_title
import kmpcyppieagents.app.shared.generated.resources.hubconnect_register_error_offline
import kmpcyppieagents.app.shared.generated.resources.hubconnect_register_intro
import kmpcyppieagents.app.shared.generated.resources.hubconnect_register_name_label
import kmpcyppieagents.app.shared.generated.resources.hubconnect_hubs_error
import kmpcyppieagents.app.shared.generated.resources.load_retry
import kmpcyppieagents.app.shared.generated.resources.settings_save
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-419 (Epic CYP-395 S-L) — the hubConnect flow gate: one [HubConnectUiState] → a `when` over full-window
 * screens (the `AuthGate` idiom; no nav library). Seq A (first-start register) and Seq B (select + connect) share
 * this gate. Login (A1/B1) is the reused `AuthGate`, wrapped by the host **around** this composable — so this begins
 * once a session is verified. Built standalone against the stub `connect/` repos (S-J wires the live CP chain).
 *
 * Every honesty rail (§13) is either encoded in [HubConnectViewModel] (never guess the cause; presence ≠
 * connection; Q5 credential gate) or in the leaf renderers here (in-progress neutral not green; `validated` = INFO;
 * `unreachable` = WARN-amber ≠ `invalid` error-red; masked never plaintext).
 */
@Composable
fun HubConnectFlow(
    viewModel: HubConnectViewModel,
    onEnterWorkspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    // Auto-start on mount (the `AuthViewModel.init` idiom, but composition-scoped): load the hub list once.
    LaunchedEffect(Unit) { viewModel.start() }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val s = state) {
            HubConnectUiState.Preparing, HubConnectUiState.LoadingHubs -> PrepareView()
            HubConnectUiState.HubsUnreachable -> HubsUnreachableView(onRetry = viewModel::retryLoadHubs)
            is HubConnectUiState.Register -> RegisterView(s, viewModel)
            is HubConnectUiState.Credentials -> CredentialsView(s, viewModel)
            HubConnectUiState.Ready -> ReadyView(onEnterWorkspace)
            is HubConnectUiState.HubList -> HubListView(s.hubs, viewModel)
            is HubConnectUiState.ChoosingMode -> ModeView(s.hub, viewModel)
            is HubConnectUiState.Connecting -> ConnectingView(s.hub, s.progress, viewModel)
            is HubConnectUiState.RemoteConnecting -> RemoteConnectingView(s.hub, s.remote, viewModel, oobConfirm = s.oobConfirm)
        }
    }
}

// --- A0 — preparation / loading (system-side keygen; a plain, non-promising loading state) ---
@Composable
private fun PrepareView() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.testTag(HubConnectTags.PREPARE),
    ) {
        CircularProgressIndicator()
        Text(
            stringResource(Res.string.hubconnect_prepare_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- H5 — CP unreachable at hub-list load (honest error + retry, never a hang) ---
@Composable
private fun HubsUnreachableView(onRetry: () -> Unit) {
    HubCard {
        // H5: the CP must be reachable for the first sign-in / hub list — honest error, never a hang (HUBS_ERROR on the hint).
        TonedHint(stringResource(Res.string.hubconnect_hubs_error), HintTone.ERROR, HubConnectTags.HUBS_ERROR)
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.load_retry))
        }
    }
}

/**
 * A centered, maritime ~400dp content column — [AuthFormCard]'s look **without** a required tag. Wrapper cards
 * carry no contract tag; the frozen `hubConnect.*` tags sit on the meaningful inner elements (so a card wrapper
 * never collides with, e.g., the chooser's own `mode.local` node).
 */
@Composable
internal fun HubCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

// --- A2 — hub registration (editable name, hostname default; device-code automatic on desktop, Q7) ---
@Composable
private fun RegisterView(s: HubConnectUiState.Register, viewModel: HubConnectViewModel) {
    AuthFormCard(tag = HubConnectTags.ONBOARDING_STEPPER) {
        StepProgress(step = 1)
        AuthTitle(stringResource(Res.string.hubconnect_register_intro))
        OutlinedTextField(
            value = s.name,
            onValueChange = viewModel::onHubNameChange,
            enabled = s.phase != RegisterPhase.REGISTERING,
            singleLine = true,
            label = { Text(stringResource(Res.string.hubconnect_register_name_label)) },
            modifier = Modifier.fillMaxWidth().testTag(HubConnectTags.REGISTER_NAME),
        )
        if (s.phase == RegisterPhase.ERROR) {
            TonedHint(
                stringResource(Res.string.hubconnect_register_error_offline),
                HintTone.ERROR,
                HubConnectTags.REGISTER_ERROR,
            )
        }
        Button(
            onClick = viewModel::register,
            enabled = s.name.isNotBlank() && s.phase != RegisterPhase.REGISTERING,
            modifier = Modifier.fillMaxWidth().testTag(HubConnectTags.REGISTER_SUBMIT),
        ) {
            if (s.phase == RegisterPhase.REGISTERING) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
            }
            Text(stringResource(Res.string.hubconnect_hubs_register))
        }
    }
}

// --- A3 — Anthropic credentials (the ApiKeySection pattern; server-masked, tri-state validation) ---
@Composable
internal fun CredentialsView(s: HubConnectUiState.Credentials, viewModel: HubConnectViewModel) {
    var input by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    AuthFormCard(tag = HubConnectTags.ONBOARDING_STEPPER) {
        StepProgress(step = 2)
        AuthTitle(stringResource(Res.string.hubconnect_creds_title))

        // Read-only masked line — hinterlegt (***last4), server-masked; NEVER plaintext (H3).
        s.masked?.let { masked ->
            Text(
                stringResource(Res.string.hubconnect_creds_masked, masked),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().testTag(HubConnectTags.CREDS_MASKED),
            )
        }

        // Write-only field + text-label reveal (un-masks only the current input) — reuses the settings a11y keys.
        val inputA11y = stringResource(Res.string.a11y_settings_apikey_input)
        val revealA11y = stringResource(Res.string.a11y_settings_apikey_reveal)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                placeholder = { Text(stringResource(Res.string.hubconnect_creds_placeholder)) },
                modifier = Modifier.fillMaxWidth().testTag(HubConnectTags.CREDS_INPUT)
                    .semantics { contentDescription = inputA11y },
            )
        }
        TextButton(
            onClick = { reveal = !reveal },
            modifier = Modifier.testTag(HubConnectTags.CREDS_REVEAL).semantics { contentDescription = revealA11y },
        ) { Text(revealA11y, style = MaterialTheme.typography.labelMedium) }

        // Zero-Knowledge micro-copy (H4/Q8) — Credentials stay local; keystore line (H6/Q8, restrained).
        Text(
            stringResource(Res.string.hubconnect_creds_privacy),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(Res.string.hubconnect_creds_keystore),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = { viewModel.submitCredential(input) },
            enabled = input.isNotBlank() && s.phase != CredentialPhase.VALIDATING,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(Res.string.settings_save)) }

        // The tri-state validation truth — validated=INFO (never green), invalid=ERROR, unreachable=WARN-amber (≠ error).
        when (s.phase) {
            CredentialPhase.VALIDATING -> Text(
                stringResource(Res.string.hubconnect_creds_validating),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(HubConnectTags.CREDS_VALIDATING),
            )
            CredentialPhase.VALIDATED -> TonedHint(
                stringResource(Res.string.hubconnect_creds_validated), HintTone.INFO, HubConnectTags.CREDS_VALIDATED,
            )
            CredentialPhase.INVALID -> TonedHint(
                stringResource(Res.string.hubconnect_creds_invalid), HintTone.ERROR, HubConnectTags.CREDS_INVALID,
            )
            CredentialPhase.UNREACHABLE -> WarnLine(
                stringResource(Res.string.hubconnect_creds_unreachable), HubConnectTags.CREDS_UNREACHABLE,
            )
            CredentialPhase.ENTERING -> Unit
        }

        // Q5 gate: proceed to A4 when hinterlegt and not INVALID (unreachable may proceed with WARN).
        Button(
            onClick = { viewModel.continueToReady() },
            enabled = s.masked != null && s.phase != CredentialPhase.INVALID &&
                s.phase != CredentialPhase.ENTERING && s.phase != CredentialPhase.VALIDATING,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(Res.string.hubconnect_ready_enter)) }
    }
}

// --- A4 — ready ---
@Composable
private fun ReadyView(onEnterWorkspace: () -> Unit) {
    AuthFormCard(tag = HubConnectTags.ONBOARDING_STEPPER) {
        StepProgress(step = 3)
        AuthTitle(stringResource(Res.string.hubconnect_ready_title))
        Button(
            onClick = onEnterWorkspace,
            modifier = Modifier.fillMaxWidth().testTag(HubConnectTags.READY_TO_WORKSPACE),
        ) { Text(stringResource(Res.string.hubconnect_ready_enter)) }
    }
}

/** A WARN-amber hint line (the §7 `unreachable` tone TonedHint lacks) — inherits the house `severityColor(WARN)`. */
@Composable
internal fun WarnLine(text: String, tag: String) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // A distinct non-error glyph (WARN), the message carrying the meaning (WCAG 1.4.1 — colour not sole signal).
        Text("!", color = severityColor(Severity.WARN), style = MaterialTheme.typography.bodySmall)
        Text(text, color = severityColor(Severity.WARN), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Minimal onboarding step-progress anchor (spec §4 "Schritt 2 von 4"). The frozen keys carry no step-progress
 * copy key, so this is a locale-neutral "n / 4" — the tag is what the contract pins; the exact glyphing is UI.
 */
@Composable
private fun StepProgress(step: Int, of: Int = 4) {
    Text(
        "$step / $of",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
