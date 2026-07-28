package com.tneff.cyppieagents

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.agentmgmt.AgentManagementRepository
import com.tneff.cyppieagents.agentview.AgentViewTags
import com.tneff.cyppieagents.agentview.StubAgentSession
import com.tneff.cyppieagents.comm.CommApi
import com.tneff.cyppieagents.comm.StubCommLiveSource
import com.tneff.cyppieagents.eventlog.StubEventsApi
import com.tneff.cyppieagents.eventlog.StubEventsSource
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentDetail
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Project
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.WorktreeFate
import com.tneff.cyppieagents.project.StubProjectRepository
import com.tneff.cyppieagents.window.WindowTestTags
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test

/**
 * CYP-270 — the CYP-250 desktop empty-state ("add your first agent") must NOT flash in a NON-empty project during the
 * agent-list load window. A project switch (or the initial mount) re-keys `agentMgmtVm` to a fresh instance that
 * starts `loading=true` with `agents=[]`; a bare `managedAgents.isEmpty()` showed the CTA for the ~re-fetch window,
 * reading as data loss on the freshly-live isolation feature. Fix: `agentsEmpty = !agentMgmtState.loading &&
 * managedAgents.isEmpty()` — the per-switch analogue of CYP-267's initial-mount loading-gate.
 *
 * Mutation: revert to `agentsEmpty = managedAgents.isEmpty()` → the empty-state flashes while the gated load is in
 * flight → the "absent during load" assertion RED.
 */
@OptIn(ExperimentalTestApi::class)
class AgentShellEmptyStateFlashTest {

    private class FakeCommApi : CommApi {
        override suspend fun channels() = emptyList<Channel>()
        override suspend fun agents() = emptyList<Agent>()
        override suspend fun messages(channelId: String, since: Long?) = emptyList<Message>()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?) =
            Message("x", channelId, "operator", body, 1L)
    }

    /** `/api/agents` held open on [gate] — models the load window where `loading=true` and `agents=[]`. */
    private class GatedAgentMgmtRepo(
        private val agents: List<Agent>,
        private val gate: CompletableDeferred<Unit>,
    ) : AgentManagementRepository {
        override suspend fun list(): List<Agent> { gate.await(); return agents }
        override suspend fun detail(id: String): AgentDetail = throw NotImplementedError()
        override suspend fun add(spec: NewAgentSpec): com.tneff.cyppieagents.model.CreatedAgent = throw NotImplementedError()
        override suspend fun edit(id: String, edit: AgentEdit): Agent = throw NotImplementedError()
        override suspend fun remove(id: String, worktree: WorktreeFate) = throw NotImplementedError()
    }

    /** `/api/agents` that resolves IMMEDIATELY to [agents] (load complete). */
    private class ImmediateAgentMgmtRepo(private val agents: List<Agent>) : AgentManagementRepository {
        override suspend fun list(): List<Agent> = agents
        override suspend fun detail(id: String): AgentDetail = throw NotImplementedError()
        override suspend fun add(spec: NewAgentSpec): com.tneff.cyppieagents.model.CreatedAgent = throw NotImplementedError()
        override suspend fun edit(id: String, edit: AgentEdit): Agent = throw NotImplementedError()
        override suspend fun remove(id: String, worktree: WorktreeFate) = throw NotImplementedError()
    }

    @androidx.compose.runtime.Composable
    private fun Shell(agentRepo: AgentManagementRepository) {
        AgentShell(
            config = ShellConfig.dev().copy(operatorToken = "op-token"),
            sessionFactory = { StubAgentSession() },
            commApi = FakeCommApi(),
            commLiveSource = StubCommLiveSource(),
            eventsApi = StubEventsApi(),
            eventsLiveSource = StubEventsSource(),
            agentManagementRepository = agentRepo,
            projectRepository = StubProjectRepository(listOf(Project("default", "Default")), "default"),
            crossProjectRepository = com.tneff.cyppieagents.crossproject.StubCrossProjectRepository(),
        )
    }

    @Test
    fun emptyState_doesNotFlash_whileAgentListLoads_inANonEmptyProject() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val agentRepo = GatedAgentMgmtRepo(listOf(Agent("po", "Product Owner", Role.PO, "po")), gate)
        setContent { MaterialTheme { Box(Modifier.size(1500.dp, 1000.dp)) { Shell(agentRepo) } } }

        // The static desktop (tool windows) renders immediately, while the agent list is STILL loading (gated).
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(WindowTestTags.window("comm")).fetchSemanticsNodes().isNotEmpty() }
        // During the load window (loading=true, agents=[]) the empty-state must NOT flash — the project is non-empty.
        onNodeWithTag(WindowTestTags.EMPTY).assertDoesNotExist()

        // Release the load → the non-empty project's agent window appears; still no empty-state.
        runOnUiThread { gate.complete(Unit) }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AgentViewTags.stream("po")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(WindowTestTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun emptyState_shows_forAGenuinelyEmptyProject_afterLoad() = runComposeUiTest {
        // Non-vacuous contrast: a project that loads (loading=false) with 0 agents DOES show the empty-state — the
        // fix suppresses only the load-window flash, never the real empty-project onboarding.
        setContent { MaterialTheme { Box(Modifier.size(1500.dp, 1000.dp)) { Shell(ImmediateAgentMgmtRepo(emptyList())) } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(WindowTestTags.EMPTY).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(WindowTestTags.EMPTY).assertExists()
    }
}
