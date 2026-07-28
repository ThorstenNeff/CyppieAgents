package com.tneff.cyppieagents.multihub

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.connect.HubDescriptor
import kotlin.test.Test

/**
 * CYP-856 Slice-2 — the [HubSwitcherBar] placement + reachability rendering:
 *  - the bar mounts ([HubSwitcherTags.BAR]) and hosts the switcher when the list is loaded;
 *  - **unknown → NO bar** (no confident-empty chrome);
 *  - reachability renders shape **+ word** (● Online / ○ Offline), never the shape alone (WCAG 1.4.1). The NEUTRAL
 *    colouring is enforced by the source-scan `Cyp856SwitcherStylingGuardTest`.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp856HubSwitcherBarRenderTest {

    private fun hub(id: String, online: Boolean = true) =
        HubDescriptor(hubId = id, name = "Hub $id", online = online, defaultPort = 8787, lastSeen = 0L)

    @Test
    fun unknown_rendersNoBar() = runComposeUiTest {
        setContent { HubSwitcherBar(HubListState.EMPTY, activeHubId = "a", onSwitch = {}, onRetry = {}) }
        onNodeWithTag(HubSwitcherTags.BAR).assertDoesNotExist()
    }

    @Test
    fun loaded_mountsBar_hostingSwitcher() = runComposeUiTest {
        setContent { HubSwitcherBar(loadHubList(listOf(hub("a"))), activeHubId = "a", onSwitch = {}, onRetry = {}) }
        onNodeWithTag(HubSwitcherTags.BAR).assertExists()
        onNodeWithTag(HubSwitcherTags.LIST, useUnmergedTree = true).assertExists()
        onNodeWithTag(HubSwitcherTags.name("a"), useUnmergedTree = true).assertExists()
        onNodeWithTag(HubSwitcherTags.reach("a"), useUnmergedTree = true).assertExists()
    }

    @Test
    fun reachability_rendersShapeAndWord_online() = runComposeUiTest {
        setContent { HubSwitcherBar(loadHubList(listOf(hub("a", online = true))), activeHubId = "a", onSwitch = {}, onRetry = {}) }
        val reach = onNodeWithTag(HubSwitcherTags.reach("a"), useUnmergedTree = true)
        reach.assertTextContains("●", substring = true) // filled shape (never the sole signal)…
        reach.assertTextContains("Online", substring = true) // …paired with the WORD (WCAG 1.4.1)
    }

    @Test
    fun reachability_rendersShapeAndWord_offline() = runComposeUiTest {
        setContent { HubSwitcherBar(loadHubList(listOf(hub("a", online = false))), activeHubId = "a", onSwitch = {}, onRetry = {}) }
        val reach = onNodeWithTag(HubSwitcherTags.reach("a"), useUnmergedTree = true)
        reach.assertTextContains("○", substring = true) // hollow shape (offline ≠ untrusted; a neutral net fact)
        reach.assertTextContains("Offline", substring = true)
    }

    @Test
    fun honestEmpty_mountsBar_withEmptyState() = runComposeUiTest {
        setContent { HubSwitcherBar(loadHubList(emptyList()), activeHubId = "a", onSwitch = {}, onRetry = {}) }
        onNodeWithTag(HubSwitcherTags.BAR).assertExists()
        onNodeWithTag(HubSwitcherTags.EMPTY, useUnmergedTree = true).assertExists()
        onNodeWithTag(HubSwitcherTags.LIST).assertDoesNotExist()
    }
}
