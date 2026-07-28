package com.tneff.cyppieagents.multihub

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.HubTrustState
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.hub_switcher_nav_label
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-856 Slice-2 — the **in-workspace top-bar** that hosts the [HubSwitcher], mounted over `WindowHost` at the
 * `ProjectSwitcherBar` level (Top-Level context: Project = *what*, Hub = *where*). A **separate bar**
 * ([HubSwitcherTags.BAR]) — not a rebuild of the project bar. Full-width, project-bar padding (`12.dp`/`6.dp`),
 * the nav `contentDescription` (parity with web's `aria-label`) lives HERE (the nav is the bar).
 *
 * **Absent when the list is UNKNOWN** (not-loaded): no confident-empty chrome — the whole bar renders nothing until
 * the list resolves (honest-empty §4). When loaded → the list; zero-hubs → an honest "Keine Hubs"; a failed load →
 * Error+Retry. Display-only: [onSwitch] + the real hub-list source are injected arming seams (M3), not built here.
 */
@Composable
fun HubSwitcherBar(
    state: HubListState,
    activeHubId: String,
    onSwitch: suspend (String) -> Unit,
    onRetry: () -> Unit,
    /** The axis-a trust to DISPLAY per hub (M4 supplies `displayedTrust`); DEFAULT `{ null }` → UNKNOWN (pre-arming). */
    trustFor: (String) -> HubTrustState? = { null },
    modifier: Modifier = Modifier,
) {
    // Unknown (not loaded) → render NOTHING: no empty padded bar, no confident-empty chrome (spec §4).
    if (state.outcome() is HubSwitcherOutcome.Unknown) return

    val barLabel = stringResource(Res.string.hub_switcher_nav_label)
    Surface(modifier = modifier.fillMaxWidth().testTag(HubSwitcherTags.BAR)) {
        HubSwitcher(
            state = state,
            activeHubId = activeHubId,
            onSwitch = onSwitch,
            onRetry = onRetry,
            trustFor = trustFor,
            // Project-bar padding + the nav label on the container (the nav IS the bar, parity web aria-label).
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .semantics { contentDescription = barLabel },
        )
    }
}
