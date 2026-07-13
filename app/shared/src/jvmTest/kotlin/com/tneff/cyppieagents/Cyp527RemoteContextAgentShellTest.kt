package com.tneff.cyppieagents

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
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
import com.tneff.cyppieagents.project.ProjectTags
import com.tneff.cyppieagents.project.StubProjectRepository
import com.tneff.cyppieagents.workspace.WorkspaceTags
import kotlin.test.Test

/**
 * CYP-527 — the **AgentShell integration tooth** the Reviewer flagged: the derivation + present-iff teeth cover
 * `remoteContextHubName` and the ProjectSwitcherBar slot, but NOT AgentShell's own `remoteContext?.let {
 * RemoteContextBanner(hubName = it) }` wiring — a mutation there stayed silent-green. This drives the **real
 * AgentShell** (hermetic stubs, like [AgentShellRenderTest]) and pins: `remoteContext` non-null ⇒ the
 * `workspace.remoteContext` banner mounts; `null` (the LOCAL / not-connected path) ⇒ it is absent.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp527RemoteContextAgentShellTest {

    private class FakeCommApi : CommApi {
        override suspend fun channels() =
            listOf(Channel("po-frontend", "PO ↔ Frontend", ChannelKind.HUB, listOf("po", "frontend")))
        override suspend fun agents() = listOf(Agent("po", "Product Owner", Role.PO, "po"))
        override suspend fun messages(channelId: String, since: Long?) = emptyList<Message>()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?) =
            Message("x", channelId, "operator", body, 1L)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.shell(remoteContext: String?) = setContent {
        MaterialTheme {
            AgentShell(
                sessionFactory = { StubAgentSession() },
                commApi = FakeCommApi(),
                commLiveSource = StubCommLiveSource(),
                agentManagementRepository = StubAgentManagementRepository(),
                projectRepository = StubProjectRepository(),
                crossProjectRepository = StubCrossProjectRepository(),
                remoteContext = remoteContext,
            )
        }
    }

    @Test
    fun agentShell_mountsBanner_whenRemoteContextNonNull() = runComposeUiTest {
        shell("Mein Hub")
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(ProjectTags.BAR).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(WorkspaceTags.REMOTE_CONTEXT, useUnmergedTree = true).assertExists()
    }

    @Test
    fun agentShell_noBanner_whenRemoteContextNull_localGuard() = runComposeUiTest {
        shell(null)
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(ProjectTags.BAR).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(WorkspaceTags.REMOTE_CONTEXT, useUnmergedTree = true).assertDoesNotExist()
    }
}
