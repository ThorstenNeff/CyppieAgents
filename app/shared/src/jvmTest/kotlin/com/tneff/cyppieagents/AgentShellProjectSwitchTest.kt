package com.tneff.cyppieagents

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.tneff.cyppieagents.agentmgmt.AgentManagementRepository
import com.tneff.cyppieagents.agentsettings.AgentSettingsTags
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
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.Project
import com.tneff.cyppieagents.model.ProjectsView
import com.tneff.cyppieagents.model.RenameProjectRequest
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.WorktreeFate
import com.tneff.cyppieagents.project.ProjectRepository
import com.tneff.cyppieagents.project.ProjectTags
import com.tneff.cyppieagents.window.WindowTestTags
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-246 (client-companion isolation zahn): a project switch must **re-scope** the desktop WITHOUT a reload —
 * the agent list re-fetches in the new scope and the derived agent windows rebuild. Before the fix the
 * agent-management VM was keyed by a CONSTANT `"agentMgmt"` → retained across the switch → `managedAgents`
 * stayed stale → the old project's windows persisted (the reported bug). The fix re-keys the verified-stale,
 * project-scoped data VMs on `activeProjectId`, so a switch hands back a FRESH instance → `init` re-fetches.
 *
 * These render tests drive the **real** [AgentShell] + [com.tneff.cyppieagents.project.ProjectSwitcherBar]
 * through Compose's UI-test infra. The fakes model Backend's paired server change: `/api/agents` carries no
 * client projectId — the SERVER resolves the active slice from the registry pointer — so the fake agent repo
 * reads the CURRENT active project from the shared holder that the fake project repo flips on `switchActive`
 * (fresh project = empty slice). Hermetic: stub sessions/events, no network.
 *
 * Mutation proof: revert the re-key (key back to the constant `AGENT_MGMT_WINDOW_ID`) → the VM is retained
 * across the switch → the agent windows never disappear → [projectSwitch_reScopesAgentWindows_zeroOnEmptyProject]
 * hangs on the "gone" wait and goes RED.
 */
@OptIn(ExperimentalTestApi::class)
class AgentShellProjectSwitchTest {

    /** Models the server's per-project partition: the active pointer + a fixed agent slice per project. */
    private class ScopedServer(
        val projects: List<Project> = listOf(Project("default", "Default"), Project("empty", "Empty")),
        private val byProject: Map<String, List<Agent>> = mapOf(
            "default" to listOf(
                Agent("po", "Product Owner", Role.PO, "po"),
                Agent("frontend", "Frontend", Role.WORKER, "frontend"),
            ),
            "empty" to emptyList(),
        ),
        initialActive: String = "default",
    ) {
        var active: String = initialActive
        fun view() = ProjectsView(active, projects)
        fun agentsForActive(): List<Agent> = byProject[active] ?: emptyList()
    }

    private class FakeProjectRepo(private val server: ScopedServer) : ProjectRepository {
        override suspend fun list(): ProjectsView = server.view()
        override suspend fun switchActive(projectId: String): ProjectsView {
            server.active = projectId // the registry pointer flips server-side; /api/agents follows
            return server.view()
        }
        override suspend fun create(request: CreateProjectRequest): Project = throw NotImplementedError()
        override suspend fun rename(id: String, request: RenameProjectRequest): Project = throw NotImplementedError()
        override suspend fun delete(id: String, deleteWorktrees: Boolean) = throw NotImplementedError()
    }

    /** `/api/agents` with NO client projectId — returns whatever slice the server's active pointer resolves to. */
    private class FakeAgentMgmtRepo(private val server: ScopedServer) : AgentManagementRepository {
        override suspend fun list(): List<Agent> = server.agentsForActive()
        override suspend fun detail(id: String): AgentDetail = throw NotImplementedError()
        override suspend fun add(spec: com.tneff.cyppieagents.model.NewAgentSpec): com.tneff.cyppieagents.model.CreatedAgent = throw NotImplementedError()
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
    private fun Shell(server: ScopedServer) {
        // Operator context (break-glass token → isOperator) so switching is enabled; MEMBER tier keeps the
        // roster (workspace-scoped) out of the picture. Event stubs keep the operator-only windows hermetic.
        AgentShell(
            config = ShellConfig.dev().copy(operatorToken = "op-token"),
            sessionFactory = { StubAgentSession() },
            commApi = FakeCommApi(),
            commLiveSource = StubCommLiveSource(),
            eventsApi = StubEventsApi(),
            eventsLiveSource = StubEventsSource(),
            agentManagementRepository = FakeAgentMgmtRepo(server),
            projectRepository = FakeProjectRepo(server),
            crossProjectRepository = com.tneff.cyppieagents.crossproject.StubCrossProjectRepository(),
        )
    }

    @Test
    fun projectSwitch_reScopesAgentWindows_zeroOnEmptyProject() = runComposeUiTest {
        val server = ScopedServer()
        setContent { MaterialTheme { Shell(server) } }

        // Initial scope (default project) → one agent window per agent in the slice.
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentViewTags.stream("po")).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(AgentViewTags.stream("po")).assertExists()
        onNodeWithTag(AgentViewTags.stream("frontend")).assertExists()

        // Switch to the (empty) project via the real switcher bar.
        onNodeWithTag(ProjectTags.MENU).performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(ProjectTags.item("empty")).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(ProjectTags.item("empty")).performClick()

