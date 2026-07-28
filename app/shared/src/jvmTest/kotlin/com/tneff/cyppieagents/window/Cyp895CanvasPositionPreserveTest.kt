package com.tneff.cyppieagents.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.AgentShell
import com.tneff.cyppieagents.agentmgmt.StubAgentManagementRepository
import com.tneff.cyppieagents.agentview.StubAgentSession
import com.tneff.cyppieagents.comm.CommApi
import com.tneff.cyppieagents.comm.StubCommLiveSource
import com.tneff.cyppieagents.crossproject.StubCrossProjectRepository
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.project.StubProjectRepository
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-895 (Nav-Rail S2) — **canvas position-preservation across destination navigation.** The floating-window
 * geometry ([WindowManagerState]) is hoisted ABOVE the nav-rail's destination switch in [AgentShell]; navigating
 * away from the Canvas destination only removes the WindowHost render subtree, not the state. So a window the user
 * moved keeps its exact position/size when they navigate away and back — no reset, no re-tile.
 *
 * This drives the **real** [AgentShell] (stub sessions/APIs, hermetic) at a rail-eligible size: move a canvas window
 * by keyboard, navigate Canvas → Settings → Canvas, and assert the window returns to the SAME on-screen position.
 *
 * **★ Load-bearing tooth (MUT):** re-seed / re-tile the canvas state on back-nav (e.g. hoist `state` INTO the canvas
 * pane so it re-inits, or `resetTo(...)` on Canvas re-entry) → the moved window snaps back to its default tile →
 * `pos-after ≈ pos-before-nav` fails → RED.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp895CanvasPositionPreserveTest {

    private class FakeCommApi : CommApi {
        override suspend fun channels() =
            listOf(Channel("po-frontend", "PO ↔ Frontend", ChannelKind.HUB, listOf("po", "frontend")))
        override suspend fun agents() = listOf(Agent("po", "Product Owner", Role.PO, "po"))
        override suspend fun messages(channelId: String, since: Long?) = emptyList<Message>()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?) =
            Message("x", channelId, "operator", body, 1L)
    }

    @Test
    fun movedCanvasWindow_keepsPosition_acrossNavAwayAndBack() = runComposeUiTest {
        setContent {
            MaterialTheme {
                // Generous landscape host so NavRailShell/railEligible (>600dp short edge, w>h) offers the rail.
                Box(Modifier.size(1200.dp, 840.dp)) {
                    AgentShell(
                        sessionFactory = { StubAgentSession() },
                        commApi = FakeCommApi(),
                        commLiveSource = StubCommLiveSource(),
                        agentManagementRepository = StubAgentManagementRepository(),
                        projectRepository = StubProjectRepository(),
                        crossProjectRepository = StubCrossProjectRepository(),
                    )
                }
            }
        }

        // The rail is present (eligible host) and the PO canvas window is laid out.
        onNodeWithTag(NavRailTags.RAIL).assertExists()
        val poWindow = WindowTestTags.window("po")
        onNodeWithTag(poWindow).assertExists()

        // Move the PO window well off its default tile via keyboard (deterministic, no drag flake).
        onNodeWithTag(poWindow).requestFocus()
        repeat(6) { onNodeWithTag(poWindow).performKeyInput { pressKey(Key.DirectionRight) } }
        repeat(4) { onNodeWithTag(poWindow).performKeyInput { pressKey(Key.DirectionDown) } }
        waitForIdle()
        val movedPos = onNodeWithTag(poWindow).fetchSemanticsNode().positionInRoot

        // Navigate AWAY from Canvas → Settings destination: the canvas (WindowHost) leaves composition.
        onNodeWithTag(NavRailTags.item(NavDestination.Settings)).performClick()
        waitForIdle()
        onNodeWithTag(poWindow).assertDoesNotExist() // one-active: canvas not composed while off-Canvas

        // Navigate BACK to Canvas: the window returns.
        onNodeWithTag(NavRailTags.item(NavDestination.Canvas)).performClick()
        waitForIdle()
        onNodeWithTag(poWindow).assertExists()
        val restoredPos = onNodeWithTag(poWindow).fetchSemanticsNode().positionInRoot

        // …at the SAME position it was moved to — state survived the switch, no re-seed/re-tile.
        assertTrue(
            kotlin.math.abs(restoredPos.x - movedPos.x) < 1f && kotlin.math.abs(restoredPos.y - movedPos.y) < 1f,
            "canvas window position must be preserved across nav-away+back: moved=$movedPos restored=$restoredPos",
        )
    }
}
