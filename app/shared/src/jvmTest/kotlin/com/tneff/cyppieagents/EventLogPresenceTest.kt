package com.tneff.cyppieagents

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.agentview.StubAgentSession
import com.tneff.cyppieagents.comm.CommApi
import com.tneff.cyppieagents.comm.StubCommLiveSource
import com.tneff.cyppieagents.eventlog.StubEventsApi
import com.tneff.cyppieagents.eventlog.StubEventsSource
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.window.WindowTestTags
import kotlin.test.Test

/**
 * CYP-41/42: the Event-Log windows are **operator-gated by omission** (EVENT-LOG-UI §5.6) — present
 * only when `cfg.operatorToken != null`, otherwise no window exists at all (QA asserts the absence,
 * not a "no access" placeholder). Hermetic: stub sessions + fakes, no network.
 */
@OptIn(ExperimentalTestApi::class)
class EventLogPresenceTest {

    private class FakeCommApi : CommApi {
        override suspend fun channels() = emptyList<Channel>()
        override suspend fun agents() = emptyList<Agent>()
        override suspend fun messages(channelId: String, since: Long?) = emptyList<Message>()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?) =
            Message("x", channelId, "operator", body, 1L)
    }

    @Composable
    private fun Shell(operatorToken: String?) {
        AgentShell(
            config = ShellConfig.dev().copy(operatorToken = operatorToken),
            sessionFactory = { StubAgentSession() },
            commApi = FakeCommApi(),
            commLiveSource = StubCommLiveSource(),
            eventsApi = StubEventsApi(),
            eventsLiveSource = StubEventsSource(),
            projectRepository = com.tneff.cyppieagents.project.StubProjectRepository(),
            crossProjectRepository = com.tneff.cyppieagents.crossproject.StubCrossProjectRepository(),
        )
    }

    @Test
    fun withOperatorToken_offersEventLogWindows() = runComposeUiTest {
        setContent { MaterialTheme { Shell("op-token") } }
        onNodeWithTag(WindowTestTags.HOST).assertExists() // GUARD: the shell really composed
        onNodeWithTag(WindowTestTags.window("eventlog")).assertExists()
        onNodeWithTag(WindowTestTags.window("eventtail")).assertExists()
    }

    @Test
    fun withoutOperatorToken_omitsEventLogWindows() = runComposeUiTest {
        setContent { MaterialTheme { Shell(null) } }

        // GUARD (load-bearing), DO NOT DELETE — CYP-340/CYP-352. An absence proves nothing on its own: a screen
        // that composed NOTHING satisfies both assertions below. Measured, in this very framework: a throwaway
        // test that rendered `Box {}` and asserted the two absences passed green. So the shell is proven present
        // first, together with a NON-operator window; only then does the missing Event-Log window mean
        // "operator-gated" rather than "nothing rendered".
        onNodeWithTag(WindowTestTags.HOST).assertExists()
        onNodeWithTag(WindowTestTags.window("comm")).assertExists()

        onNodeWithTag(WindowTestTags.window("eventlog")).assertDoesNotExist()
        onNodeWithTag(WindowTestTags.window("eventtail")).assertDoesNotExist()
    }
}
