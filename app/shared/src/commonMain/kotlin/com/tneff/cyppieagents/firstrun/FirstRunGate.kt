package com.tneff.cyppieagents.firstrun

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.tneff.cyppieagents.agentmgmt.AgentManagementViewModel
import com.tneff.cyppieagents.settings.SettingsViewModel
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.first_run_degraded_note
import kmpcyppieagents.app.shared.generated.resources.first_run_intro
import kmpcyppieagents.app.shared.generated.resources.first_run_title
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-629 §1 — the First-Run setup gate, inserted `AuthGate → RemoteHubConnectGate → ▶ FirstRunGate ◀ → Workspace`
 * (ux-spec §1). Mirrors the [com.tneff.cyppieagents.connect.RemoteHubConnectGate] opt-in-**off** discipline: with
 * [enabled] `false` (the default until the §7 Backend seam ships + a deploy GO) the gate is **inert** — neither VM is
 * constructed and [workspace] renders directly, byte-identical to today. A tooth pins "off ⇒ workspace".
 *
 * When enabled the mode is derived purely from the config status ([firstRunGateMode]):
 * - **LOADING** (status unknown) → a live load surface. **Never** rendered as "step 1" — unknown ≠ unconfigured
 *   (PO constraint ①); the load state is the honest statement, a step would assert a config-state we don't know.
 * - **TRANSPARENT** (key set AND repo `CLONED_OK`) → the completion surface. It appears because the MODE is
 *   TRANSPARENT, NOT because a "last step" was clicked (PO constraint ③) — so nobody sees "done" mid-clone. Only its
 *   CTA advances into [workspace].
 * - **ACTIVE** → orientation (§2) + the stepper, which lands on [firstRunOpenStep] (PO constraint ④).
 *
 * Skipping (§6.2) drops to the honest [DegradedWorkspace] (workspace + a persistent unconfigured banner/chip + a
 * resume CTA back into the gate); the gate re-derives on the next launch. Once configured the surface vanishes.
 */
@Composable
fun FirstRunGate(
    enabled: Boolean,
    createViewModel: () -> FirstRunViewModel,
    createSettingsViewModel: () -> SettingsViewModel,
    createAgentMgmtViewModel: () -> AgentManagementViewModel,
    activeProjectName: String? = null,
    workspace: @Composable () -> Unit,
) {
    if (!enabled) {
        // OFF (default): construct nothing — byte-identical to a build without the first-run flow.
        workspace()
        return
    }
    val viewModel = remember { createViewModel() }
    val settingsViewModel = remember { createSettingsViewModel() }
    val agentMgmtViewModel = remember { createAgentMgmtViewModel() }
    val status by viewModel.status.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    var skipped by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(false) } // set ONLY by the completion CTA (§6.1) — not a "completed" flag
    // Inc4 (§6.3a): the degraded-workspace banner's collapse preference — HOISTED to gate scope so it survives the
    // resume → gate → skip round-trip (DegradedWorkspace unmounts on resume; a local bit would reset and re-nag every
    // cycle). Reset only on relaunch → full banner once per session-start, then collapse respected for the session.
    var bannerCollapsed by remember { mutableStateOf(false) }
    val mode = firstRunGateMode(status)
    // §1/§6.3b — once configured the gate NEVER re-appears: a hub configured AT LAUNCH passes straight through (like
    // [com.tneff.cyppieagents.connect.RemoteHubConnectGate]), with NO completion step. The completion surface is the
    // IN-SESSION finish moment ONLY — it shows when this session actually went through setup (reached ACTIVE) and then
    // became TRANSPARENT. `wasActive` is a session-scoped latch, NOT a durable "completed" flag: completion is still
    // DERIVED from `mode == TRANSPARENT` (PO ③), just gated so a fresh configured launch is transparent, not a nag.
    var wasActive by remember { mutableStateOf(false) }
    LaunchedEffect(mode) { if (mode == FirstRunGateMode.ACTIVE) wasActive = true }
    // Live-wiring: after the operator saves the API key or repo in a step, the config changed server-side — re-read
    // it so the mode advances (e.g. ACTIVE → TRANSPARENT once both are set). Keyed on the settings save-signals so a
    // save (effect-hint false→true) triggers exactly one reload; without this the gate would never notice the key/repo
    // the user just entered and could never be completed.
    LaunchedEffect(settingsState.apiKeyEffectHint, settingsState.repoEffectHint) {
        if (settingsState.apiKeyEffectHint || settingsState.repoEffectHint) viewModel.reload()
    }

    when {
        opened -> workspace() // completed via the CTA → fully transparent, no degraded surface
        // Skipped (§6.2): the honest degraded workspace — the real workspace PLUS the unconfigured banner/chip while
        // still unconfigured; the banner clears itself once configured (workspaceUnconfigured goes invisible). Resume
        // reopens the gate at the first open step (firstRunOpenStep, not always step 1).
        skipped -> DegradedWorkspace(
            status = status,
            collapsedPref = bannerCollapsed,
            onCollapse = { bannerCollapsed = true },
            onResume = { skipped = false },
            workspace = workspace,
        )
        else -> when (mode) {
            // TRANSPARENT: pass straight through UNLESS the operator just completed setup this session (§1 vs §6.1).
            FirstRunGateMode.TRANSPARENT -> if (wasActive) FirstRunComplete(onOpen = { opened = true }) else workspace()
            FirstRunGateMode.LOADING -> FirstRunLoading()
            FirstRunGateMode.ACTIVE ->
                FirstRunActive(status, settingsState, settingsViewModel, agentMgmtViewModel, activeProjectName, onSkip = { skipped = true })
        }
    }
}

/**
 * Fail-closed load surface (constraint ①): the status is not yet known, so the gate shows a live indicator rather
 * than passing through OR rendering a step. (The full `LoadErrorRetry` reuse + retry wiring land with the live
 * source in Inc3.)
 */
@Composable
private fun FirstRunLoading() {
    Column(
        modifier = Modifier.fillMaxSize().testTag(FirstRunTags.GATE).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
    }
}

/** ACTIVE: orientation (§2) framed honestly + the setup stepper (lands on the first open step, §6.3b/④). */
@Composable
private fun FirstRunActive(
    status: FirstRunConfigStatus,
    settingsState: com.tneff.cyppieagents.settings.SettingsUiState,
    settingsViewModel: SettingsViewModel,
    agentMgmtViewModel: com.tneff.cyppieagents.agentmgmt.AgentManagementViewModel,
    activeProjectName: String?,
    onSkip: () -> Unit,
) {
    // Fixed orientation header + a weighted stepper body (NOT an outer verticalScroll: the embedded roster panel
    // scrolls itself + fillMaxSize, and a fillMaxSize child inside a scroll is an infinite-height crash. The stepper
    // gives its step content a weighted, bounded region so each step scrolls appropriately).
    Column(
        modifier = Modifier.fillMaxSize().testTag(FirstRunTags.GATE).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(Res.string.first_run_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        // Orientation (§2): what to do now. INFO — neutral, never success-green / alarm-red.
        TonedHint(text = stringResource(Res.string.first_run_intro), tone = HintTone.INFO, tag = FirstRunTags.INTRO)
        // Degraded framing (§2, PO point): frame the degraded boot as EXPECTED, not broken. Polite live-region once.
        TonedHint(
            text = stringResource(Res.string.first_run_degraded_note),
            tone = HintTone.INFO,
            tag = FirstRunTags.DEGRADED_NOTE,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        FirstRunStepper(Modifier.weight(1f), status, settingsState, settingsViewModel, agentMgmtViewModel, activeProjectName, onSkip)
    }
}
