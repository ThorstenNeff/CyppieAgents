package com.tneff.cyppieagents.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_settings_apikey_hide
import kmpcyppieagents.app.shared.generated.resources.a11y_settings_apikey_input
import kmpcyppieagents.app.shared.generated.resources.a11y_settings_apikey_reveal
import kmpcyppieagents.app.shared.generated.resources.a11y_settings_repo_branch
import kmpcyppieagents.app.shared.generated.resources.a11y_settings_repo_url
import kmpcyppieagents.app.shared.generated.resources.settings_apikey_effect_hint
import kmpcyppieagents.app.shared.generated.resources.settings_apikey_masked
import kmpcyppieagents.app.shared.generated.resources.settings_apikey_placeholder
import kmpcyppieagents.app.shared.generated.resources.settings_apikey_save_failed
import kmpcyppieagents.app.shared.generated.resources.settings_apikey_section
import kmpcyppieagents.app.shared.generated.resources.settings_apikey_unset
import kmpcyppieagents.app.shared.generated.resources.settings_operator_required
import kmpcyppieagents.app.shared.generated.resources.settings_repo_branch_label
import kmpcyppieagents.app.shared.generated.resources.settings_repo_effect_hint
import kmpcyppieagents.app.shared.generated.resources.settings_repo_section
import kmpcyppieagents.app.shared.generated.resources.settings_repo_status_unset
import kmpcyppieagents.app.shared.generated.resources.settings_repo_url_invalid
import kmpcyppieagents.app.shared.generated.resources.settings_repo_url_label
import kmpcyppieagents.app.shared.generated.resources.settings_save
import org.jetbrains.compose.resources.stringResource

/**
 * The Project-Settings window body (S15: CYP-84 repo-config + CYP-85 API-key) — one panel, two
 * sections (PROJECT-SETTINGS §6.5). Operator-gated/fail-closed: without an operator token the inputs
 * are disabled and a visible gate hint explains why (the gate stays observable, Muster CYP-73/ACL).
 *
 * Disclosure is honest and never colour-only (WCAG 1.4.1): every state is distinct **text** + tone +
 * a11y label. "Saved" is the amber effect hint, never success-green; the API key is shown only masked
 * (`***<last4>`); the new-key field is write-only with a reveal that un-masks **only the current input**.
 * Activation of a saved key is the existing CYP-73 per-agent restart — this panel renders no restart
 * control of its own (PROJECT-SETTINGS §5).
 */
@Composable
fun SettingsPanel(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag(SettingsTags.PANEL),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        RepoSection(state, viewModel)
        ApiKeySection(state, viewModel)
    }
}

@Composable
private fun RepoSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(SettingsTags.SECTION_REPO),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeading(stringResource(Res.string.settings_repo_section))

        // Honest "not configured → agents cannot start" (info/warning tone, not error-red); §3.2.
        if (!state.repoConfigured) {
            TonedHint(stringResource(Res.string.settings_repo_status_unset), HintTone.INFO, SettingsTags.REPO_STATUS)
        }

        val urlLabel = stringResource(Res.string.settings_repo_url_label)
        val urlA11y = stringResource(Res.string.a11y_settings_repo_url)
        OutlinedTextField(
            value = state.repoUrl,
            onValueChange = viewModel::onRepoUrlChange,
            enabled = state.editable,
            singleLine = true,
            label = { Text(urlLabel) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsTags.REPO_URL_INPUT)
                .semantics { contentDescription = urlA11y },
        )

        val branchLabel = stringResource(Res.string.settings_repo_branch_label)
        val branchA11y = stringResource(Res.string.a11y_settings_repo_branch)
        OutlinedTextField(
            value = state.repoBranch,
            onValueChange = viewModel::onRepoBranchChange,
            enabled = state.editable,
            singleLine = true,
            label = { Text(branchLabel) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsTags.REPO_BRANCH_INPUT)
                .semantics { contentDescription = branchA11y },
        )

        if (!state.editable) {
            TonedHint(stringResource(Res.string.settings_operator_required), HintTone.GATED, SettingsTags.REPO_GATE_HINT)
        }

        Button(
            onClick = viewModel::saveRepo,
            enabled = state.canSaveRepo,
            modifier = Modifier.testTag(SettingsTags.REPO_SAVE),
        ) {
            Text(stringResource(Res.string.settings_save))
        }

        state.repoError?.let { key ->
            TonedHint(errorText(key), HintTone.ERROR, SettingsTags.REPO_ERROR)
        }

        // Amber "saved ≠ active" — applies to new worktrees / next boot (§3.2, disclosure mandatory).
        if (state.repoEffectHint) {
            TonedHint(stringResource(Res.string.settings_repo_effect_hint), HintTone.EFFECT_DEFERRED, SettingsTags.REPO_EFFECT_HINT)
        }
    }
}

