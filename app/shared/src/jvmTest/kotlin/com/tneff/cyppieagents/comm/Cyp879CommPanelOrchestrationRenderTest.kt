package com.tneff.cyppieagents.comm

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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
 * CYP-879 (OS-A) — CommPanel renders the orchestration TYPE (TASK/STATUS badge) + reply THREADING (inReplyTo tree)
 * from SERVER-STAMPED meta ONLY (render ≠ authority, mirror of web-ts `CommPanel.cyp868.render.test.tsx`):
 *  - a TASK/STATUS message shows the kind badge (verbatim word); a NOTE / absent-meta message shows NO badge;
 *  - a reply to a PRESENT parent shows the reply reference + indent depth 1; a top-level/orphan shows none + depth 0.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp879CommPanelOrchestrationRenderTest {

    private class FakeCommApi(private val msgs: List<Message>) : CommApi {
        override suspend fun channels() = listOf(Channel("c", "C", ChannelKind.HUB, listOf("po", "frontend")))
        override suspend fun agents() = listOf(
            Agent("po", "Product Owner", Role.PO, "po"),
            Agent("frontend", "Frontend", Role.WORKER, "frontend"),
        )
        override suspend fun messages(channelId: String, since: Long?) = msgs
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?) =
            Message("real-1", "c", "operator", body, 99L)
    }

    private fun msg(id: String, meta: MessageMeta? = null) = Message(id, "c", "frontend", "b-$id", id.hashCode().toLong(), meta)

    @Test
    fun kindBadge_TASKandSTATUS_shown_NOTEandAbsent_quiet() = runComposeUiTest {
        val vm = CommViewModel(
            FakeCommApi(
                listOf(
                    msg("t", MessageMeta(kind = MessageKind.TASK)),
                    msg("s", MessageMeta(kind = MessageKind.STATUS)),
                    msg("n", MessageMeta(kind = MessageKind.NOTE)),
                    msg("a"),
                ),
            ),
            StubCommLiveSource(),
            viewerId = "operator",
        )
        setContent { MaterialTheme { CommPanel(vm) } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(CommTags.message("t")).fetchSemanticsNodes().isNotEmpty() }

        // TASK/STATUS → the server-stamped kind badge, verbatim word.
        onNodeWithTag(CommTags.messageKind("t"), useUnmergedTree = true).assertTextEquals("TASK")
        onNodeWithTag(CommTags.messageKind("s"), useUnmergedTree = true).assertTextEquals("STATUS")
        // NOTE = quiet default → NO badge; absent meta = plain NOTE-equivalent → NO badge (never fabricated).
        onNodeWithTag(CommTags.messageKind("n"), useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(CommTags.messageKind("a"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun replyTree_presentParent_showsRefAndDepth1_topLevelAndOrphan_none() = runComposeUiTest {
        val vm = CommViewModel(
            FakeCommApi(
                listOf(
                    msg("p"),
                    msg("r", MessageMeta(inReplyTo = "p")),
                    msg("o", MessageMeta(inReplyTo = "ghost")),
                ),
            ),
            StubCommLiveSource(),
            viewerId = "operator",
        )
        setContent { MaterialTheme { CommPanel(vm) } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(CommTags.message("r")).fetchSemanticsNodes().isNotEmpty() }

        // Reply to a PRESENT parent → the reply reference + indent depth 1 (from the server link).
        onNodeWithTag(CommTags.messageReplyTo("r"), useUnmergedTree = true).assertExists()
        onNodeWithTag(CommTags.message("r")).assert(SemanticsMatcher.expectValue(ReplyDepthKey, 1))
        // A top-level message → NO reference, depth 0.
        onNodeWithTag(CommTags.messageReplyTo("p"), useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(CommTags.message("p")).assert(SemanticsMatcher.expectValue(ReplyDepthKey, 0))
        // inReplyTo to an ABSENT parent → NO reference, depth 0 (never a fabricated thread to an unseen message).
        onNodeWithTag(CommTags.messageReplyTo("o"), useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(CommTags.message("o")).assert(SemanticsMatcher.expectValue(ReplyDepthKey, 0))
    }
}
