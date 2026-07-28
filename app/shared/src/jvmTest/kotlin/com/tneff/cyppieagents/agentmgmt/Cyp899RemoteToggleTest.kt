package com.tneff.cyppieagents.agentmgmt

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentDetail
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.WorktreeFate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-899 (BYOA-M1 B1) — the remote/BYOA create toggle feeds `NewAgentSpec.remote` at `confirmAdd()`. Pure app-layer
 * (the contract field already exists server-side). Load-bearing tooth: toggle-on → `remote=true` sent, off → false.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp899RemoteToggleTest {

    /** Captures the [NewAgentSpec] the VM sends on add — the observable the tooth asserts against. */
    private class CapturingRepo : AgentManagementRepository {
        var lastSpec: NewAgentSpec? = null
        override suspend fun list(): List<Agent> = emptyList()
        override suspend fun detail(id: String): AgentDetail = error("unused")
        override suspend fun add(spec: NewAgentSpec): Agent {
            lastSpec = spec
            return Agent(spec.id, spec.name, spec.role, spec.id, AgentRunState.STOPPED)
        }
        override suspend fun edit(id: String, edit: AgentEdit): Agent = error("unused")
        override suspend fun remove(id: String, worktree: WorktreeFate) {}
    }

    private fun vmWith(repo: CapturingRepo) =
        AgentManagementViewModel(repo, editable = true, scope = CoroutineScope(Dispatchers.Unconfined))

    private fun AgentManagementViewModel.fillValidAdd() {
        openAdd(); setAddId("be"); setAddName("Backend"); setAddRole(Role.WORKER)
    }

    @Test
    fun toggleOn_confirmAdd_sendsRemoteTrue() {
        val repo = CapturingRepo()
        val vm = vmWith(repo)
        vm.fillValidAdd()
        vm.setAddRemote(true)
        vm.confirmAdd()
        // MUT: confirmAdd drops `remote = f.remote` → the sent spec stays false → reds.
        assertEquals(true, repo.lastSpec?.remote)
    }

    @Test
    fun defaultOff_confirmAdd_sendsRemoteFalse() {
        val repo = CapturingRepo()
        val vm = vmWith(repo)
        vm.fillValidAdd() // no setAddRemote → default local agent
        vm.confirmAdd()
        assertEquals(false, repo.lastSpec?.remote)
    }

    @Test
    fun addDialog_remoteToggle_isRendered_andBoundToForm() = runComposeUiTest {
        val vm = vmWith(CapturingRepo())
        setContent { MaterialTheme { AgentManagementPanel(vm) } }
        vm.openAdd() // reveal the add dialog (contains the remote toggle)
        waitForIdle()
        // The toggle is present and OFF by default (local agent).
        onNodeWithTag(AgentMgmtTags.ADD_REMOTE_TOGGLE).assertIsOff()
        // …and its checked state is bound to addForm.remote. MUT: `checked` not bound to the form → stays off → reds.
        vm.setAddRemote(true)
        waitForIdle()
        onNodeWithTag(AgentMgmtTags.ADD_REMOTE_TOGGLE).assertIsOn()
        assertTrue(vm.state.value.addForm.remote)
    }
}
