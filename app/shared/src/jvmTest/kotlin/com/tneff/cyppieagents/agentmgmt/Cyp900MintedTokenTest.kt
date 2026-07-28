package com.tneff.cyppieagents.agentmgmt

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentDetail
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.CreatedAgent
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.WorktreeFate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * CYP-900 (BYOA-M1 B2) — the one-time minted token is surfaced ONCE for the operator to copy, and NEVER leaks. A
 * remote create carries the token through to [AgentMgmtUiState.addSuccessToken]; a local create surfaces none; the
 * reveal is shown-once (dismiss → gone, no re-view); the secret is confined to the token field (never folded into the
 * user-facing / loggable name or error).
 */
@OptIn(ExperimentalTestApi::class)
class Cyp900MintedTokenTest {

    private class TokenRepo(private val token: String?) : AgentManagementRepository {
        override suspend fun list(): List<Agent> = emptyList()
        override suspend fun detail(id: String): AgentDetail = error("unused")
        override suspend fun add(spec: NewAgentSpec): CreatedAgent =
            CreatedAgent(Agent(spec.id, spec.name, spec.role, spec.id, AgentRunState.STOPPED), token)
        override suspend fun edit(id: String, edit: AgentEdit): Agent = error("unused")
        override suspend fun remove(id: String, worktree: WorktreeFate) {}
    }

    private fun filledVm(repo: AgentManagementRepository): AgentManagementViewModel {
        val vm = AgentManagementViewModel(repo, editable = true, scope = CoroutineScope(Dispatchers.Unconfined))
        vm.openAdd(); vm.setAddId("be"); vm.setAddName("Backend"); vm.setAddRole(Role.WORKER); vm.setAddRemote(true)
        return vm
    }

    @Test
    fun remoteCreate_surfacesMintedTokenOnce() {
        val vm = filledVm(TokenRepo("TKN-777"))
        vm.confirmAdd()
        // MUT: confirmAdd drops `addSuccessToken = created.token` → null → reds.
        assertEquals("TKN-777", vm.state.value.addSuccessToken)
    }

    @Test
    fun localCreate_surfacesNoToken() {
        val vm = filledVm(TokenRepo(null))
        vm.confirmAdd()
        assertNull(vm.state.value.addSuccessToken)
    }

    @Test
    fun dismiss_clearsToken_noReView() {
        val vm = filledVm(TokenRepo("TKN-777"))
        vm.confirmAdd()
        assertEquals("TKN-777", vm.state.value.addSuccessToken)
        vm.dismissAddSuccessToken()
        assertNull(vm.state.value.addSuccessToken) // shown-once: once dismissed, there is no way back
    }

    @Test
    fun mintedToken_confinedToTokenField_notInNameOrError() {
        val vm = filledVm(TokenRepo("TKN-777"))
        vm.confirmAdd()
        val s = vm.state.value
        // Secret-hygiene: the token must NOT be folded into the user-facing / loggable confirmation name or error.
        assertNull(s.addError)
        assertFalse(s.addSuccessName.orEmpty().contains("TKN-777"))
    }

    @Test
    fun reveal_showsTokenOnce_withCopy_dismissRemovesIt() = runComposeUiTest {
        val vm = filledVm(TokenRepo("TKN-XYZ"))
        setContent { MaterialTheme { AgentManagementPanel(vm) } }
        vm.confirmAdd() // create succeeds → the panel-level reveal appears
        waitForIdle()
        onNodeWithTag(AgentMgmtTags.ADD_TOKEN_REVEAL).assertExists()
        onNodeWithText("TKN-XYZ").assertExists() // readable (not masked), selectable
        onNodeWithTag(AgentMgmtTags.ADD_TOKEN_COPY).assertExists()
        // Dismiss → gone. MUT: dismiss not wired / reveal not gated on the token → still present → reds.
        onNodeWithTag(AgentMgmtTags.ADD_TOKEN_DISMISS).performClick()
        waitForIdle()
        onNodeWithTag(AgentMgmtTags.ADD_TOKEN_REVEAL).assertDoesNotExist()
        onNodeWithText("TKN-XYZ").assertDoesNotExist()
    }
}
