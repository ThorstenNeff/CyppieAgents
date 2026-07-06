package com.tneff.cyppieagents.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-250 — the desktop (canvas) empty-state for a 0-AGENT project. Drives the real [WindowHost]/[WindowCanvas].
 * The empty-state triggers on `agentsEmpty` (the agent list), NOT on the window count — the tool windows always
 * coexist — so these render with the tool windows present AND agentsEmpty=true.
 *
 * UIUX §D2-addendum hardening: the tool windows are laid out with the REAL tiler ([WindowManagerState.resetTo] →
 * [WindowReducer.tile]) so they genuinely FILL the canvas and sit under the centered empty-state (the original
 * fixture used arbitrary positions that left the middle free, masking the occlusion). The "reachable" check uses a
 * COORDINATE hit-test through [onRoot] (which respects z-order) rather than `performClick` (which dispatches to the
 * node directly and would pass even if the CTA were occluded) — so a regression back to a background placement,
 * where the tiled tool windows draw over the panel, makes the CTA click land on a window and the test go RED.
 */
@OptIn(ExperimentalTestApi::class)
class WindowCanvasEmptyStateTest {

    private val hostW = 1000f
    private val hostH = 800f

    /** The always-present tool windows, laid out with the REAL tiler so they fill the canvas (agent-less, not window-less). */
    private fun tiledToolWindows() = WindowManagerState(emptyList()).apply {
        updateHostSize(hostW, hostH)
        resetTo(
            listOf(
                "agentMgmt" to "Agenten", "comm" to "Kommunikation", "acl" to "Zugriffe",
                "settings" to "Einstellungen", "productLead" to "Report",
                "eventlog" to "Event-Log", "eventtail" to "Live-Tail",
            ),
            emptySet(),
            false,
        )
    }

    @androidx.compose.runtime.Composable
    private fun Host(state: WindowManagerState, agentsEmpty: Boolean, canAddAgent: Boolean, onAdd: () -> Unit = {}) {
        Box(Modifier.size(hostW.dp, hostH.dp)) {
            WindowHost(
                state = state,
                agentsEmpty = agentsEmpty,
                canAddAgent = canAddAgent,
                onAddFirstAgent = onAdd,
                windowContent = { Text("stub ${it.id}") },
            )
        }
    }

    @Test
    fun emptyState_shownWhenAgentsEmpty_coexistingWithTiledToolWindows() = runComposeUiTest {
        setContent { MaterialTheme { Host(tiledToolWindows(), agentsEmpty = true, canAddAgent = true) } }
        // §9-1/§9-2: present WHILE the (tiled) tool windows are also present — decoupled from window count.
        onNodeWithTag(WindowTestTags.EMPTY).assertExists()
        onNodeWithTag(WindowTestTags.window("comm")).assertExists()
        onNodeWithTag(WindowTestTags.window("agentMgmt")).assertExists()
        onNodeWithTag(WindowTestTags.EMPTY_ADD_BTN).assertExists().assertIsEnabled()
        onNodeWithTag(WindowTestTags.EMPTY_GATE_HINT).assertDoesNotExist()
    }

    @Test
    fun emptyState_selfClears_whenAgentsPresent() = runComposeUiTest {
        setContent { MaterialTheme { Host(tiledToolWindows(), agentsEmpty = false, canAddAgent = true) } }
        onNodeWithTag(WindowTestTags.EMPTY).assertDoesNotExist()
        onNodeWithTag(WindowTestTags.EMPTY_ADD_BTN).assertDoesNotExist()
        onNodeWithTag(WindowTestTags.window("comm")).assertExists() // tool windows unaffected
    }

    /**
     * UIUX-QA regression guard (§D2 addendum): the CTA must be the TOP-MOST node at its own position — clickable
     * over the tiled tool windows, not occluded. Uses a coordinate hit-test through the root (z-order-aware).
     * Mutation: move the empty-state back to the background (before the window loop / no lifted zIndex) → a tiled
     * tool window sits on top at the CTA coordinate → the click lands on the window → addFlow stays 0 → RED.
     */
    @Test
    fun emptyState_ctaIsOnTopOfTiledToolWindows_reachableByHitTest() = runComposeUiTest {
        var addFlow = 0
        setContent { MaterialTheme { Host(tiledToolWindows(), agentsEmpty = true, canAddAgent = true, onAdd = { addFlow++ }) } }

        // A tool window must actually cover the CTA's position (else the occlusion scenario is vacuous)...
        val ctaCenter = onNodeWithTag(WindowTestTags.EMPTY_ADD_BTN, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center
        val covered = listOf("agentMgmt", "comm", "acl", "settings", "productLead", "eventlog", "eventtail").any { id ->
            onNodeWithTag(WindowTestTags.window(id), useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.contains(ctaCenter)
        }
        assertEquals(true, covered, "fixture sanity: a tiled tool window must sit under the CTA (real occlusion scenario)")

        // ...and clicking at that coordinate through the root (z-order-aware) must still reach the CTA.
        // CYP-266#1: BOUND the wait. A background-placement regression occludes the CTA → the click lands on a
        // tiled window → addFlow stays 0. With a bare waitForIdle() that regression manifests as a skiko
        // render-SPIN (the occluded frame never idles) → an indefinite test HANG, illegible in CI. waitUntil
        // fails fast+clean at the deadline (ComposeTimeoutException naming the condition) so the regression is
        // a legible RED, not a hang. On correct code addFlow hits 1 immediately → returns at once (no penalty).
        onRoot().performTouchInput { click(ctaCenter) }
        waitUntil("the foreground CTA received the click (not occluded by a tiled tool window)", timeoutMillis = 2_000) { addFlow == 1 }
        assertEquals(1, addFlow, "the CTA is the foreground overlay (top-most at its position), not occluded by a tiled tool window")
    }

    @Test
    fun emptyState_nonOperator_ctaDisabled_withHonestGateHint() = runComposeUiTest {
        setContent { MaterialTheme { Host(tiledToolWindows(), agentsEmpty = true, canAddAgent = false) } }
        // §9-5: no dead CTA — disabled button + the honest "why" gate hint.
        onNodeWithTag(WindowTestTags.EMPTY_ADD_BTN).assertExists().assertIsNotEnabled()
        onNodeWithTag(WindowTestTags.EMPTY_GATE_HINT).assertExists()
    }
}