@Composable
private fun ApiKeySection(state: SettingsUiState, viewModel: SettingsViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(SettingsTags.SECTION_API_KEY),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeading(stringResource(Res.string.settings_apikey_section))

        // Read-only status: stored shows ONLY the server-masked ***last4 — never the clear key.
        val maskedText = if (state.apiKeySet && state.apiKeyMasked != null) {
            stringResource(Res.string.settings_apikey_masked, state.apiKeyMasked)
        } else {
            stringResource(Res.string.settings_apikey_unset)
        }
        Text(
            text = maskedText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().testTag(SettingsTags.API_KEY_MASKED),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val inputA11y = stringResource(Res.string.a11y_settings_apikey_input)
            OutlinedTextField(
                value = state.apiKeyInput,
                onValueChange = viewModel::onApiKeyInputChange,
                enabled = state.editable,
                singleLine = true,
                // Write-only: masked while typing unless the reveal toggle un-masks the CURRENT input.
                visualTransformation = if (state.apiKeyReveal) VisualTransformation.None else PasswordVisualTransformation(),
                placeholder = { Text(stringResource(Res.string.settings_apikey_placeholder)) },
                modifier = Modifier
                    .weight(1f)
                    .testTag(SettingsTags.API_KEY_INPUT)
                    .semantics { contentDescription = inputA11y },
            )
            val revealDesc = stringResource(
                if (state.apiKeyReveal) Res.string.a11y_settings_apikey_hide else Res.string.a11y_settings_apikey_reveal,
            )
            TextButton(
                onClick = viewModel::toggleApiKeyReveal,
                enabled = state.editable,
                modifier = Modifier
                    .testTag(SettingsTags.API_KEY_REVEAL)
                    .semantics { contentDescription = revealDesc },
            ) {
                // CYP-99: a reliable text label, not an emoji (👁/🙈 was tofu-prone on Desktop-JVM and broke
                // the no-emoji convention, CYP-54). Doubles as the a11y description above.
                Text(revealDesc, style = MaterialTheme.typography.labelMedium)
            }
        }

        if (!state.editable) {
            TonedHint(stringResource(Res.string.settings_operator_required), HintTone.GATED, SettingsTags.API_KEY_GATE_HINT)
        }

        Button(
            onClick = viewModel::saveApiKey,
            enabled = state.canSaveApiKey,
            modifier = Modifier.testTag(SettingsTags.API_KEY_SAVE),
        ) {
            Text(stringResource(Res.string.settings_save))
        }

        state.apiKeyError?.let { key ->
            TonedHint(errorText(key), HintTone.ERROR, SettingsTags.API_KEY_ERROR)
        }

        // Amber "saved ≠ active — restart the affected agents" (§4.2). No restart button here: the
        // honest activation is the existing CYP-73 per-agent restart (agent.<id>.restartBtn).
        if (state.apiKeyEffectHint) {
            TonedHint(stringResource(Res.string.settings_apikey_effect_hint), HintTone.EFFECT_DEFERRED, SettingsTags.API_KEY_EFFECT_HINT)
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
    )
}

/** Map a VM error i18n-key to its resolved string (the only keys that flow through state as errors). */
@Composable
private fun errorText(key: String): String = when (key) {
    "settings_repo_url_invalid" -> stringResource(Res.string.settings_repo_url_invalid)
    "settings_apikey_save_failed" -> stringResource(Res.string.settings_apikey_save_failed)
    "settings_operator_required" -> stringResource(Res.string.settings_operator_required)
    else -> key
}
