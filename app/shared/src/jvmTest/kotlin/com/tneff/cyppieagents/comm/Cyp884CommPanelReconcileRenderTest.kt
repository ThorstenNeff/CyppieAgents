package com.tneff.cyppieagents.comm

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageKind
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.Role
import kotlin.test.Test

/**
 * CYP-884 (merge-reconciliation, my CYP-879 forward-flag) — OS-A (CYP-879 orchestration render: kind badges + reply
 * threading in the timeline) and OS-D (CYP-884 AgentAddressPicker, mounted above the channel nav) now live in ONE
 * Compose CommPanel. This pins exactly that coexistence: both render, and the picker mount does not displace the
 * message loop. Mirror of web-ts `CommPanel.reconcile.render.test.tsx`.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp884CommPanelReconcileRenderTest {

    private class FakeCommApi(private val msgs: List<Message>) : CommApi {
        override suspend fun channels() = listOf(Channel("c", "C", ChannelKind.DIRECT, listOf("po", "frontend")))
        override suspend fun agents() = listOf(
            Agent("po", "Product Owner", Role.PO, "po"),
            Agent("frontend", "Frontend", Role.WORKER, "frontend"),
            Agent("backend", "Backend", Role.WORKER, "backend"),
        )
        override suspend fun messages(channelId: String, since: Long?) = msgs
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?) =
            Message("real-1", "c", "operator", body, 99L)
    }

    @Test
    fun osA_orchestrationRender_and_osD_picker_coexist_inOneCommPanel() = runComposeUiTest {
        val vm = CommViewModel(
            FakeCommApi(
                listOf(
                    Message("p", "c", "po", "b-p", 1L),
                    Message("r", "c", "po", "b-r", 2L, MessageMeta(kind = MessageKind.TASK, inReplyTo = "p")),
                ),
            ),
            StubCommLiveSource(),
            viewerId = "operator",
        )
        setContent { MaterialTheme { CommPanel(vm) } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(CommTags.message("r")).fetchSemanticsNodes().isNotEmpty() }

        // OS-D: the addressing picker mounts (roster non-empty → shown above the channel nav).
        onNodeWithTag(AgentAddressTags.ROOT).assertExists()
        // OS-A: the orchestration render is present in the timeline (kind badge + reply reference).
        onNodeWithTag(CommTags.messageKind("r"), useUnmergedTree = true).assertTextEquals("TASK")
        onNodeWithTag(CommTags.messageReplyTo("r"), useUnmergedTree = true).assertExists()
        // …and the picker mount did NOT displace the message loop — both messages still render.
        onNodeWithTag(CommTags.message("p")).assertExists()
        onNodeWithTag(CommTags.message("r")).assertExists()
    }
}
