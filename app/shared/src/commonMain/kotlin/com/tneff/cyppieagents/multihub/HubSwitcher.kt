package com.tneff.cyppieagents.multihub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.agentview.formatLocalHhMm
import com.tneff.cyppieagents.connect.HubDescriptor
import com.tneff.cyppieagents.net.hub.trust.HubTrustBadge
import com.tneff.cyppieagents.ui.LoadErrorRetry
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.hub_switcher_empty
import kmpcyppieagents.app.shared.generated.resources.hub_switcher_last_seen
import kmpcyppieagents.app.shared.generated.resources.hub_switcher_reach_offline
import kmpcyppieagents.app.shared.generated.resources.hub_switcher_reach_online
import kmpcyppieagents.app.shared.generated.resources.load_failed
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-856 (CYP-807-A Multi-Hub, Compose parity of web-ts CYP-852) — the **hub switcher**: the N known hubs
 * ([HubListState]) as a LIST (not a dropdown — a list entry carries per-hub badges + a11y a single item can't).
 * Reuses the ProjectSwitcher NON-OPTIMISTIC behaviour: the active marker follows the **server-confirmed
 * [activeHubId]**, never the click; a pending switch disables the whole list; switch-to-active is a no-op. The real
 * switch effect (teardown/setup of the new active hub) is the injected [onSwitch] — an **arming seam** (M3), not here.
 *
 * Slice-1 = LOGIC; **Slice-2 = the maritime/M3 styling per the UIUX spec** (`docs/design/CYP-856-…spec.md`), applied
 * here + the [HubSwitcherBar] placement. Every axis is a separate node/testTag.
 *
 * ★ FOUR DISTINCT AXES PER ENTRY, NEVER CONFLATED (colour never the sole signal — WCAG 1.4.1):
 *  - **name/identity** — [HubDescriptor.name], `onSurface`, ellipsised under a narrow bar
 *  - **reachability** — [HubDescriptor.online] → shape (`●` online / `○` offline) **+ WORD**, BOTH `onSurfaceVariant`
 *    (a NEUTRAL net fact: never green for online, never error-red for offline; offline ≠ untrusted)
 *  - **hub-key trust (axis a)** — the reused CYP-808 [HubTrustBadge], fed `state = null` → **UNKNOWN** (pre-arming
 *    neutral): the switcher NEVER wires the badge to a live trust decision; real observed trust arrives at arming (M3)
 *  - **freshness** — [HubDescriptor.lastSeen] → "zuletzt gesehen HH:mm", advisory `onSurfaceVariant` `labelSmall`
 *
 * axis-c ([HubDescriptor.issuerTrust]) is DELIBERATELY ABSENT — a Zone-2 connect verdict (M4), never a switcher badge.
 * Tier is the active-header, not here. Both absences are guarded by a source-scan tooth.
 *
 * **Empty ≠ Load-Error ≠ Unknown** (three [HubSwitcherOutcome]s, precedence error→unknown→empty→list): a failed
 * `GET /hubs` → Error+Retry (never a misleading "no hubs"); a successful zero-hub result → honest empty; not-loaded
 * → render nothing.
 */
@Composable
fun HubSwitcher(
    state: HubListState,
    /** The server-confirmed active hub — the active marker follows THIS, never an in-flight click (non-optimistic). */
    activeHubId: String,
    /** Switch the active hub. The real teardown/setup lives behind this injected seam (M3), not in the switcher. */
    onSwitch: suspend (String) -> Unit,
    /** Retry a failed `GET /hubs` load. */
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val outcome = state.outcome()) {
        // Unknown — not loaded yet; render NOTHING rather than a confident empty.
        HubSwitcherOutcome.Unknown -> Unit
        // Fail-closed, distinct outcome: a failed load is Error+Retry, NEVER an empty "no hubs".
        HubSwitcherOutcome.Error -> LoadErrorRetry(
            message = stringResource(Res.string.load_failed),
            onRetry = onRetry,
            containerTag = HubSwitcherTags.ERROR,
            retryTag = HubSwitcherTags.RETRY,
            modifier = modifier,
        )
        // Loaded, zero hubs — an honest empty state.
        HubSwitcherOutcome.Empty -> Text(
            text = stringResource(Res.string.hub_switcher_empty),
            modifier = modifier.testTag(HubSwitcherTags.EMPTY),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        is HubSwitcherOutcome.Hubs -> HubSwitcherList(outcome.hubs, activeHubId, onSwitch, modifier)
    }
}

@Composable
private fun HubSwitcherList(
    hubs: List<HubDescriptor>,
    activeHubId: String,
    onSwitch: suspend (String) -> Unit,
    modifier: Modifier,
) {
    var pending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun pick(hubId: String) {
        if (hubId == activeHubId) return // switch-to-active = no-op (non-optimistic, mirrors ProjectSwitcher)
        if (pending) return              // a pending switch disables the list → no in-flight re-point
        pending = true
        scope.launch {
            try {
                onSwitch(hubId)
            } finally {
                pending = false
            }
        }
    }

    // The nav/`aria-label` semantics live on the [HubSwitcherBar] container (parity: the nav is the bar).
    Column(
        modifier = modifier.testTag(HubSwitcherTags.LIST).selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        hubs.forEach { hub ->
            HubSwitcherEntry(
                hub = hub,
                active = hub.hubId == activeHubId,
                enabled = !pending,
                onClick = { pick(hub.hubId) },
            )
        }
    }
}

@Composable
private fun HubSwitcherEntry(
    hub: HubDescriptor,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val reachWord = if (hub.online) {
        stringResource(Res.string.hub_switcher_reach_online)
    } else {
        stringResource(Res.string.hub_switcher_reach_offline)
    }
    val lastSeen = stringResource(Res.string.hub_switcher_last_seen, formatLocalHhMm(hub.lastSeen))

    Row(
        modifier = Modifier
            .testTag(HubSwitcherTags.entry(hub.hubId))
            // NON-OPTIMISTIC: `selected` follows the server-confirmed active flag, not the click; a pending switch
            // disables every entry (no in-flight re-point). Switch-to-active is a no-op via pick()'s guard.
            .selectable(selected = active, enabled = enabled, role = Role.Tab, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Active marker — a NON-COLOUR `●` (never a colour-only highlight; mirrors ProjectSwitcherBar). Present iff active.
        if (active) {
            Text(
                "●",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        // Axis 1 — name/identity: primary onSurface, ellipsised under a narrow bar.
        Text(
            hub.name,
            modifier = Modifier.weight(1f, fill = false).testTag(HubSwitcherTags.name(hub.hubId)),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Axis 2 — reachability: shape (`●` online / `○` offline) + WORD, BOTH onSurfaceVariant — a NEUTRAL net fact
        // (never green for online, never error-red for offline; offline ≠ untrusted). The word carries the state
        // (shape is never the sole signal — WCAG 1.4.1).
        Text(
            "${hubReachabilityGlyph(hub.online)} $reachWord",
            modifier = Modifier
                .testTag(HubSwitcherTags.reach(hub.hubId))
                .semantics { stateDescription = reachWord },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        // Axis 4 — freshness: advisory "zuletzt gesehen HH:mm", onSurfaceVariant labelSmall (own axis ≠ reachability).
        Text(
            lastSeen,
            modifier = Modifier.testTag(HubSwitcherTags.lastSeen(hub.hubId)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        // Axis 3 — hub-key trust (axis a): the reused CYP-808 badge, UNKNOWN pre-arming (state = null) — NEVER wired
        // to a live decision here. axis-c (issuerTrust) + tier are DELIBERATELY ABSENT from the switcher.
        HubTrustBadge(hubId = hub.hubId, state = null)
    }
}

/**
 * CYP-856 — the reachability shape marker: filled `●` online / hollow `○` offline. Paired with the WORD so the shape
 * is never the sole signal (WCAG 1.4.1); a NEUTRAL net fact, never a severity/trust glyph. Pure — unit-tested directly.
 */
fun hubReachabilityGlyph(online: Boolean): String = if (online) "●" else "○"
