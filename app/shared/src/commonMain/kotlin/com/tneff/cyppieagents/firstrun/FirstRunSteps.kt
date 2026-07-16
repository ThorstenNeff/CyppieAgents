package com.tneff.cyppieagents.firstrun

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kmpcyppieagents.app.shared.generated.resources.first_run_skip
import com.tneff.cyppieagents.agentmgmt.AgentManagementPanel
import com.tneff.cyppieagents.agentmgmt.AgentManagementViewModel
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
import kmpcyppieagents.app.shared.generated.resources.first_run_repo_clone_failed
import kmpcyppieagents.app.shared.generated.resources.first_run_repo_clone_failed_auth
import kmpcyppieagents.app.shared.generated.resources.first_run_repo_clone_failed_url
import kmpcyppieagents.app.shared.generated.resources.first_run_repo_clone_ok
import kmpcyppieagents.app.shared.generated.resources.first_run_repo_cloning
import kmpcyppieagents.app.shared.generated.resources.first_run_repo_cloning_slow
import kmpcyppieagents.app.shared.generated.resources.first_run_repo_saved
import kmpcyppieagents.app.shared.generated.resources.first_run_step_apikey
import kmpcyppieagents.app.shared.generated.resources.first_run_step_repo
import kmpcyppieagents.app.shared.generated.resources.first_run_step_team
import kmpcyppieagents.app.shared.generated.resources.first_run_team_intro
import org.jetbrains.compose.resources.stringResource

/** CYP-629 §4.4 — after this long in `CLONING`, the calm "still cloning, can take minutes" line appears (so a fast
 *  clone is not over-warned). NOT a timeout: the clone keeps running, the poll never invents a failure. */
internal const val CLONE_SLOW_THRESHOLD_MS: Long = 15_000

/**
 * CYP-629 (2d + stepper) — the setup stepper: a step indicator + the current step's content, landing on the FIRST
 * OPEN step ([firstRunOpenStep], PO constraint ④ — never blindly "step 1"). Each step embeds the already-parameterized
 * reused section (ApiKeySection/RepoSection) or the roster, and the first-run wrapper renders the net-new surfaces
 * (posture, saved confirmations, team intro) AROUND them — the shared sections stay first-run-agnostic (2b/2c cut).
 */
