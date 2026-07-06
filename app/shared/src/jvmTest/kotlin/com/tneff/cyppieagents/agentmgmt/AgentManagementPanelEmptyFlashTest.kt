package com.tneff.cyppieagents.agentmgmt

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentDetail
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.WorktreeFate
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test

/**
 * CYP-276 (CYP-270 class) — the CYP-228 onboarding empty-state must NOT flash during the async load window
 * (cold open / project switch); only a SETTLED-empty list shows it. A [GatedRepo] holds `list()` suspended so
 * the VM stays `loading = true`, proving the panel gates the empty-state on `!loading`.
 *
 * Mutation proof: drop the `!state.loading &&` guard at AgentManagementPanel:146 → the onboarding empty-state
 * renders WHILE loading → the first assertion of [onboardingEmptyState_neverFlashesDuringLoad_thenShowsWhenSettledEmpty] REDs.
 */
@OptIn(ExperimentalTestApi::class)
class AgentManagementPanelEmptyFlashTest {

    private fun agent(id: String, role: Role) = Agent(id, id.uppercase(), role, id, AgentRunState.RUNNING)

    /** `list()` suspends on [gate] → the VM stays in its initial `loading = true` until the test releases it. */
    private class GatedRepo(val gate: CompletableDeferred<Unit>, val result: List<Agent>) : AgentManagementRepository {
        override suspend fun list(): List<Agent> { gate.await(); return result }
        override suspend fun detail(id: String): AgentDetail = throw NotImplementedError()
        override suspend fun add(spec: NewAgentSpec): Agent = throw NotImplementedError()
        override suspend fun edit(id: String, edit: AgentEdit): Agent = throw NotImplementedError()
        override suspend fun remove(id: String, worktree: WorktreeFate) = throw NotImplementedError()
    }

    @Test
    fun onboardingEmptyState_neverFlashesDuringLoad_thenShowsWhenSettledEmpty() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        setContent {
            MaterialTheme {
                val vm = remember { AgentManagementViewModel(GatedRepo(gate, emptyList()), editable = true) }
                AgentManagementPanel(vm)
            }
        }
        waitForIdle()
        // Load in flight (gate not released) → loading = true → the onboarding empty-state must NOT show (the flash).
        onNodeWithTag(AgentMgmtTags.EMPTY).assertDoesNotExist()
        // Release the load → settles empty → NOW the onboarding empty-state appears (non-vacuous: it CAN show).
        gate.complete(Unit)
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AgentMgmtTags.EMPTY).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AgentMgmtTags.EMPTY).assertExists()
    }

    @Test
    fun onboardingEmptyState_neverShows_whenLoadYieldsAgents() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        setContent {
            MaterialTheme {
                val vm = remember { AgentManagementViewModel(GatedRepo(gate, listOf(agent("po", Role.PO))), editable = true) }
                AgentManagementPanel(vm)
            }
        }
        waitForIdle()
        onNodeWithTag(AgentMgmtTags.EMPTY).assertDoesNotExist() // during load
        gate.complete(Unit)
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AgentMgmtTags.item("po")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AgentMgmtTags.EMPTY).assertDoesNotExist() // settled WITH data → never
    }
}
