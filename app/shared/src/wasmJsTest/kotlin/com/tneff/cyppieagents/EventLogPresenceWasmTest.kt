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
 * CYP-352 — **the operator-gating security claim, proven in a real browser.**
 *
 * This test replaced `maestro/eventlog-presence-web.yaml` (removed in CYP-352) — and it had to exist, green and
 * mutation-proven, *before* that file was allowed to disappear. The Maestro flow could never have proven
 * anything: Maestro drives Chromium through Selenium and reads the DOM, while Compose on wasmJs paints into a
 * canvas. Measured: the same Maestro sees a plain HTML page and sees nothing at all of this app — neither
 * testTag nor visible text, with 30 s of patience. Background: `docs/QA-WEB-FLOW-ROLLBACK-CYP-352.md`.
 *
 * `runComposeUiTest` under `wasmJsBrowserTest` has none of that problem: it runs in headless Chrome (the CYP-216
 * gate) against the real Compose semantics tree, so `onNodeWithTag` addresses what the operator would see.
 *
 * **The guard is the point.** [withoutOperatorToken_omitsEventLogWindows] asserts an *absence*, and an absence
 * proves nothing on its own — an app that never composed has the same absence. So the shell is first proven
 * present (`window.host`) together with a NON-operator window (`window.comm`). Only then does the missing
 * Event-Log window mean "operator-gated" rather than "nothing rendered".
 *
 * (The JVM twin, `EventLogPresenceTest`, asserts the same omission **without** that guard. Same vacuum class,
 * different tool — worth fixing there too, but this is the browser-side proof the rollback depends on.)
 */
@OptIn(ExperimentalTestApi::class)
class EventLogPresenceWasmTest {

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

    /** The positive half: with an operator token the two Event-Log windows ARE offered. */
    @Test
    fun withOperatorToken_offersEventLogWindows() = runComposeUiTest {
        setContent { MaterialTheme { Shell("op-token") } }

        // GUARD (load-bearing): the shell really composed.
        onNodeWithTag(WindowTestTags.HOST).assertExists()

        onNodeWithTag(WindowTestTags.window("eventlog")).assertExists()
        onNodeWithTag(WindowTestTags.window("eventtail")).assertExists()
    }

    /** The security claim: without an operator token the windows are NOT offered — omission, not a dead window. */
    @Test
    fun withoutOperatorToken_omitsEventLogWindows() = runComposeUiTest {
        setContent { MaterialTheme { Shell(null) } }

        // GUARD (load-bearing), DO NOT DELETE. Without these two, the assertions below pass on an app that never
        // composed — the exact failure that made the Maestro flow worthless (CYP-340/CYP-352).
        onNodeWithTag(WindowTestTags.HOST).assertExists()
        onNodeWithTag(WindowTestTags.window("comm")).assertExists()

        onNodeWithTag(WindowTestTags.window("eventlog")).assertDoesNotExist()
        onNodeWithTag(WindowTestTags.window("eventtail")).assertDoesNotExist()
    }
}
