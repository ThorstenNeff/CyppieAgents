package com.tneff.cyppieagents.comm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.Role
import kotlin.test.Test

/**
 * CYP-156 (Klasse A): the comm panel collapses its master/detail to a SINGLE pane below
 * `PANE_COLLAPSE_WIDTH` (measured at the panel inner width). The same nodes render — list OR
 * conversation — and a new `comm.back` affordance navigates between them. Wide = two-pane (unchanged).
 * Mirrors the EventBrowse precedent test; hermetic (fake [CommApi] + [StubCommLiveSource]).
 */
@OptIn(ExperimentalTestApi::class)
class CommPaneCollapseTest {

    private class FakeCommApi : CommApi {
        override suspend fun channels() =
            listOf(Channel("po-frontend", "PO ↔ Frontend", ChannelKind.HUB, listOf("po", "frontend")))
        override suspend fun agents() = listOf(
            Agent("po", "Product Owner", Role.PO, "po"),
            Agent("frontend", "Frontend", Role.WORKER, "frontend"),
        )
        override suspend fun messages(channelId: String, since: Long?) =
            listOf(Message("m1", "po-frontend", "frontend", "hallo PO", 1L))
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?) =
            Message("real-1", "po-frontend", "operator", body, 2L)
    }

    @Test
    fun narrowWidth_collapsesToSinglePane_backTogglesListAndConversation() = runComposeUiTest {
        val vm = CommViewModel(FakeCommApi(), StubCommLiveSource(), viewerId = "operator")
        setContent { MaterialTheme { Box(Modifier.width(360.dp).height(600.dp)) { CommPanel(vm) } } }

        // The first channel auto-selects (CommViewModel init) → single-pane shows the conversation with a
        // back affordance; the channel list is NOT composed beside it.
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(CommTags.BACK).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(CommTags.TIMELINE).assertExists()
        onNodeWithTag(CommTags.BACK).assertExists()
        onNodeWithTag(CommTags.CHANNEL_LIST).assertDoesNotExist()

        // Back → the channel list owns the pane; the conversation is gone.
        onNodeWithTag(CommTags.BACK).performClick()
        waitForIdle()
        onNodeWithTag(CommTags.CHANNEL_LIST).assertExists()
        onNodeWithTag(CommTags.channel("po-frontend")).assertExists()
        onNodeWithTag(CommTags.TIMELINE).assertDoesNotExist()
        onNodeWithTag(CommTags.BACK).assertDoesNotExist()

        // Select a channel → navigate back to the conversation (single-pane again).
        onNodeWithTag(CommTags.channel("po-frontend")).performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(CommTags.BACK).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(CommTags.TIMELINE).assertExists()
        onNodeWithTag(CommTags.CHANNEL_LIST).assertDoesNotExist()
    }

    @Test
    fun wideWidth_keepsTwoPane_noBackAffordance() = runComposeUiTest {
        val vm = CommViewModel(FakeCommApi(), StubCommLiveSource(), viewerId = "operator")
        setContent { MaterialTheme { Box(Modifier.width(900.dp).height(600.dp)) { CommPanel(vm) } } }

        // Two-pane: list AND conversation side by side, and no single-pane back affordance.
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(CommTags.TIMELINE).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(CommTags.CHANNEL_LIST).assertExists()
        onNodeWithTag(CommTags.TIMELINE).assertExists()
        onNodeWithTag(CommTags.BACK).assertDoesNotExist()
    }
}
