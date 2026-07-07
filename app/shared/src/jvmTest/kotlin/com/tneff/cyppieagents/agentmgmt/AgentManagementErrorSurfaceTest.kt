package com.tneff.cyppieagents.agentmgmt

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentDetail
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.WorktreeFate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test

/**
 * CYP-288 A3/AgentManagement — a FAILED agent-list load must render the honest error+retry surface (shared
 * LoadErrorRetry), NOT the CYP-228 onboarding empty-state ("no agents yet — add one"). Sweep-#4 class A: reload()
 * swallowed the failure to empty with no error field, so a failed GET /api/agents looked like a fresh workspace.
 *
 * Unconfined scope runs the load synchronously at construction → deterministic waitForIdle (no wall-clock
 * waitUntil, the CYP-271 flake class). Mutation proof: restore the swallow (listError=false) → the failure test
 * REDs (onboarding shows). The genuinely-empty contrast keeps it non-vacuous.
 */
@OptIn(ExperimentalTestApi::class)
class AgentManagementErrorSurfaceTest {

    private abstract class BaseRepo : AgentManagementRepository {
        override suspend fun detail(id: String): AgentDetail = throw NotImplementedError()
        override suspend fun add(spec: NewAgentSpec): Agent = throw NotImplementedError()
        override suspend fun edit(id: String, edit: AgentEdit): Agent = throw NotImplementedError()
        override suspend fun remove(id: String, worktree: WorktreeFate) = throw NotImplementedError()
    }

    private class FailingRepo : BaseRepo() {
        override suspend fun list(): List<Agent> = throw RuntimeException("boom")
    }

    private class EmptyRepo : BaseRepo() {
        override suspend fun list(): List<Agent> = emptyList()
    }

    /** Fails once (→ error), then succeeds with one agent → proves Retry re-invokes the load. */
    private class FlakyRepo : BaseRepo() {
        var calls = 0
        override suspend fun list(): List<Agent> {
            calls += 1
            if (calls == 1) throw RuntimeException("boom")
            return listOf(Agent("a1", "A1", Role.WORKER, "a1"))
        }
    }

    private fun vm(repo: AgentManagementRepository) =
        AgentManagementViewModel(repo, editable = true, scope = CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun listLoadFailure_showsErrorAndRetry_notOnboardingEmpty() = runComposeUiTest {
        setContent { MaterialTheme { AgentManagementPanel(remember { vm(FailingRepo()) }) } }
        waitForIdle()
        onNodeWithTag(AgentMgmtTags.ERROR).assertExists()
        onNodeWithTag(AgentMgmtTags.ERROR_RETRY).assertExists()
        onNodeWithTag(AgentMgmtTags.EMPTY).assertDoesNotExist() // error beats the onboarding empty — the fix
    }

    @Test
    fun genuinelyEmpty_showsOnboarding_notError() = runComposeUiTest {
        // Non-vacuous contrast: a SUCCESSFUL empty list still shows the onboarding empty-state, never the error.
        setContent { MaterialTheme { AgentManagementPanel(remember { vm(EmptyRepo()) }) } }
        waitForIdle()
        onNodeWithTag(AgentMgmtTags.EMPTY).assertExists()
        onNodeWithTag(AgentMgmtTags.ERROR).assertDoesNotExist()
    }

    @Test
    fun retry_reinvokesLoad_recovers() = runComposeUiTest {
        setContent { MaterialTheme { AgentManagementPanel(remember { vm(FlakyRepo()) }) } }
        waitForIdle()
        onNodeWithTag(AgentMgmtTags.ERROR).assertExists()
        onNodeWithTag(AgentMgmtTags.ERROR_RETRY).performClick() // re-invokes the load; the 2nd list() succeeds
        waitForIdle()
        onNodeWithTag(AgentMgmtTags.ERROR).assertDoesNotExist()
        onNodeWithTag(AgentMgmtTags.EMPTY).assertDoesNotExist() // neither error nor empty → the list recovered
    }
}
