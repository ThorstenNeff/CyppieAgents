package com.tneff.cyppieagents

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.agentmgmt.AgentManagementRepository
import com.tneff.cyppieagents.agentview.StubAgentSession
import com.tneff.cyppieagents.comm.CommApi
import com.tneff.cyppieagents.comm.StubCommLiveSource
import com.tneff.cyppieagents.crossproject.StubCrossProjectRepository
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
import com.tneff.cyppieagents.ui.ThemeMode
import com.tneff.cyppieagents.ui.ThemeTags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-268 R3 — the SHELL wiring of the theme toggle. The pure ThemeModeToggleTest proves the control drives
 * its callback; this proves the real [AgentShell] actually mounts it in the always-visible ProjectSwitcherBar
 * trailing slot AND threads `onThemeModeChange` back out — the "unwired integration step" guard (a byte-exact
 * toggle is worthless if the slot is never rendered / the callback never reaches App.kt's persist+recolour).
 *
 * Deterministic barriers only ([waitForIdle], never a wall-clock `waitUntil`): the toggle rides the trailing
 * slot, which composes with the bar (independent of the async project/comm loads), so it is present at idle.
 *
 * Mutation proof: drop the `trailing = { ThemeModeToggle(...) }` arg in AgentShell (or the `trailing()` call in
 * ProjectSwitcherBar) → `TOGGLE` never appears → both assertions RED.
 */
@OptIn(ExperimentalTestApi::class)
class AgentShellThemeToggleTest {

    private class OneProjectRepo : ProjectRepository {
        private val view = ProjectsView("default", listOf(Project("default", "Default")))
        override suspend fun list(): ProjectsView = view
        override suspend fun switchActive(projectId: String): ProjectsView = view
        override suspend fun create(request: CreateProjectRequest): Project = throw NotImplementedError()
        override suspend fun rename(id: String, request: RenameProjectRequest): Project = throw NotImplementedError()
        override suspend fun delete(id: String, deleteWorktrees: Boolean) = throw NotImplementedError()
    }

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
    private fun Shell(onThemeModeChange: (ThemeMode) -> Unit) {
        AgentShell(
            config = ShellConfig.dev().copy(operatorToken = "op-token"),
            sessionFactory = { StubAgentSession() },
            commApi = FakeCommApi(),
            commLiveSource = StubCommLiveSource(),
            eventsApi = StubEventsApi(),
            eventsLiveSource = StubEventsSource(),
            agentManagementRepository = FakeAgentMgmtRepo(),
            projectRepository = OneProjectRepo(),
            crossProjectRepository = StubCrossProjectRepository(),
            themeMode = ThemeMode.SYSTEM,
            onThemeModeChange = onThemeModeChange,
        )
    }

    @Test
    fun themeToggle_ridesSwitcherBar_andThreadsOnChange() = runComposeUiTest {
        var picked: ThemeMode? = null
        setContent { MaterialTheme { Box(Modifier.size(1200.dp, 900.dp)) { Shell { picked = it } } } }
        waitForIdle()

        // The toggle is mounted in the always-visible switcher bar (its trailing slot).
        onNodeWithTag(ThemeTags.TOGGLE).assertExists()
        assertNull(picked, "no selection until the user picks one")

        onNodeWithTag(ThemeTags.TOGGLE).performClick() // open the menu
        waitForIdle()
        onNodeWithTag(ThemeTags.item(ThemeMode.DARK)).performClick()
        waitForIdle()
        assertEquals(ThemeMode.DARK, picked, "picking Dark from the bar toggle threads onThemeModeChange(DARK)")
    }
}
