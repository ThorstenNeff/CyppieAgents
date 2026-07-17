package com.tneff.cyppieagents.firstrun

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import com.tneff.cyppieagents.workspace.WorkspaceTags
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_workspace_unconfigured_collapse
import kmpcyppieagents.app.shared.generated.resources.first_run_repo_clone_failed
import kmpcyppieagents.app.shared.generated.resources.first_run_repo_clone_failed_auth
import kmpcyppieagents.app.shared.generated.resources.first_run_repo_clone_failed_url
import kmpcyppieagents.app.shared.generated.resources.first_run_step_apikey
import kmpcyppieagents.app.shared.generated.resources.first_run_step_repo
import kmpcyppieagents.app.shared.generated.resources.workspace_setup_resume
import kmpcyppieagents.app.shared.generated.resources.workspace_unconfigured_banner
import kmpcyppieagents.app.shared.generated.resources.workspace_unconfigured_chip
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-629 Inc4 (ux-spec §6.2/§6.3a) — the honest degraded workspace after a skip: the real [workspace] plus a
 * persistent unconfigured surface, **as long as the hub is still unconfigured** ([workspaceUnconfiguredState].visible,
 * i.e. the gate would be ACTIVE). Once key + repo `CLONED_OK` land, [workspaceUnconfigured] goes invisible and the
 * surface vanishes — no lingering nag.
 *
 * **Nag-fix, two bits (§6.3a):**
 * - [collapsedPref] is the user's **sticky** collapse preference. It is HOISTED to [FirstRunGate] (gate scope), so it
 *   survives a resume → gate → skip round-trip (this composable unmounts on resume). If it were local here, that cycle
 *   would reset it and the full banner would re-nag every time — the exact §6.3a bug. Reset only on relaunch → the
 *   full banner appears **once per session-start**, then the collapse is respected for the session.
 * - [expandedOnDemand] is a **transient** local bit: a chip-tap shows the banner on demand WITHOUT clearing the sticky
 *   preference, and it resets on unmount — so a resume detour returns to the passive chip, not the full banner.
 *
 * So the resting collapsed state is the passive [WorkspaceTags.UNCONFIGURED_CHIP] (never wholly hidden — the unfinished
 * hub is a real standing fact; hiding it to nothing would be an honesty omission), while the real honesty enforcement
 * lives at the point of action (the GATED per-agent start block, §6.3c — a later, coordinated slice).
 */
@Composable
internal fun DegradedWorkspace(
    status: FirstRunConfigStatus,
    collapsedPref: Boolean,
    onCollapse: () -> Unit,
    onResume: () -> Unit,
    workspace: @Composable () -> Unit,
) {
    val state = workspaceUnconfigured(status)
    var expandedOnDemand by remember { mutableStateOf(false) }
    val showBanner = state.visible && (!collapsedPref || expandedOnDemand)
    val showChip = state.visible && !showBanner

    Column(modifier = Modifier.fillMaxSize()) {
        when {
            showBanner -> WorkspaceUnconfiguredBanner(
                state = state,
                // Collapsing clears the on-demand expand AND records the sticky preference.
                onCollapse = { expandedOnDemand = false; onCollapse() },
                onResume = onResume,
            )
            showChip -> WorkspaceUnconfiguredChip(onExpand = { expandedOnDemand = true })
        }
        // The real workspace in a bounded, weighted region — a fillMaxSize child inside an unbounded/scrolling
        // parent is an infinite-height crash (same lesson as the stepper).
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { workspace() }
    }
}

/**
 * The full unconfigured banner (§6.2/§6.3a): the expected-degraded note (INFO/Polite, never alarm-red) + the collapse
 * control + the SPECIFIC missing items + the resume CTA. Specific, not generic: the open items are the reused first-run
 * step labels as chips; a `CLONE_FAILED` repo instead carries the clone-error copy (it is *set*, not missing — the
 * CYP-639 confusion §7 closes). The resume CTA is LAST in the group (a11y: `setupResume` must not be the first tab stop).
 */
@Composable
private fun WorkspaceUnconfiguredBanner(
    state: WorkspaceUnconfiguredState,
    onCollapse: () -> Unit,
    onResume: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Persistent context, so Polite (announces once on first appearance, like the remote-context banner).
            TonedHint(
                text = stringResource(Res.string.workspace_unconfigured_banner),
                tone = HintTone.INFO,
                tag = WorkspaceTags.UNCONFIGURED_BANNER,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            // Collapse control — a text button (tofu-safe, no icon dependency); its visible label is also its
            // SR-audible name (a bare icon would be mute to a screen reader, a11y §6.3a).
            TextButton(onClick = onCollapse, modifier = Modifier.testTag(WorkspaceTags.UNCONFIGURED_COLLAPSE)) {
                Text(stringResource(Res.string.a11y_workspace_unconfigured_collapse))
            }
        }
        // Specific missing (§6.3a): the reused step labels as "missing" chips (no grammar-fragile new sentences).
        if (state.missingApiKey || state.missingRepo) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.missingApiKey) MissingChip(stringResource(Res.string.first_run_step_apikey))
                if (state.missingRepo) MissingChip(stringResource(Res.string.first_run_step_repo))
            }
        }
        // A set-but-failed repo reads the clone-error copy, NEVER "repository missing" (the specific truth, not the
        // generic lie). ERROR tone: this IS a real, operator-correctable failure (unlike the expected unconfigured note).
        if (state.cloneFailed) {
            val copy = when (state.cloneReason) {
                CloneFailReason.URL_UNREACHABLE -> Res.string.first_run_repo_clone_failed_url
                CloneFailReason.AUTH -> Res.string.first_run_repo_clone_failed_auth
                CloneFailReason.UNKNOWN, null -> Res.string.first_run_repo_clone_failed
            }
            TonedHint(stringResource(copy), HintTone.ERROR, WorkspaceTags.UNCONFIGURED_BANNER + ".cloneFailed")
        }
        TextButton(onClick = onResume, modifier = Modifier.testTag(WorkspaceTags.SETUP_RESUME)) {
            Text(stringResource(Res.string.workspace_setup_resume))
        }
    }
}

/** A passive "missing: X" label — the reused step label in a chip-shaped, non-interactive container. */
@Composable
private fun MissingChip(label: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * The collapsed, passive indicator chip. Deliberately carries **no** liveRegion — the collapsed chip must not announce,
 * so honesty is kept (it's never wholly hidden) without an assistive back-door nag (a11y §6.3a). Tap → re-expand.
 */
@Composable
private fun WorkspaceUnconfiguredChip(onExpand: () -> Unit) {
    AssistChip(
        onClick = onExpand,
        label = { Text(stringResource(Res.string.workspace_unconfigured_chip)) },
        modifier = Modifier.testTag(WorkspaceTags.UNCONFIGURED_CHIP),
    )
}
