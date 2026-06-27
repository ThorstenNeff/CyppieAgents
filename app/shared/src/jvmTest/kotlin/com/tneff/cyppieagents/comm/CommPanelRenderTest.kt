package com.tneff.cyppieagents.comm

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.Role
import kotlin.test.Test

/**
 * CYP-21: proves the comm panel renders against the data ports — channel list (ACL-readable, here
 * server-filtered to one), auto-selected timeline, and a message row. Hermetic: a fake [CommApi]
 * and the [StubCommLiveSource] — no network.
 */
@OptIn(ExperimentalTestApi::class)
class CommPanelRenderTest {

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
    fun panel_rendersChannelsAndTimeline() = runComposeUiTest {
        val viewModel = CommViewModel(FakeCommApi(), StubCommLiveSource(), viewerId = "operator")
        setContent { MaterialTheme { CommPanel(viewModel) } }

        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(CommTags.CHANNEL_LIST).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(CommTags.CHANNEL_LIST).assertExists()
        onNodeWithTag(CommTags.channel("po-frontend")).assertExists()

        // First channel auto-selected → history loads → timeline + the message row appear.
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(CommTags.message("m1")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(CommTags.TIMELINE).assertExists()
        onNodeWithTag(CommTags.COMPOSER_INPUT).assertExists()
    }
}
