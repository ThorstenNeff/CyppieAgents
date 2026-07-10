package com.tneff.cyppieagents

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.agentmgmt.AgentManagementRepository
import com.tneff.cyppieagents.agentmgmt.StubAgentManagementRepository
import com.tneff.cyppieagents.agentview.AgentViewTags
import com.tneff.cyppieagents.agentview.StubAgentSession
import com.tneff.cyppieagents.comm.CommApi
import com.tneff.cyppieagents.comm.StubCommLiveSource
import com.tneff.cyppieagents.eventlog.StubEventsApi
import com.tneff.cyppieagents.eventlog.StubEventsSource
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.Role
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test

/**
 * CYP-338 — the *rendered* half of the fix. `WindowSyncTest` proves the geometry (a late content window gets
 * `TILED_CONTENT_WINDOW_MIN_HEIGHT`); this proves the geometry is actually **enough**: at that height an agent
 * window still shows its transcript AND its composer. The composer is not optional — a window without it is
 * not an agent window.
 *
 * Why it is not redundant: the collapse was never a wrong number, it was a number too small for the chrome an
 * agent window stacks around its `weight(1f)` transcript. A pure-geometry assertion cannot see that — it would
 * stay green if the floor were one dp short. This renders the real composition (title bar + header + transcript
 * + composer) and fails if the chrome ever outgrows the floor.
 *
 * **The agent list is gated on purpose.** Whether agents reach the shell before or after the first layout is a
 * race (a suspending HTTP fetch loses it, an instant stub can win it), and the two outcomes take *different*
 * code paths: winning → `resetTo`/`tile`, losing → `syncWindows`/`placeNewWindow`. CYP-338 fixes the second.
 * The gate forces the second deterministically, so this test cannot silently start measuring the other path.
 *
 * The `tile` path is floored at the composer invariant rather than the preferred minimum (spec §5), so a
 * crowded grid yields a short-but-usable agent window; `WindowReducerTest` pins that.
 *
 * Mutation-provable (spec §7.5): `TILED_CONTENT_WINDOW_MIN_HEIGHT = 120` → the composer is not displayed → RED.
 */
@OptIn(ExperimentalTestApi::class)
class AgentWindowMinHeightTest {

    private class FakeCommApi : CommApi {
        override suspend fun channels() = emptyList<Channel>()
        override suspend fun agents() = emptyList<Agent>()
        override suspend fun messages(channelId: String, since: Long?) = emptyList<Message>()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?) =
            Message("x", channelId, "operator", body, 1L)
    }

    private val agents = listOf(
        Agent("po", "Product Owner", Role.PO, "po", AgentRunState.RUNNING),
        Agent("backend", "Backend", Role.WORKER, "backend", AgentRunState.RUNNING),
    )

    /** Holds `list()` until the gate completes, so the first layout provably happens with zero agents. */
    private class GatedAgentManagementRepository(
        private val delegate: StubAgentManagementRepository,
        private val gate: CompletableDeferred<Unit>,
    ) : AgentManagementRepository by delegate {
        override suspend fun list(): List<Agent> {
            gate.await()
            return delegate.list()
        }
    }

    @Composable
    private fun Shell(repo: AgentManagementRepository) {
        AgentShell(
            config = ShellConfig.dev().copy(operatorToken = "op-token"),
            sessionFactory = { StubAgentSession() },
            commApi = FakeCommApi(),
            commLiveSource = StubCommLiveSource(),
            eventsApi = StubEventsApi(),
            eventsLiveSource = StubEventsSource(),
            agentManagementRepository = repo,
            projectRepository = com.tneff.cyppieagents.project.StubProjectRepository(),
            crossProjectRepository = com.tneff.cyppieagents.crossproject.StubCrossProjectRepository(),
        )
    }

    @Test
    fun lateArrivingAgentWindow_showsTranscriptAndComposer() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val repo = GatedAgentManagementRepository(StubAgentManagementRepository(agents), gate)
        setContent { MaterialTheme { Shell(repo) } }

        // First layout runs with the system windows only (tile). No agent window exists yet.
        waitForIdle()
        onNodeWithTag(AgentViewTags.input("po")).assertDoesNotExist()

        // Now the agents arrive → syncWindows → placeNewWindow, the path CYP-338 fixes.
        gate.complete(Unit)
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(AgentViewTags.input("po")).fetchSemanticsNodes().isNotEmpty()
        }

        for (agentId in listOf("po", "backend")) {
            onNodeWithTag(AgentViewTags.stream(agentId)).assertIsDisplayed()
            // The composer is the tag the collapse silently dropped — and the one `smoke-web.yaml` asserts.
            onNodeWithTag(AgentViewTags.input(agentId)).assertIsDisplayed()
        }
    }
}
