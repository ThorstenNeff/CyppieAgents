package com.tneff.cyppieagents

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.agentmgmt.AgentManagementRepository
import com.tneff.cyppieagents.agentview.StubAgentSession
import com.tneff.cyppieagents.comm.CommApi
import com.tneff.cyppieagents.comm.StubCommLiveSource
import com.tneff.cyppieagents.eventlog.StubEventsApi
import com.tneff.cyppieagents.eventlog.StubEventsSource
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentDetail
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Project
import com.tneff.cyppieagents.model.ProjectsView
import com.tneff.cyppieagents.model.RenameProjectRequest
import com.tneff.cyppieagents.model.WorktreeFate
import com.tneff.cyppieagents.project.ProjectRepository
import com.tneff.cyppieagents.project.ProjectTags
import com.tneff.cyppieagents.project.ProjectVmStoreManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * CYP-249 — the SHELL wiring of the per-project ViewModelStore LRU (K=3), mirroring the server's runtime cap. The
 * pure [com.tneff.cyppieagents.project.ProjectVmStoreManagerTest] pins the LRU logic + socket-teardown proxy; this
 * proves the real [AgentShell] actually drives it: get-or-create the active project's store each switch, and after
 * the switch commits promote it + evict the least-recently-used beyond K. Injects the manager to observe warm ids.
 *
 * Mutation proof: drop the shell's `LaunchedEffect { projectStores.noteActive(...) }` (or make `ProjectVmStoreManager`
 * un-remembered so it resets each recomposition) → the warm set is never bounded (grows past 3 / resets) → the
 * `== [p2,p3,p4]` assertion RED.
 */
@OptIn(ExperimentalTestApi::class)
class AgentShellProjectLruTest {

    // The boot project id matches ProjectViewModel's seeded default ("default") so no transient pre-reload phantom
    // store is created — the LRU then tracks exactly the real active-project sequence.
    private class ScopedServer(initialActive: String = "default") {
        val projects = listOf(
            Project("default", "Default"), Project("beta", "Beta"), Project("gamma", "Gamma"), Project("delta", "Delta"),
        )
        var active: String = initialActive
        fun view() = ProjectsView(active, projects)
    }

    private class FakeProjectRepo(private val server: ScopedServer) : ProjectRepository {
        override suspend fun list(): ProjectsView = server.view()
        override suspend fun switchActive(projectId: String): ProjectsView {
            server.active = projectId
            return server.view()
        }
        override suspend fun create(request: CreateProjectRequest): Project = throw NotImplementedError()
        override suspend fun rename(id: String, request: RenameProjectRequest): Project = throw NotImplementedError()
        override suspend fun delete(id: String, deleteWorktrees: Boolean) = throw NotImplementedError()
    }

    /** Every project is empty of agents — the LRU tracks the ACTIVE-project sequence, independent of agent count. */
    private class FakeAgentMgmtRepo : AgentManagementRepository {
        override suspend fun list(): List<Agent> = emptyList()
        override suspend fun detail(id: String): AgentDetail = throw NotImplementedError()
        override suspend fun add(spec: NewAgentSpec): Agent = throw NotImplementedError()
        override suspend fun edit(id: String, edit: AgentEdit): Agent = throw NotImplementedError()
        override suspend fun remove(id: String, worktree: WorktreeFate) = throw NotImplementedError()
    }

    private class FakeCommApi : CommApi {
        override suspend fun channels() = emptyList<Channel>()
        override suspend fun agents() = emptyList<Agent>()
        override suspend fun messages(channelId: String, since: Long?) = emptyList<Message>()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?) =
            Message("x", channelId, "operator", body, 1L)
    }

    @Composable
    private fun Shell(server: ScopedServer, stores: ProjectVmStoreManager) {
        AgentShell(
            config = ShellConfig.dev().copy(operatorToken = "op-token"),
            sessionFactory = { StubAgentSession() },
            commApi = FakeCommApi(),
            commLiveSource = StubCommLiveSource(),
            eventsApi = StubEventsApi(),
            eventsLiveSource = StubEventsSource(),
            agentManagementRepository = FakeAgentMgmtRepo(),
            projectRepository = FakeProjectRepo(server),
            crossProjectRepository = com.tneff.cyppieagents.crossproject.StubCrossProjectRepository(),
            projectVmStores = stores,
        )
    }

    @Test
    fun switchingThroughMoreThanKProjects_boundsWarmSetToK_evictingLeastRecentlyUsed() = runComposeUiTest {
        val server = ScopedServer()
        val stores = ProjectVmStoreManager() // K = 3 default
        setContent { MaterialTheme { Box(Modifier.size(1500.dp, 1000.dp)) { Shell(server, stores) } } }

        fun switchTo(pid: String) {
            onNodeWithTag(ProjectTags.MENU).performClick()
            waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(ProjectTags.item(pid)).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(ProjectTags.item(pid)).performClick()
            waitUntil(timeoutMillis = 5_000L) { stores.liveProjectIds().lastOrNull() == pid } // switch committed (MRU)
        }

        // Mount settles on the boot project → it is the sole warm project.
        waitUntil(timeoutMillis = 5_000L) { stores.liveProjectIds() == listOf("default") }

        switchTo("beta")
        switchTo("gamma")
        // Three distinct hot projects → all within K=3, none evicted yet (non-vacuous: eviction happens at the 4th).
        assertEquals(listOf("default", "beta", "gamma"), stores.liveProjectIds(), "active + 2 recent all warm at K=3")

        switchTo("delta")
        // The fourth distinct project evicts the least-recently-used (default) → its store cleared → sockets closed.
        assertEquals(listOf("beta", "gamma", "delta"), stores.liveProjectIds(), "warm set bounded to K=3, LRU evicted")
        assertFalse(stores.liveProjectIds().contains("default"), "the least-recently-hot project is disposed")
    }

    @Test
    fun switchBackToWarmProject_keepsItWithinK_noUnboundedGrowth() = runComposeUiTest {
        val server = ScopedServer()
        val stores = ProjectVmStoreManager()
        setContent { MaterialTheme { Box(Modifier.size(1500.dp, 1000.dp)) { Shell(server, stores) } } }

        fun switchTo(pid: String) {
            onNodeWithTag(ProjectTags.MENU).performClick()
            waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(ProjectTags.item(pid)).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(ProjectTags.item(pid)).performClick()
            waitUntil(timeoutMillis = 5_000L) { stores.liveProjectIds().lastOrNull() == pid }
        }

        waitUntil(timeoutMillis = 5_000L) { stores.liveProjectIds() == listOf("default") }
        switchTo("beta")
        switchTo("default") // switch BACK to a warm project (within K) — a cheap re-entry, no growth
        // default is promoted to MRU; still exactly {beta, default} warm — bounded, no accumulation.
        assertEquals(listOf("beta", "default"), stores.liveProjectIds(), "a warm switch-back reuses, promotes, never grows the set")
    }
}
