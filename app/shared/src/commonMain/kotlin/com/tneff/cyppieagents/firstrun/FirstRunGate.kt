package com.tneff.cyppieagents.firstrun

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import androidx.compose.ui.platform.testTag
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.first_run_degraded_note
import kmpcyppieagents.app.shared.generated.resources.first_run_intro
import kmpcyppieagents.app.shared.generated.resources.first_run_skip
import kmpcyppieagents.app.shared.generated.resources.first_run_skip_note
import kmpcyppieagents.app.shared.generated.resources.first_run_title
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-629 §1 — the First-Run setup gate, inserted `AuthGate → RemoteHubConnectGate → ▶ FirstRunGate ◀ → Workspace`
 * (ux-spec §1). Mirrors the [com.tneff.cyppieagents.connect.RemoteHubConnectGate] opt-in-**off** discipline: with
 * [enabled] `false` (the default until the §7 Backend seam ships + a deploy GO) the gate is **inert** — the VM is
 * never constructed and [workspace] renders directly, byte-identical to today. A tooth pins "off ⇒ workspace".
 *
 * When enabled it derives its mode purely from the config status ([firstRunGateMode]): TRANSPARENT (key set AND
 * repo CLONED_OK) → straight to [workspace]; LOADING (status unknown) → a load/retry surface (fail-closed — never
 * passes through on unknown); ACTIVE → the setup surface. Skipping (§6.2) drops to [workspace] (the degraded
 * banner is a later sub-slice); the gate re-derives on the next launch while still unconfigured.
 *
 * **Sub-slice 2a:** the shell — orientation (§2) + honest degraded framing + skip. The stepper and the embedded
 * reused `ApiKeySection`/`RepoSection`/roster (§3–§5) land in 2b+.
 */
@Composable
fun FirstRunGate(
    enabled: Boolean,
    createViewModel: () -> FirstRunViewModel,
    workspace: @Composable () -> Unit,
) {
    if (!enabled) {
        // OFF (default): construct nothing — byte-identical to a build without the first-run flow.
        workspace()
        return
    }
    val viewModel = remember { createViewModel() }
    val status by viewModel.status.collectAsState()
    // Skip is a session choice (§6.3b: the gate re-derives on the next launch while still unconfigured).
    var skipped by remember { mutableStateOf(false) }

    when {
        skipped -> workspace()
        else -> when (firstRunGateMode(status)) {
            FirstRunGateMode.TRANSPARENT -> workspace()
            FirstRunGateMode.LOADING -> FirstRunLoading()
            FirstRunGateMode.ACTIVE -> FirstRunSetup(onSkip = { skipped = true })
        }
    }
}

/**
 * Fail-closed load surface: the status is not yet known, so the gate shows a live indicator rather than passing
 * through (unknown ≠ configured, ux-spec §1). (2a: a minimal indicator; the full `LoadErrorRetry` reuse + retry
 * wiring land with the live source in a later sub-slice.)
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

/**
 * The ACTIVE setup surface (2a shell): title + orientation intro + the honest degraded framing ("running but not
 * configured is normal"), plus the skip affordance. Net-new copy only — no reused input sections yet (2b+).
 */
@Composable
private fun FirstRunSetup(onSkip: () -> Unit) {
    val skipLabel = stringResource(Res.string.first_run_skip)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(FirstRunTags.GATE)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(Res.string.first_run_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        // Orientation (§2): what to do now. INFO — neutral, never success-green / alarm-red.
        TonedHint(text = stringResource(Res.string.first_run_intro), tone = HintTone.INFO, tag = FirstRunTags.INTRO)
        // Degraded framing (§2, PO point): frame the degraded boot as EXPECTED so the operator doesn't read it as
        // broken. Polite live-region (a11y §2): a persistent context, announced once.
        TonedHint(
            text = stringResource(Res.string.first_run_degraded_note),
            tone = HintTone.INFO,
            tag = FirstRunTags.DEGRADED_NOTE,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        // Skip (§6.2): honest degraded workspace, no dead end (the banner/resume land in a later sub-slice).
        TextButton(onClick = onSkip, modifier = Modifier.testTag(FirstRunTags.SKIP)) { Text(skipLabel) }
        TonedHint(text = stringResource(Res.string.first_run_skip_note), tone = HintTone.INFO, tag = "${FirstRunTags.SKIP}.note")
    }
}
