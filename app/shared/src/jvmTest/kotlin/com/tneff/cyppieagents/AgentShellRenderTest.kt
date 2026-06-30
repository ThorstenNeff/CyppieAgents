package com.tneff.cyppieagents

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.agentview.AgentViewTags
import com.tneff.cyppieagents.agentmgmt.StubAgentManagementRepository
import com.tneff.cyppieagents.agentview.StubAgentSession
import com.tneff.cyppieagents.comm.CommApi
import com.tneff.cyppieagents.comm.CommTags
import com.tneff.cyppieagents.comm.StubCommLiveSource
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.window.WindowTestTags
import kotlin.test.Test

/**
 * CYP-15/CYP-21: proves the shell marries the window manager (CYP-10) with the renderer (CYP-6,
 * a per-agent stream) AND the comm panel (CYP-21, a comm window). Hermetic: stub sessions + a fake
 * comm API, so the test never touches the network.
 */
@OptIn(ExperimentalTestApi::class)
class AgentShellRenderTest {

    private class FakeCommApi : CommApi {
        override suspend fun channels() =
            listOf(Channel("po-frontend", "PO ↔ Frontend", ChannelKind.HUB, listOf("po", "frontend")))
        override suspend fun agents() = listOf(Agent("po", "Product Owner", Role.PO, "po"))
        override suspend fun messages(channelId: String, since: Long?) = emptyList<Message>()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?) =
            Message("x", channelId, "operator", body, 1L)
    }

    @Test
    fun shell_rendersAgentStreamsAndCommWindow() = runComposeUiTest {
        setContent {
            MaterialTheme {
                AgentShell(
                    sessionFactory = { StubAgentSession() },
                    commApi = FakeCommApi(),
                    commLiveSource = StubCommLiveSource(),
                    // The default agent-management port is now the live REST client (CYP-86/87/88 swap) —
                    // inject the stub so the agent list (→ dynamic windows) stays hermetic, like commApi.
                    agentManagementRepository = StubAgentManagementRepository(),
                    // Likewise the project port is now the live client (CYP-92 swap) — inject the stub.
                    projectRepository = com.tneff.cyppieagents.project.StubProjectRepository(),
                    // And the cross-project port is the live client (CYP-93 swap) — inject the stub.
                    crossProjectRepository = com.tneff.cyppieagents.crossproject.StubCrossProjectRepository(),
                )
            }
        }

        onNodeWithTag(WindowTestTags.HOST).assertExists()
        // One AgentWindow per agent, addressed by its v0.5 stream tag.
        onNodeWithTag(AgentViewTags.stream("po")).assertExists()
        onNodeWithTag(AgentViewTags.stream("frontend")).assertExists()
        onNodeWithTag(AgentViewTags.stream("backend")).assertExists()

        // The comm panel is a window too. Its tiled width in the shell straddles PANE_COLLAPSE_WIDTH
        // (600dp) ENV-DEPENDENTLY (screen density / default window size): some environments render it
        // two-pane (≥600 → channel list + timeline side by side), others CYP-156 single-pane (<600 →
        // channel list, or the auto-selected conversation). So neither a hard two-pane nor a hard
        // single-pane assertion is portable. LAYOUT-AGNOSTIC check: the comm window renders functional
        // content either way — the channel list OR the conversation timeline — and both require the
        // channels to have loaded (= comm wired). The pane-mode behaviour itself is covered
        // deterministically by CommPaneCollapseTest (fixed-width Box).
        onNodeWithTag(WindowTestTags.window("comm")).assertExists()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(CommTags.CHANNEL_LIST).fetchSemanticsNodes().isNotEmpty() ||
                onAllNodesWithTag(CommTags.TIMELINE).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