@Composable
internal fun FirstRunStepper(
    modifier: Modifier = Modifier,
    status: FirstRunConfigStatus,
    settingsState: SettingsUiState,
    settingsViewModel: SettingsViewModel,
    agentMgmtViewModel: AgentManagementViewModel,
    activeProjectName: String?,
    onSkip: () -> Unit,
) {
    // ④: initial selection = the first open step (API_KEY or REPO in ACTIVE; TEAM is only reachable by tapping,
    // since "both core done" is TRANSPARENT, not ACTIVE). User-navigable thereafter.
    var current by remember { mutableStateOf(firstRunOpenStep(status)) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().testTag(FirstRunTags.STEPPER),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StepChip(FirstRunTags.STEP_API_KEY, stringResource(Res.string.first_run_step_apikey), selected = current == FirstRunStep.API_KEY) { current = FirstRunStep.API_KEY }
            StepChip(FirstRunTags.STEP_REPO, stringResource(Res.string.first_run_step_repo), selected = current == FirstRunStep.REPO) { current = FirstRunStep.REPO }
            StepChip(FirstRunTags.STEP_TEAM, stringResource(Res.string.first_run_step_team), selected = current == FirstRunStep.TEAM) { current = FirstRunStep.TEAM }
        }

        // Weighted, bounded region: the config steps scroll their own content; the team step's roster panel
        // (fillMaxSize + its own scroll) needs a bounded height here — NOT an ancestor scroll (infinite-height crash).
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (current) {
                FirstRunStep.API_KEY -> FirstRunApiKeyStep(settingsState, settingsViewModel)
                FirstRunStep.REPO -> FirstRunRepoStep(settingsState, settingsViewModel, status)
                FirstRunStep.TEAM -> FirstRunTeamStep(agentMgmtViewModel, activeProjectName)
            }
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
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
 * "set, cloning now" INFO confirmation (§4.1) + the live [CloneStatusDisplay] (§4.2/§4.4), driven by the polled
 * [FirstRunConfigStatus] from the §7 seam.
 */
@Composable
internal fun FirstRunRepoStep(state: SettingsUiState, viewModel: SettingsViewModel, status: FirstRunConfigStatus) {
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RepoSection(state, viewModel, firstRunContext = true)
        if (state.repoEffectHint) {
            TonedHint(
                stringResource(Res.string.first_run_repo_saved), HintTone.INFO, FirstRunTags.REPO_SAVED,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        CloneStatusDisplay(status)
    }
}

/**
 * CYP-629 §4.2/§4.4 — the repo clone lifecycle, exactly one state (via [cloneDisplay], fail-closed to CLONING while
 * unsure). CLONING shows a **live indeterminate indicator** (§4.4 — a life-sign, never a fabricated % bar, since
 * `CloneStatus` carries no progress) + the "cloning…" line (INFO/Polite). CLONED_OK is neutral INFO (no green).
 * CLONE_FAILED is a real, operator-correctable failure → ERROR/Assertive, with the reason selecting the actionable
 * copy (URL vs auth vs generic). Fixed via the SAME repo save (§4.3 — no separate retry CTA). `NOT_CONFIGURED`
 * (repo not set yet) renders nothing.
 */
@Composable
internal fun CloneStatusDisplay(status: FirstRunConfigStatus) {
    when (cloneDisplay(status.cloneStatus)) {
        null -> Unit
        CloneDisplay.CLONING -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp)) // §4.4: live life-sign, NOT a fake progress bar
                TonedHint(
                    stringResource(Res.string.first_run_repo_cloning), HintTone.INFO, FirstRunTags.REPO_CLONING,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            // §4.4: after a threshold a calm "still cloning — can take minutes" line, so a LONG clone reads as
            // expected, not a hang. Polite, announced once. This is NOT a failure/timeout — the clone keeps running
            // (the poll never invents CLONE_FAILED); the user can also leave it running in the background (skip).
            var slow by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { delay(CLONE_SLOW_THRESHOLD_MS); slow = true }
            if (slow) {
                TonedHint(
                    stringResource(Res.string.first_run_repo_cloning_slow), HintTone.INFO, FirstRunTags.REPO_CLONING_SLOW,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        CloneDisplay.CLONED_OK -> TonedHint(
            stringResource(Res.string.first_run_repo_clone_ok), HintTone.INFO, FirstRunTags.REPO_CLONE_OK,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        CloneDisplay.FAILED -> {
            val copy = when (status.cloneReason) {
                CloneFailReason.URL_UNREACHABLE -> Res.string.first_run_repo_clone_failed_url
                CloneFailReason.AUTH -> Res.string.first_run_repo_clone_failed_auth
                CloneFailReason.UNKNOWN, null -> Res.string.first_run_repo_clone_failed
            }
            TonedHint(
                stringResource(copy), HintTone.ERROR, FirstRunTags.REPO_CLONE_FAILED,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
    }
}

/**
 * Step 4 — team (§5). Net-new framing (`first_run_team_intro`) in the WRAPPER + the reused [AgentManagementPanel]
 * embedded UNCHANGED (reuse pur — no `firstRunContext`: its `agent_add_spawn_hint` is TRUE in first-run too, so
 * there is no lying hint to suppress). Team is OPTIONAL: the roster starts with one PO; adding agents is not a
 * completion prerequisite (that is Key + Repo CLONED_OK, ux-spec §6.1) — nothing here gates the finish.
 */
@Composable
internal fun FirstRunTeamStep(agentMgmtViewModel: AgentManagementViewModel, activeProjectName: String?) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TonedHint(stringResource(Res.string.first_run_team_intro), HintTone.INFO, FirstRunTags.TEAM_INTRO)
        AgentManagementPanel(agentMgmtViewModel, activeProjectName = activeProjectName)
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
