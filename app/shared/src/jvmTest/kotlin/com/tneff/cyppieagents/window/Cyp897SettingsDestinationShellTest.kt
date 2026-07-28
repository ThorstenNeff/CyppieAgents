package com.tneff.cyppieagents.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.AgentShell
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
import com.tneff.cyppieagents.project.StubProjectRepository
import com.tneff.cyppieagents.settings.SettingsTags
import kotlin.test.Test

/**
 * CYP-897 (Nav-Rail S4) — the settings destination, on the REAL [AgentShell]: selecting it renders the actual
 * [com.tneff.cyppieagents.settings.SettingsPanel] surface ([SettingsTags.PANEL]) in the content pane — NOT a
 * placeholder — and one-active holds (the canvas leaves composition while Settings is active).
 *
 * **MUT:** revert the settings destination to a placeholder (drop `SettingsPanel(settingsVm)`) → `SettingsTags.PANEL`
 * absent under Settings → RED.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp897SettingsDestinationShellTest {

    private class FakeCommApi : CommApi {
        override suspend fun channels() =
            listOf(Channel("po-frontend", "PO ↔ Frontend", ChannelKind.HUB, listOf("po", "frontend")))
        override suspend fun agents() = listOf(Agent("po", "Product Owner", Role.PO, "po"))
        override suspend fun messages(channelId: String, since: Long?) = emptyList<Message>()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?) =
            Message("x", channelId, "operator", body, 1L)
    }

    @Test
    fun settingsDestination_rendersRealSettingsSurface_notPlaceholder() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.size(1200.dp, 840.dp)) { // rail-eligible
                    AgentShell(
                        sessionFactory = { StubAgentSession() },
                        commApi = FakeCommApi(),
                        commLiveSource = StubCommLiveSource(),
                        agentManagementRepository = StubAgentManagementRepository(),
                        projectRepository = StubProjectRepository(),
                        crossProjectRepository = StubCrossProjectRepository(),
                    )
                }
            }
        }

        onNodeWithTag(NavRailTags.RAIL).assertExists()
        // On Canvas: a canvas window is present, the settings panel is not the active pane content.
        onNodeWithTag(WindowTestTags.window("po")).assertExists()

        // Switch to the Settings destination → the REAL settings surface renders; canvas leaves (one-active).
        onNodeWithTag(NavRailTags.item(NavDestination.Settings)).performClick()
        waitForIdle()
        onNodeWithTag(NavRailTags.PANE_SETTINGS).assertExists()
        onNodeWithTag(SettingsTags.PANEL).assertExists() // the actual SettingsPanel, not a placeholder box
        onNodeWithTag(WindowTestTags.window("po")).assertDoesNotExist() // one-active
    }
}
