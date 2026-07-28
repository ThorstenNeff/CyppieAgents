package com.tneff.cyppieagents.multihub

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.connect.HubDescriptor
import com.tneff.cyppieagents.connect.RemoteConnectTags
import com.tneff.cyppieagents.model.HubTrustState
import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.trust.HubTrustTags
import kotlin.test.Test

/**
 * CYP-873 (Compose Multi-Hub M4) — the **§3 render-honesty** teeth of the [MultiHubShell] mount-host (mirror of the
 * web-ts `MultiHubShell.render.test.tsx` tooth). Three axes:
 *
 *  1. **pre-arming-never-false-positive** — mounted with the DEFAULT idle connector + empty provenance + blank active
 *     id: every hub's trust badge is UNKNOWN, NO hub is marked active, and NO Zone-2 progression renders. Honest
 *     UNKNOWN / nothing — never a false trusted/connected/live.
 *  2. **DRIVEN liveness ≠ trust** (non-vacuity for #1's "no progression") — a hub whose injected machine is CONNECTED
 *     DOES render the Zone-2 progression (liveness is driven by the machine), yet its trust badge STAYS UNKNOWN
 *     (no observation was recorded → liveness is not trust). A host that derived trust from the connection reddens.
 *  3. **the host FEEDS displayedTrust** (non-vacuity for the UNKNOWN badges) — with an injected provenance where an
 *     INACTIVE hub was TRUSTED, the host's `displayedTrust` degrades it to STALE in the switcher badge. A hardcoded
 *     `trustFor = { null }` (UNKNOWN everywhere) reddens here.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp873MultiHubShellRenderTest {

    private fun hub(id: String) =
        HubDescriptor(hubId = id, name = "Hub $id", online = true, defaultPort = 8787, lastSeen = 0L)

    /** A DARK-armed (in-test) connector whose active machine rests at a fixed [state] — drives Zone-2 liveness only. */
    private fun fixedConnector(state: RemoteConnState) = HubConnector<RemoteConnState?> {
        object : HubConnectionHandle<RemoteConnState?> {
            override val machine: RemoteConnState? = state
            override fun close() {}
        }
    }

    @Test
    fun mountedNotArmed_everyBadgeUnknown_noActive_noProgression() = runComposeUiTest {
        // Production mount: default idle connector, empty provenance, blank active id.
        setContent { MultiHubShell(hubListState = loadHubList(listOf(hub("a"), hub("b"))), onRetryHubs = {}) }

        onNodeWithTag(HubSwitcherTags.BAR).assertExists()
        // No hub marked active (blank active id matches no entry).
        onNodeWithTag(HubSwitcherTags.entry("a"), useUnmergedTree = true).assertIsNotSelected()
        onNodeWithTag(HubSwitcherTags.entry("b"), useUnmergedTree = true).assertIsNotSelected()
        // Every trust badge is UNKNOWN — never a false trusted/rejected pill.
        onNodeWithTag(HubTrustTags.state("a", "unknown"), useUnmergedTree = true).assertExists()
        onNodeWithTag(HubTrustTags.state("b", "unknown"), useUnmergedTree = true).assertExists()
        onNodeWithTag(HubTrustTags.state("a", "trusted"), useUnmergedTree = true).assertDoesNotExist()
        // NO Zone-2 progression chrome pre-arming (idle machine → progressionStateFor null → nothing renders).
        onNodeWithTag(RemoteConnectTags.CONNECTED, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(RemoteConnectTags.RELAY_DIALING, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(RemoteConnectTags.TRUST_CHECK, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun drivenConnectedMachine_showsProgression_butTrustStaysUnknown() = runComposeUiTest {
        // A CONNECTED machine (armed in-test) — the Zone-2 progression renders (liveness), but trust is NOT observed.
        setContent {
            MultiHubShell(
                hubListState = loadHubList(listOf(hub("a"))),
                onRetryHubs = {},
                initialActiveHubId = "a",
                connector = fixedConnector(RemoteConnState.CONNECTED),
            )
        }
        // Liveness IS driven → the CONNECTED progression renders (this also makes #1's "no progression" non-vacuous).
        onNodeWithTag(RemoteConnectTags.CONNECTED, useUnmergedTree = true).assertExists()
        // ...but trust STAYS UNKNOWN: a live connection is not an observed-trust. liveness ≠ trust.
        onNodeWithTag(HubTrustTags.state("a", "unknown"), useUnmergedTree = true).assertExists()
        onNodeWithTag(HubTrustTags.state("a", "trusted"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun hostFeedsDisplayedTrust_inactiveTrustedHub_showsStale() = runComposeUiTest {
        // Injected provenance: hub "a" was observed TRUSTED. With "b" active, "a" is inactive → the host's
        // displayedTrust degrades it to STALE (never cached-trusted). Proves the host FEEDS displayedTrust (non-vacuous).
        setContent {
            MultiHubShell(
                hubListState = loadHubList(listOf(hub("a"), hub("b"))),
                onRetryHubs = {},
                initialActiveHubId = "b",
                initialProvenance = recordObservation(emptyTrustProvenance, "a", HubTrustState.TRUSTED, 1L),
            )
        }
        onNodeWithTag(HubTrustTags.state("a", "stale"), useUnmergedTree = true).assertExists()
        onNodeWithTag(HubTrustTags.state("a", "trusted"), useUnmergedTree = true).assertDoesNotExist()
        // "b" is active, never observed → UNKNOWN (fail-closed).
        onNodeWithTag(HubTrustTags.state("b", "unknown"), useUnmergedTree = true).assertExists()
    }
}
