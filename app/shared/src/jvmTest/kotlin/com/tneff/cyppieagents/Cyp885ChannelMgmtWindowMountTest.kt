package com.tneff.cyppieagents

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.agentmgmt.StubAgentManagementRepository
import com.tneff.cyppieagents.agentview.StubAgentSession
import com.tneff.cyppieagents.comm.ChannelMgmtApi
import com.tneff.cyppieagents.comm.ChannelMgmtTags
import com.tneff.cyppieagents.comm.CommApi
import com.tneff.cyppieagents.comm.StubCommLiveSource
import com.tneff.cyppieagents.eventlog.StubEventsApi
import com.tneff.cyppieagents.eventlog.StubEventsSource
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.CreateChannelRequest
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.window.WindowTestTags
import kotlin.test.Test

/**
 * CYP-885 (OS-C window mount) — the channel-management window is **operator-gated by omission** (mirrors the
 * Event-Log windows / EventLogPresenceTest): present only for an operator, absent for a MEMBER. And when present it
 * mounts CLEANLY (its [ChannelManagementPanel] dispatches) WITHOUT displacing the comm window (the message loop +
 * OS-D picker keep working). Hermetic: stub sessions + fakes, no network.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp885ChannelMgmtWindowMountTest {

    private class FakeCommApi : CommApi {
        override suspend fun channels() =
            listOf(Channel("po-frontend", "PO ↔ Frontend", ChannelKind.HUB, listOf("po", "frontend")))
        override suspend fun agents() = listOf(
            Agent("po", "Product Owner", Role.PO, "po"),
            Agent("frontend", "Frontend", Role.WORKER, "frontend"),
        )
        override suspend fun messages(channelId: String, since: Long?) = emptyList<Message>()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?) =
            Message("x", channelId, "operator", body, 1L)
    }

    /** Never exercised by a mount test (no button click) — the window just needs a non-null port to render. */
    private class FakeChannelMgmtApi : ChannelMgmtApi {
        override suspend fun create(req: CreateChannelRequest): Channel = error("not exercised")
        override suspend fun rename(channelId: String, name: String): Channel = error("not exercised")
        override suspend fun archive(channelId: String) = Unit
    }

    @Composable
    private fun Shell(operatorToken: String?) {
        AgentShell(
            config = ShellConfig.dev().copy(operatorToken = operatorToken),
            sessionFactory = { StubAgentSession() },
            commApi = FakeCommApi(),
            commLiveSource = StubCommLiveSource(),
            channelMgmtApi = FakeChannelMgmtApi(),
            eventsApi = StubEventsApi(),
            eventsLiveSource = StubEventsSource(),
            agentManagementRepository = StubAgentManagementRepository(),
            projectRepository = com.tneff.cyppieagents.project.StubProjectRepository(),
            crossProjectRepository = com.tneff.cyppieagents.crossproject.StubCrossProjectRepository(),
        )
    }

    @Test
    fun withOperatorToken_mountsTheChannelMgmtWindow_besideComm() = runComposeUiTest {
        setContent { MaterialTheme { Shell("op-token") } }
        onNodeWithTag(WindowTestTags.HOST).assertExists() // the shell really composed
        // The operator channel-mgmt window mounts, and its panel content dispatches.
        onNodeWithTag(WindowTestTags.window("channelMgmt")).assertExists()
        onNodeWithTag(ChannelMgmtTags.ROOT, useUnmergedTree = true).assertExists()
        // …and it did NOT displace the comm window (the OS-A render + OS-D picker keep their home).
        onNodeWithTag(WindowTestTags.window("comm")).assertExists()
    }

    @Test
    fun withoutOperatorToken_omitsTheChannelMgmtWindow() = runComposeUiTest {
        setContent { MaterialTheme { Shell(null) } }
        // GUARD (load-bearing): an absence proves nothing unless the shell + a NON-operator window are present first.
        onNodeWithTag(WindowTestTags.HOST).assertExists()
        onNodeWithTag(WindowTestTags.window("comm")).assertExists()
        // Operator-gated by omission — a MEMBER's window set never contains it.
        onNodeWithTag(WindowTestTags.window("channelMgmt")).assertDoesNotExist()
    }
}
