package com.tneff.cyppieagents

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.agentmgmt.StubAgentManagementRepository
import com.tneff.cyppieagents.agentview.AgentViewTags
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
 * CYP-376 — **the agent window renders on the WASM target at all.**
 *
 * Web (Kotlin/Wasm) is the primary platform (spec 02 §1), yet every guard that renders the `AgentWindow`
 * composition — chrome floor, header controls, composer breakpoint — lives in `jvmTest`, because each **compares
 * measurements across separate renders** (natural vs floor, below vs above a breakpoint) and that needs values
 * held *outside* `runComposeUiTest`, which is not reliable on wasmJs (a `lateinit` never initializes there). That
 * boundary is real and accepted: **height/behaviour is jvm-only**.
 *
 * But `EventLogPresenceWasmTest` — the one wasm test that builds the real `AgentShell` — asserts the comm and
 * Event-Log windows and **never the agent window**, and it passes no `agentManagementRepository`, so the live
 * HTTP default returns no agents and the agent window is not even composed. The whole `AgentWindow` composition
 * was therefore **unrendered on the platform it primarily ships to** — a pre-existing gap CYP-369 surfaced, not
 * caused.
 *
 * This is the single-render half that *is* wasm-safe: seed one agent, render the real shell in headless Chrome,
 * and assert the agent window and its header exist. It proves the composition paints on wasm; it deliberately
 * asserts nothing about height — that stays with the jvm guards.
 *
 * Guard-the-guard (same discipline as `EventLogPresenceWasmTest`): [WindowTestTags.HOST] is asserted first, so a
 * shell that never composed cannot make the header assertion vacuously pass.
 */
@OptIn(ExperimentalTestApi::class)
class AgentWindowPresenceWasmTest {

    private class FakeCommApi : CommApi {
        override suspend fun channels() = emptyList<Channel>()
        override suspend fun agents() = emptyList<Agent>()
        override suspend fun messages(channelId: String, since: Long?) = emptyList<Message>()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?) =
            Message("x", channelId, "operator", body, 1L)
    }

    @Composable
    private fun Shell() {
        AgentShell(
            config = ShellConfig.dev().copy(operatorToken = "op-token"),
            sessionFactory = { StubAgentSession() },
            commApi = FakeCommApi(),
            commLiveSource = StubCommLiveSource(),
            eventsApi = StubEventsApi(),
            eventsLiveSource = StubEventsSource(),
            // The one difference from EventLogPresenceWasmTest: an in-memory agent roster (po/frontend/backend),
            // so the shell actually composes agent windows instead of defaulting to the server-less HTTP repo.
            agentManagementRepository = StubAgentManagementRepository(),
            projectRepository = com.tneff.cyppieagents.project.StubProjectRepository(),
            crossProjectRepository = com.tneff.cyppieagents.crossproject.StubCrossProjectRepository(),
        )
    }

    @Test
    fun agentWindow_rendersOnWasm() = runComposeUiTest {
        setContent { MaterialTheme { Shell() } }

        // GUARD (load-bearing): the shell really composed. Without it the assertions below could pass on nothing.
        onNodeWithTag(WindowTestTags.HOST).assertExists()

        // The agent window frame, and — the point — the AgentWindow composition inside it (its header). If the
        // composition threw or failed to lay out on wasm, this is where it shows.
        onNodeWithTag(WindowTestTags.window("po")).assertExists()
        onNodeWithTag(AgentViewTags.header("po"), useUnmergedTree = true).assertExists()
    }
}
