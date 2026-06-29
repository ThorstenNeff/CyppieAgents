package com.tneff.cyppieagents.crossproject

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.comm.CommApi
import com.tneff.cyppieagents.comm.CommPanel
import com.tneff.cyppieagents.comm.CommTags
import com.tneff.cyppieagents.comm.CommViewModel
import com.tneff.cyppieagents.comm.StubCommLiveSource
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test

/**
 * CYP-93 anchor gate: the cross-project authorization control is **reachable from the channel context**
 * (host-anchored into the Comm channel list) AND **operator/owner-gated**. Mutation-provable: drop the
 * gate in `CrossProjectControls` → the authorize action shows for a non-operator → `nonOperator_*` RED.
 */
@OptIn(ExperimentalTestApi::class)
class CommCrossProjectAnchorTest {

    private class FakeCommApi : CommApi {
        override suspend fun channels() =
            listOf(Channel("po-frontend", "PO ↔ Frontend", ChannelKind.HUB, listOf("po", "frontend")))
        override suspend fun agents() = listOf(Agent("po", "Product Owner", Role.PO, "po"))
        override suspend fun messages(channelId: String, since: Long?) = emptyList<Message>()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?) =
            Message("x", channelId, "operator", body, 1L)
    }

    private fun slot(editable: Boolean): @Composable (String) -> Unit = { cid ->
        CrossProjectControls(
            CrossProjectViewModel(
                StubCrossProjectRepository(reachByChannel = mapOf(cid to listOf(CrossMember("agent-b", "p2", CrossAccess.READ)))),
                channelId = cid, editable = editable, scope = CoroutineScope(Dispatchers.Unconfined),
            ),
        )
    }

    @Test
    fun operator_controlReachableFromChannel_authorizeShown() = runComposeUiTest {
        val vm = CommViewModel(FakeCommApi(), StubCommLiveSource(), viewerId = "operator")
        setContent { MaterialTheme { CommPanel(vm, crossProjectSlot = slot(editable = true)) } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(CommTags.channel("po-frontend")).fetchSemanticsNodes().isNotEmpty() }
        // Reachable from the channel context: the cross-project badge + authorize render under the channel.
        onNodeWithTag(CrossProjectTags.badgeUnauthorized("po-frontend")).assertExists()
        onNodeWithTag(CrossProjectTags.AUTHORIZE).assertExists()
    }

    @Test
    fun nonOperator_controlGated_noAuthorize() = runComposeUiTest {
        val vm = CommViewModel(FakeCommApi(), StubCommLiveSource(), viewerId = "viewer")
        setContent { MaterialTheme { CommPanel(vm, crossProjectSlot = slot(editable = false)) } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(CommTags.channel("po-frontend")).fetchSemanticsNodes().isNotEmpty() }
        // Reachable but gated: the gate hint shows, the authorize action does not.
        onNodeWithTag(CrossProjectTags.GATE_HINT).assertExists()
        onNodeWithTag(CrossProjectTags.AUTHORIZE).assertDoesNotExist()
    }
}
