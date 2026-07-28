package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.agentmgmt.AgentManagementPanel
import com.tneff.cyppieagents.agentmgmt.AgentManagementViewModel
import com.tneff.cyppieagents.agentmgmt.AgentMgmtTags
import com.tneff.cyppieagents.agentmgmt.StubAgentManagementRepository
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test

/**
 * CYP-907 (BYOA-M1 B3) — the remote-origin indicator surfaces `Agent.remote` on **both** axes: PRIMARY on the roster
 * row (chip), SECONDARY on the agent title-bar (marker). Present iff `remote == true`; absent for a local agent
 * (incl. a locally-degraded MCP connector, which is `remote == false`). Dormant until the Backend populates the field.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp907RemoteIndicatorTest {

    // --- Roster (primary): the AgentManagementPanel row chip. ---

    @Test
    fun rosterChip_presentForRemote_absentForLocal() = runComposeUiTest {
        val repo = StubAgentManagementRepository(
            listOf(
                Agent("r1", "Remote One", Role.WORKER, "r1", runState = AgentRunState.STOPPED, remote = true),
                Agent("l1", "Local One", Role.WORKER, "l1", runState = AgentRunState.STOPPED, remote = false),
            ),
        )
        val vm = AgentManagementViewModel(repo, editable = true, scope = CoroutineScope(Dispatchers.Unconfined))
        setContent { MaterialTheme { AgentManagementPanel(vm) } }
        waitForIdle()
        // MUT: chip not gated on `agent.remote` (always shown / never shown) → one of these reddens.
        onNodeWithTag(AgentMgmtTags.itemRemote("r1")).assertExists()
        onNodeWithTag(AgentMgmtTags.itemRemote("l1")).assertDoesNotExist()
    }

    // --- Title-bar (secondary): the AgentWindow header marker. ---

    private fun emptySession() = object : AgentSession {
        override val events: Flow<AgentEvent> = emptyFlow()
        override fun sendMessage(text: String) {}
    }

    private fun headerVm(): AgentViewModel {
        val src = StubAgentLifecycle(mapOf("be" to AgentLifecycleState.STOPPED))
        return AgentViewModel(emptySession(), agentId = "be", lifecycle = src, lifecycleSource = src, canControl = true)
    }

    @Test
    fun titleMarker_presentWhenRemote() = runComposeUiTest {
        setContent { MaterialTheme { AgentWindow(agentId = "be", viewModel = remember { headerVm() }, remote = true) } }
        onNodeWithTag(AgentViewTags.remoteMarker("be")).assertExists()
    }

    @Test
    fun titleMarker_absentWhenLocal() = runComposeUiTest {
        setContent { MaterialTheme { AgentWindow(agentId = "be", viewModel = remember { headerVm() }, remote = false) } }
        onNodeWithTag(AgentViewTags.remoteMarker("be")).assertDoesNotExist()
    }
}
