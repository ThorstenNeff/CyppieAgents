package com.tneff.cyppieagents.firstrun

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kmpcyppieagents.app.shared.generated.resources.first_run_skip
import com.tneff.cyppieagents.settings.ApiKeySection
import com.tneff.cyppieagents.settings.RepoSection
import com.tneff.cyppieagents.settings.SettingsUiState
import com.tneff.cyppieagents.settings.SettingsViewModel
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.first_run_apikey_posture
import kmpcyppieagents.app.shared.generated.resources.first_run_apikey_saved
import kmpcyppieagents.app.shared.generated.resources.first_run_complete_body
import kmpcyppieagents.app.shared.generated.resources.first_run_complete_title
import kmpcyppieagents.app.shared.generated.resources.first_run_open_workspace
import kmpcyppieagents.app.shared.generated.resources.first_run_repo_saved
import kmpcyppieagents.app.shared.generated.resources.first_run_step_apikey
import kmpcyppieagents.app.shared.generated.resources.first_run_step_repo
import kmpcyppieagents.app.shared.generated.resources.first_run_step_team
import kmpcyppieagents.app.shared.generated.resources.first_run_team_intro
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-629 (2d + stepper) — the setup stepper: a step indicator + the current step's content, landing on the FIRST
 * OPEN step ([firstRunOpenStep], PO constraint ④ — never blindly "step 1"). Each step embeds the already-parameterized
 * reused section (ApiKeySection/RepoSection) or the roster, and the first-run wrapper renders the net-new surfaces
 * (posture, saved confirmations, team intro) AROUND them — the shared sections stay first-run-agnostic (2b/2c cut).
 */
@Composable
internal fun FirstRunStepper(
    status: FirstRunConfigStatus,
    settingsState: SettingsUiState,
    settingsViewModel: SettingsViewModel,
    onSkip: () -> Unit,
) {
    // ④: initial selection = the first open step (API_KEY or REPO in ACTIVE; TEAM is only reachable by tapping,
    // since "both core done" is TRANSPARENT, not ACTIVE). User-navigable thereafter.
    var current by remember { mutableStateOf(firstRunOpenStep(status)) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().testTag(FirstRunTags.STEPPER),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StepChip(FirstRunTags.STEP_API_KEY, stringResource(Res.string.first_run_step_apikey), selected = current == FirstRunStep.API_KEY) { current = FirstRunStep.API_KEY }
            StepChip(FirstRunTags.STEP_REPO, stringResource(Res.string.first_run_step_repo), selected = current == FirstRunStep.REPO) { current = FirstRunStep.REPO }
            StepChip(FirstRunTags.STEP_TEAM, stringResource(Res.string.first_run_step_team), selected = current == FirstRunStep.TEAM) { current = FirstRunStep.TEAM }
        }

        when (current) {
            FirstRunStep.API_KEY -> FirstRunApiKeyStep(settingsState, settingsViewModel)
            FirstRunStep.REPO -> FirstRunRepoStep(settingsState, settingsViewModel)
            FirstRunStep.TEAM -> FirstRunTeamStep()
        }

        // Skip (§6.2): honest degraded workspace, no dead end (the banner/resume land in Inc4).
        TextButton(onClick = onSkip, modifier = Modifier.testTag(FirstRunTags.SKIP)) {
            Text(stringResource(Res.string.first_run_skip))
        }
    }
}

@Composable
private fun StepChip(tag: String, label: String, selected: Boolean, onClick: () -> Unit) {
    // The step switcher; `selected` = the current step. (A "done/abgehakt" marker per step is a UIUX polish for a
    // later slice — deliberately not a colour-only signal, so it needs a verified non-ASCII-safe glyph first.)
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.testTag(tag),
    )
}

/**
 * Step 2 — API key. The net-new first-run surfaces the shared [ApiKeySection] deliberately does NOT render (2b cut):
 * the at-rest posture (§3.1, VERBATIM copy — the only place the product discloses "not encrypted at rest"), the
 * embedded section in first-run context (restart hint suppressed), and the neutral INFO "saved" confirmation (§3.2).
 */
@Composable
internal fun FirstRunApiKeyStep(state: SettingsUiState, viewModel: SettingsViewModel) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TonedHint(stringResource(Res.string.first_run_apikey_posture), HintTone.INFO, FirstRunTags.APIKEY_POSTURE)
        ApiKeySection(state, viewModel, firstRunContext = true)
        // Neutral INFO confirmation instead of the suppressed restart hint (same apiKeyEffectHint save signal).
        if (state.apiKeyEffectHint) {
            TonedHint(
                stringResource(Res.string.first_run_apikey_saved), HintTone.INFO, FirstRunTags.APIKEY_SAVED,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * Step 3 — repository. Embeds the reused [RepoSection] in first-run context (next-boot hint suppressed) + the
 * "set, cloning now" INFO confirmation (§4.1). The live clone-status states (§4.2/§4.4) wire against the §7 seam in
 * Inc3 — not here.
 */
@Composable
internal fun FirstRunRepoStep(state: SettingsUiState, viewModel: SettingsViewModel) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RepoSection(state, viewModel, firstRunContext = true)
        if (state.repoEffectHint) {
            TonedHint(
                stringResource(Res.string.first_run_repo_saved), HintTone.INFO, FirstRunTags.REPO_SAVED,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * Step 4 — team. Net-new framing only (§5): the roster starts with one PO; adding agents is optional now or later.
 * The `AgentManagementPanel` roster embed lands next (its own VM wiring); this pins the intro framing.
 */
@Composable
internal fun FirstRunTeamStep() {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TonedHint(stringResource(Res.string.first_run_team_intro), HintTone.INFO, FirstRunTags.TEAM_INTRO)
    }
}

/**
 * CYP-629 §6.1 — the completion surface. ③: it is shown because [firstRunGateMode] == TRANSPARENT (key set AND repo
 * CLONED_OK), NOT because the user clicked a last step — so nobody sees "done" while the clone is still running. The
 * CTA is the ONLY thing that advances into the workspace.
 */
@Composable
internal fun FirstRunComplete(onOpen: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(FirstRunTags.COMPLETE),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(Res.string.first_run_complete_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(Res.string.first_run_complete_body), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onOpen, modifier = Modifier.testTag(FirstRunTags.OPEN_WORKSPACE)) {
            Text(stringResource(Res.string.first_run_open_workspace))
        }
    }
}
