package com.tneff.cyppieagents.multihub

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.connect.HubDescriptor
import com.tneff.cyppieagents.net.hub.trust.HubTrustTags
import kotlin.test.Test

/**
 * CYP-861 (Compose-M4 list-live) — the **render-honesty invariant** (plan §3 mirror): a switcher mounted LIVE but
 * NOT ARMED renders the LIST honestly — with the dark `activeHubId=""`, **no hub is marked active** (never a false
 * "this is your active/connected hub"), and the axis-a trust badge stays **UNKNOWN** (`state=null`), never
 * trusted/connected. Real observed trust + the active pointer are the Compose-M3/§9.3 arming lane.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp861RenderHonestyTest {

    private fun hub(id: String) =
        HubDescriptor(hubId = id, name = "Hub $id", online = true, defaultPort = 8787, lastSeen = 0L)

    @Test
    fun mountedNotArmed_activeHubIdBlank_noHubMarkedActive_badgeUnknown() = runComposeUiTest {
        // The dark literal the AgentShell mount passes pre-arming.
        setContent { HubSwitcherBar(loadHubList(listOf(hub("a"), hub("b"))), activeHubId = "", onSwitch = {}, onRetry = {}) }
        onNodeWithTag(HubSwitcherTags.BAR).assertExists()
        // NO hub marked active pre-arming (activeHubId="" matches no entry).
        onNodeWithTag(HubSwitcherTags.entry("a"), useUnmergedTree = true).assertIsNotSelected()
        onNodeWithTag(HubSwitcherTags.entry("b"), useUnmergedTree = true).assertIsNotSelected()
        // The axis-a trust badge is present but UNKNOWN (state=null) — never trusted/connected pre-arming.
        onNodeWithTag(HubTrustTags.area("a"), useUnmergedTree = true).assertExists()
    }

    @Test
    fun control_realActiveId_marksThatHubActive() = runComposeUiTest {
        // Non-vacuity (discrimination): a REAL active id DOES select that entry — so the blank-id "not selected"
        // assertion above is meaningful. (This is the behaviour a future M3 arming would drive; the mount keeps it dark.)
        setContent { HubSwitcherBar(loadHubList(listOf(hub("a"), hub("b"))), activeHubId = "a", onSwitch = {}, onRetry = {}) }
        onNodeWithTag(HubSwitcherTags.entry("a"), useUnmergedTree = true).assertIsSelected()
        onNodeWithTag(HubSwitcherTags.entry("b"), useUnmergedTree = true).assertIsNotSelected()
    }
}
