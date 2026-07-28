package com.tneff.cyppieagents.multihub

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.connect.HubDescriptor
import com.tneff.cyppieagents.net.hub.trust.HubTrustTags
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-856 — the render teeth for the hub switcher (Slice-1 logic proven through the UI):
 *  - **honest-empty tri-state**: unknown → render nothing; honest-empty → the empty node (not error, not list);
 *    load-error → the error surface (not empty, not list).
 *  - **four separate axis nodes** per hub (name / reach / lastSeen + the axis-a trust badge's own namespace).
 *  - **non-optimistic selection**: the active marker follows the server-confirmed `activeHubId` INPUT, never the
 *    click; switch-to-active is a no-op; a pending switch disables every entry.
 *
 * runComposeUiTest is display-sensitive; the pure ([HubListStateTest]/[HubListHolderTest]) + source-scan
 * ([Cyp856HubSwitcherSeamGuardTest]) teeth carry the contract headlessly. The PO clean-env full gate runs these.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp856HubSwitcherRenderTest {

    private fun hub(id: String, online: Boolean = true) =
        HubDescriptor(hubId = id, name = "Hub $id", online = online, defaultPort = 8787, lastSeen = 0L)

    @Test
    fun unknown_rendersNothing() = runComposeUiTest {
        setContent { HubSwitcher(HubListState.EMPTY, activeHubId = "a", onSwitch = {}, onRetry = {}) }
        onNodeWithTag(HubSwitcherTags.LIST).assertDoesNotExist()
        onNodeWithTag(HubSwitcherTags.EMPTY).assertDoesNotExist()
        onNodeWithTag(HubSwitcherTags.ERROR).assertDoesNotExist()
    }

    @Test
    fun honestEmpty_rendersEmpty_notErrorNotList() = runComposeUiTest {
        setContent { HubSwitcher(loadHubList(emptyList()), activeHubId = "a", onSwitch = {}, onRetry = {}) }
        onNodeWithTag(HubSwitcherTags.EMPTY).assertExists()
        onNodeWithTag(HubSwitcherTags.ERROR).assertDoesNotExist()
        onNodeWithTag(HubSwitcherTags.LIST).assertDoesNotExist()
    }

    @Test
    fun loadError_rendersErrorSurface_notEmptyNotList() = runComposeUiTest {
        setContent { HubSwitcher(failHubList(), activeHubId = "a", onSwitch = {}, onRetry = {}) }
        onNodeWithTag(HubSwitcherTags.ERROR).assertExists()
        onNodeWithTag(HubSwitcherTags.EMPTY).assertDoesNotExist()
        onNodeWithTag(HubSwitcherTags.LIST).assertDoesNotExist()
    }

    @Test
    fun hubs_renderFourSeparateAxisNodes_perHub() = runComposeUiTest {
        setContent { HubSwitcher(loadHubList(listOf(hub("a"))), activeHubId = "a", onSwitch = {}, onRetry = {}) }
        // Four un-conflated axes, each its OWN node: name / reachability / freshness / axis-a trust badge namespace.
        onNodeWithTag(HubSwitcherTags.name("a"), useUnmergedTree = true).assertExists()
        onNodeWithTag(HubSwitcherTags.reach("a"), useUnmergedTree = true).assertExists()
        onNodeWithTag(HubSwitcherTags.lastSeen("a"), useUnmergedTree = true).assertExists()
        onNodeWithTag(HubTrustTags.area("a"), useUnmergedTree = true).assertExists()
    }

    @Test
    fun selection_nonOptimistic_activeFollowsInput_switchToActiveNoOp() = runComposeUiTest {
        val switched = mutableListOf<String>()
        setContent {
            HubSwitcher(
                loadHubList(listOf(hub("a"), hub("b"))),
                activeHubId = "a",
                onSwitch = { switched.add(it) },
                onRetry = {},
            )
        }
        // Active follows the INPUT activeHubId, not the click.
        onNodeWithTag(HubSwitcherTags.entry("a"), useUnmergedTree = true).assertIsSelected()
        onNodeWithTag(HubSwitcherTags.entry("b"), useUnmergedTree = true).assertIsNotSelected()

        // Switch-to-active = no-op (the injected seam is never invoked).
        onNodeWithTag(HubSwitcherTags.entry("a")).performClick()
        waitForIdle()
        assertTrue(switched.isEmpty(), "clicking the ALREADY-active hub must be a no-op (never calls onSwitch)")

        // Clicking a non-active hub invokes onSwitch, but the active marker does NOT re-point (non-optimistic): the
        // input activeHubId is unchanged, so 'a' stays selected until the server confirms.
        onNodeWithTag(HubSwitcherTags.entry("b")).performClick()
        waitForIdle()
        assertEquals(listOf("b"), switched, "clicking a non-active hub invokes the injected onSwitch seam")
        onNodeWithTag(HubSwitcherTags.entry("a"), useUnmergedTree = true).assertIsSelected()
        onNodeWithTag(HubSwitcherTags.entry("b"), useUnmergedTree = true).assertIsNotSelected()
    }

    @Test
    fun pendingSwitch_disablesEveryEntry_untilResolved() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        setContent {
            HubSwitcher(
                loadHubList(listOf(hub("a"), hub("b"))),
                activeHubId = "a",
                onSwitch = { gate.await() }, // stays pending until the gate completes
                onRetry = {},
            )
        }
        onNodeWithTag(HubSwitcherTags.entry("b")).performClick()
        waitForIdle()
        // A pending switch disables the WHOLE list (non-optimistic — no in-flight re-point).
        onNodeWithTag(HubSwitcherTags.entry("a"), useUnmergedTree = true).assertIsNotEnabled()
        onNodeWithTag(HubSwitcherTags.entry("b"), useUnmergedTree = true).assertIsNotEnabled()

        gate.complete(Unit)
        waitForIdle()
        onNodeWithTag(HubSwitcherTags.entry("a"), useUnmergedTree = true).assertIsEnabled()
    }
}
