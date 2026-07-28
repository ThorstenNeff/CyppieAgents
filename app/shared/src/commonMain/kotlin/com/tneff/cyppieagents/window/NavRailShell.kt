package com.tneff.cyppieagents.window

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * CYP-894 (Epic CYP-892, Nav-Rail S1) — the desktop **vertical-nav-rail shell**: a persistent rail (left) + a content
 * pane (right). Shown **ONLY** at Landscape + short-edge ≥ ~600dp; below that (or portrait) the shell renders the
 * current canvas-only layout **UNCHANGED** (no rail — zero regression). One active destination at a time; the pane
 * routes each destination via the injected [paneContent] (Canvas → the existing WindowHost, filled by the caller).
 *
 * S1 is the shell + responsive gate + one-active state. The agent-maximize (shared-VM) + settings destinations are
 * S3/S4 — here they route through [paneContent] to whatever the caller supplies (a placeholder until then).
 */

/** The rail's minimum short-edge (dp) — below this, or in portrait, the rail is not offered (S1 scope). */
const val NAV_RAIL_MIN_SHORT_EDGE_DP: Float = 600f

/**
 * Whether the vertical nav-rail is eligible for the given host size (dp): **landscape** (width > height) AND the
 * **short edge ≥ [NAV_RAIL_MIN_SHORT_EDGE_DP]**. Pure — the single load-bearing S1 predicate (mutation target).
 * Below/portrait ⇒ false ⇒ the caller renders the current canvas-only shell unchanged.
 */
fun railEligible(widthDp: Float, heightDp: Float): Boolean =
    widthDp > heightDp && minOf(widthDp, heightDp) >= NAV_RAIL_MIN_SHORT_EDGE_DP

/** A rail destination. Agents are dynamic (PO + workers); Canvas + Settings are fixed. */
sealed interface NavDestination {
    /** The existing floating-window canvas (paradigm unchanged). */
    data object Canvas : NavDestination

    /** A maximized agent destination (PO or a worker), keyed by its agent id. [isPo] orders it above the workers. */
    data class Agent(val agentId: String, val label: String, val isPo: Boolean) : NavDestination

    /** The settings destination. */
    data object Settings : NavDestination
}

/** Stable key for a destination's testTag/selection (Canvas/Settings fixed; Agent by id). */
private fun destinationKey(dest: NavDestination): String = when (dest) {
    NavDestination.Canvas -> "canvas"
    is NavDestination.Agent -> "agent.${dest.agentId}"
    NavDestination.Settings -> "settings"
}

/** `testTag` contract for the nav-rail shell. */
object NavRailTags {
    const val SHELL = "navRail.shell"
    const val RAIL = "navRail.rail"
    const val PANE = "navRail.pane"
    fun item(dest: NavDestination) = "navRail.item.${destinationKey(dest)}"
}

@Composable
fun NavRailShell(
    destinations: List<NavDestination>,
    selected: NavDestination,
    onSelect: (NavDestination) -> Unit,
    labelFor: (NavDestination) -> String,
    modifier: Modifier = Modifier,
    paneContent: @Composable (NavDestination) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (!railEligible(maxWidth.value, maxHeight.value)) {
            // Below threshold / portrait → the current canvas-only shell, UNCHANGED. No rail, no wrapper Row.
            paneContent(NavDestination.Canvas)
        } else {
            androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxSize().testTag(NavRailTags.SHELL)) {
                VerticalNavRail(destinations, selected, onSelect, labelFor)
                Box(modifier = Modifier.weight(1f).fillMaxHeight().testTag(NavRailTags.PANE)) {
                    // One active destination — only the selected pane composes (no 2nd render path for the others).
                    paneContent(selected)
                }
            }
        }
    }
}

/**
 * CYP-894 (S1) — the placeholder pane for a not-yet-wired destination (PO/Worker agents = S3 shared-VM-maximize;
 * Settings = S4). A tagged full-size box; the real content routes through the caller's `paneContent` in S3/S4.
 */
@Composable
fun NavPanePlaceholder(tag: String) {
    Box(modifier = Modifier.fillMaxSize().testTag(tag))
}

@Composable
private fun VerticalNavRail(
    destinations: List<NavDestination>,
    selected: NavDestination,
    onSelect: (NavDestination) -> Unit,
    labelFor: (NavDestination) -> String,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            modifier = Modifier.width(84.dp).fillMaxHeight().testTag(NavRailTags.RAIL).selectableGroup()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            destinations.forEach { dest ->
                val active = dest == selected
                Text(
                    text = (if (active) "● " else "") + labelFor(dest),
                    modifier = Modifier
                        .testTag(NavRailTags.item(dest))
                        .selectable(selected = active, role = Role.Tab) { onSelect(dest) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
