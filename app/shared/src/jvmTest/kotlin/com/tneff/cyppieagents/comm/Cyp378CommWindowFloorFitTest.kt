package com.tneff.cyppieagents.comm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.AgentShell
import com.tneff.cyppieagents.ShellConfig
import com.tneff.cyppieagents.agentmgmt.StubAgentManagementRepository
import com.tneff.cyppieagents.agentview.StubAgentSession
import com.tneff.cyppieagents.crossproject.StubCrossProjectRepository
import com.tneff.cyppieagents.eventlog.StubEventsApi
import com.tneff.cyppieagents.eventlog.StubEventsSource
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.project.StubProjectRepository
import com.tneff.cyppieagents.window.CONTENT_WINDOW_MIN_HEIGHT
import com.tneff.cyppieagents.window.TILED_CONTENT_WINDOW_MIN_HEIGHT
import com.tneff.cyppieagents.window.TILED_CONTENT_WINDOW_MIN_WIDTH
import com.tneff.cyppieagents.window.WindowTestTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-378 — **the shared content-window floor carries the COMM window too, not only the Agent window it was
 * derived from.**
 *
 * [CONTENT_WINDOW_MIN_HEIGHT] governs every content window — `AgentShell` classes Agent **and Comm** as
 * content (both carry a composer). But `ContentWindowChromeFloorGuardTest` measures only the `AgentWindow`
 * composition, and the floor came out of Agent chrome. If the Comm window's chrome were taller, the floor would
 * be too low for it — the CYP-363 defect one window over, and nobody was measuring it. This is that measurement.
 *
 * Measured, and the answer is **it fits, with room** — the floor is set by the taller window (Agent), and Comm
 * sits under it:
 *  - Comm's title bar is **smaller** than the Agent's (measured on the real shell — the frame is not the same
 *    height for both windows), so at the floor CommPanel receives `CONTENT_WINDOW_MIN_HEIGHT` minus that title bar.
 *  - Comm's own fixed chrome (conversation header + composer) fits well within that; the message list is the
 *    `weight(1f)` middle. So at the floor the composer is whole and the message area keeps real room — nowhere
 *    near the all-chrome clamp the Agent window hit before CYP-363.
 *
 * The proof, not the number: at the floor the Comm composer input is at its **full natural height** and the
 * message area is **present** (non-zero). If Comm's chrome ever grows past the floor — a new toolbar row, a
 * taller composer — the input clamps here and this goes red, exactly as it would for the Agent window. Both title
 * bar and natural heights are measured, never hardcoded, so the guard follows the composition.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp378CommWindowFloorFitTest {

    private class IdleSource : CommLiveSource {
        override fun events(): Flow<CommLiveEvent> = flow { awaitCancellation() }
    }

    /** One channel, auto-selected → the conversation pane (header + message area + composer) renders. */
    private class OneChannelApi : CommApi {
        override suspend fun channels() = listOf(Channel("a", "A", ChannelKind.HUB, listOf("operator")))
        override suspend fun agents() = emptyList<Agent>()
        override suspend fun messages(channelId: String, since: Long?) = emptyList<Message>()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?) =
            Message("s", channelId, "operator", body, 9L)
    }

    private class FakeWritable(val v: List<String>) : WritableChannelsApi {
        override suspend fun writableChannels() = v
    }

    private fun vm() = CommViewModel(
        OneChannelApi(), IdleSource(), viewerId = "operator",
        writableChannels = FakeWritable(listOf("a")), scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun theCommComposerSurvivesTheSharedFloor_soTheFloorCarriesBothContentWindows() {
        val titleBar = measureCommTitleBarOnTheRealShell()
        val naturalInput = commInputHeight(availableHeight = 2_000f)
        assertTrue(naturalInput > 0f, "precondition: the comm composer must render at a generous height")

        listOf(
            "CONTENT_WINDOW_MIN_HEIGHT" to CONTENT_WINDOW_MIN_HEIGHT,
            "TILED_CONTENT_WINDOW_MIN_HEIGHT" to TILED_CONTENT_WINDOW_MIN_HEIGHT,
        ).forEach { (name, windowFloor) ->
            val panelHeight = windowFloor - titleBar
            val measured = commState(panelHeight)
            assertTrue(
                measured.inputHeight >= naturalInput - EPS,
                "CYP-378 [$name=$windowFloor]: at the shared floor the Comm composer input is only " +
                    "${measured.inputHeight} dp (natural $naturalInput). Comm's chrome now EXCEEDS the floor that " +
                    "was derived from the Agent window — the CYP-363 defect, one window over. The floor must rise " +
                    "to carry the taller of the two content windows; measure Comm's chrome and lift the constant.",
            )
            assertTrue(
                measured.messageAreaPresent,
                "CYP-378 [$name=$windowFloor]: at the shared floor the Comm message area is gone — the window is " +
                    "all chrome. The composer alone surviving is not enough; a comm window with no room for a " +
                    "message is the same 100%-chrome state CYP-363 closed for the agent window.",
            )
        }
    }

    // --- measurement ------------------------------------------------------------------------------------------

    private data class CommState(val inputHeight: Float, val messageAreaPresent: Boolean)

    /**
     * The Comm window's title bar, read off the REAL shell — it is **36 dp, not the Agent's 64 dp**, so it must
     * be measured for Comm specifically, not borrowed from the sibling guard.
     */
    private fun measureCommTitleBarOnTheRealShell(): Float {
        var h = Float.NaN
        runComposeUiTest {
            setContent { MaterialTheme { Box(Modifier.size(900.dp, 3_000.dp)) { RealShell() } } }
            awaitTag(WindowTestTags.window(COMM))
            val r = onNodeWithTag(WindowTestTags.titleBar(COMM)).getUnclippedBoundsInRoot()
            h = (r.bottom - r.top).value
        }
        return h
    }

    private fun commInputHeight(availableHeight: Float): Float = commState(availableHeight).inputHeight

    private fun commState(availableHeight: Float): CommState {
        lateinit var state: CommState
        runComposeUiTest {
            setContent {
                MaterialTheme {
                    Box(Modifier.size(TILED_CONTENT_WINDOW_MIN_WIDTH.dp, availableHeight.dp)) { CommPanel(vm()) }
                }
            }
            waitForIdle()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(CommTags.COMPOSER_INPUT).fetchSemanticsNodes().isNotEmpty()
            }
            val input = onNodeWithTag(CommTags.COMPOSER_INPUT).getUnclippedBoundsInRoot()
            // The message area is the empty-timeline text (this fixture has no messages); present with height
            // means the weight(1f) middle still has room, absent/zero means it was squeezed out.
            val empty = onAllNodesWithTag(CommTags.EMPTY_TIMELINE).fetchSemanticsNodes()
            val emptyHeight = if (empty.isEmpty()) 0f else onNodeWithTag(CommTags.EMPTY_TIMELINE)
                .getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }
            state = CommState(
                inputHeight = (input.bottom - input.top).value,
                messageAreaPresent = emptyHeight > 0f,
            )
        }
        return state
    }

    private fun ComposeUiTest.awaitTag(tag: String) = waitUntil(timeoutMillis = 5_000) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private class FakeShellCommApi : CommApi {
        override suspend fun channels() = emptyList<Channel>()
        override suspend fun agents() = emptyList<Agent>()
        override suspend fun messages(channelId: String, since: Long?) = emptyList<Message>()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?) =
            Message("x", channelId, "operator", body, 1L)
    }

    @Composable
    private fun RealShell() = AgentShell(
        config = ShellConfig.dev().copy(operatorToken = "op-token"),
        sessionFactory = { StubAgentSession() },
        commApi = FakeShellCommApi(),
        commLiveSource = StubCommLiveSource(),
        eventsApi = StubEventsApi(),
        eventsLiveSource = StubEventsSource(),
        agentManagementRepository = StubAgentManagementRepository(),
        projectRepository = StubProjectRepository(),
        crossProjectRepository = StubCrossProjectRepository(),
    )

    private companion object {
        const val COMM = "comm"
        const val EPS = 0.5f
    }
}