        // The switched-to project is empty → the agent windows are gone (re-key → re-fetch → managedAgents
        // empties → windowKey changes → syncWindows prunes them). The static comm window is unaffected.
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentViewTags.stream("po")).fetchSemanticsNodes().isEmpty()
        }
        onNodeWithTag(AgentViewTags.stream("po")).assertDoesNotExist()
        onNodeWithTag(AgentViewTags.stream("frontend")).assertDoesNotExist()
        onNodeWithTag(WindowTestTags.window("comm")).assertExists()

        // Switch BACK to default → the agent windows return: proves a real re-instance (a fresh in-scope
        // fetch), not a one-way teardown.
        onNodeWithTag(ProjectTags.MENU).performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(ProjectTags.item("default")).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(ProjectTags.item("default")).performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentViewTags.stream("po")).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(AgentViewTags.stream("po")).assertExists()
        onNodeWithTag(AgentViewTags.stream("frontend")).assertExists()
    }

    @Test
    fun freshMount_inSwitchedScope_showsOnlyThatScopesAgents_reloadScenario() = runComposeUiTest {
        // Reload scenario: the switch already happened server-side and the page was reloaded → a fresh shell
        // mounts against the switched (empty) scope. The client always re-fetches on mount and never persists
        // windows independently of the agent list, so an empty scope shows 0 agent windows from the start —
        // while a fresh mount against the default scope (below) does show them (non-vacuous contrast).
        val empty = ScopedServer().apply { active = "empty" }
        setContent { MaterialTheme { Shell(empty) } }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(WindowTestTags.window("comm")).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(AgentViewTags.stream("po")).assertDoesNotExist()
        onNodeWithTag(AgentViewTags.stream("frontend")).assertDoesNotExist()
    }

    @Test
    fun freshMount_inDefaultScope_showsAgents_contrastForReloadScenario() = runComposeUiTest {
        // The non-vacuous other half of the reload scenario: a fresh mount against a populated scope DOES show
        // the agents (so the empty-scope assertion above isn't trivially always-empty).
        val default = ScopedServer() // active = "default"
        setContent { MaterialTheme { Shell(default) } }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentViewTags.stream("po")).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(AgentViewTags.stream("po")).assertExists()
        onNodeWithTag(AgentViewTags.stream("frontend")).assertExists()
    }

    /**
     * CYP-246 (PO-Assistent-found 10th VM): the per-agent settings overlay VM must ALSO re-key on activeProjectId.
     * Two projects reuse the agent id "backend" with different names. Opening settings in alpha, switching to beta,
     * then reopening settings for the SAME id must show BETA's agent — a fresh VM — not alpha's retained one. A
     * stale overlay would not only show the wrong name but a save from it would land alpha's values in beta's
     * override (the repo is project-agnostic; the server resolves scope to the now-active project) — a data leak.
     *
     * Mutation proof: revert the overlay key to the bare `"agentSettings-$sid"` → the retained alpha VM comes back
     * on reopen → the field reads "Backend Alpha" → RED.
     */
    @Test
    fun perAgentSettingsOverlay_reopenAfterSwitch_showsNewProjectsAgent_notRetainedStale() = runComposeUiTest {
        val server = ScopedServer(
            projects = listOf(Project("alpha", "Alpha"), Project("beta", "Beta")),
            byProject = mapOf(
                "alpha" to listOf(Agent("backend", "Backend Alpha", Role.WORKER, "backend")),
                "beta" to listOf(Agent("backend", "Backend Beta", Role.WORKER, "backend")),
            ),
            initialActive = "alpha",
        )
        // A large canvas so the windows tile WITHOUT overlap — agent windows draw under the system windows, so on
        // a cramped canvas the backend ⋮ settings button would be occluded and unclickable.
        setContent { MaterialTheme { Box(Modifier.size(1500.dp, 1000.dp)) { Shell(server) } } }

        // The name field's editable value (a failed detail() keeps the VM's initialName = the agent's list name,
        // which differs per project — so the field reads whichever project's VM is behind the overlay).
        fun nameField(): String = onNodeWithTag(AgentSettingsTags.NAME_INPUT, useUnmergedTree = true)
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text ?: ""

        // Open the "backend" settings overlay in alpha (⋮ button) → shows alpha's name.
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(WindowTestTags.settings("backend")).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(WindowTestTags.settings("backend")).performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentSettingsTags.NAME_INPUT).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("Backend Alpha", nameField())

        // Close the overlay as the user would (the PO repro is open→close→switch→reopen). Its retained VM stays
        // in the ViewModelStore — reopening it after a switch is exactly what surfaces the stale-scope bug. (The
        // modal dialog's scrim also blocks the project-bar MENU, so it MUST be dismissed before switching.)
        onNodeWithTag(AgentSettingsTags.CANCEL).performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentSettingsTags.NAME_INPUT).fetchSemanticsNodes().isEmpty()
        }

        // Switch to beta.
        onNodeWithTag(ProjectTags.MENU).performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(ProjectTags.item("beta")).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(ProjectTags.item("beta")).performClick()
        // The backend window is present in beta too (same id, different name) — wait for the switch to settle.
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentViewTags.stream("backend")).fetchSemanticsNodes().isNotEmpty()
        }

        // Reopen the SAME-id overlay in beta → a FRESH VM shows beta's name, not alpha's retained one.
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(WindowTestTags.settings("backend")).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(WindowTestTags.settings("backend")).performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AgentSettingsTags.NAME_INPUT).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("Backend Beta", nameField())
    }
}
