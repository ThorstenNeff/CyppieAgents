package com.tneff.cyppieagents.firstrun

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.agentmgmt.AgentManagementRepository
import com.tneff.cyppieagents.agentmgmt.AgentManagementViewModel
import com.tneff.cyppieagents.agentmgmt.AgentMgmtException
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentDetail
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.WorktreeFate
import com.tneff.cyppieagents.settings.ApiKeyState
import com.tneff.cyppieagents.settings.ConfigRepository
import com.tneff.cyppieagents.settings.RepoConfigState
import com.tneff.cyppieagents.settings.SettingsTags
import com.tneff.cyppieagents.settings.SettingsViewModel
import com.tneff.cyppieagents.workspace.WorkspaceTags
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.first_run_repo_clone_failed_url
import kmpcyppieagents.app.shared.generated.resources.first_run_step_repo
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test

/**
 * CYP-629 Inc4 — the honest degraded workspace after a skip (ux-spec §6.2/§6.3a). Pins: the specific banner (named
 * missing items), the `CLONE_FAILED`-≠-missing render, the collapse→passive-chip Nag-fix, resume reopening the gate at
 * the first open step, and — the PO lifecycle tooth — that the collapse preference is STICKY across a resume cycle
 * (hoisted), yet FULL on a fresh session-start.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp629WorkspaceDegradedTest {

    private val WORKSPACE = "workspace-marker"

    private class StubRepo : ConfigRepository {
        override suspend fun getRepo(): RepoConfigState = RepoConfigState.NotConfigured
        override suspend fun putRepo(url: String, branch: String) = RepoConfigState.Configured(url, branch)
        override suspend fun getApiKey() = ApiKeyState(set = false, masked = null)
        override suspend fun putApiKey(apiKey: String) = ApiKeyState(set = true, masked = "***1234")
    }

    private class StubMgmtRepo : AgentManagementRepository {
        override suspend fun list(): List<Agent> = emptyList()
        override suspend fun detail(id: String): AgentDetail = throw AgentMgmtException("stub")
        override suspend fun add(spec: NewAgentSpec): Agent = throw AgentMgmtException("stub")
        override suspend fun edit(id: String, edit: AgentEdit): Agent = throw AgentMgmtException("stub")
        override suspend fun remove(id: String, worktree: WorktreeFate) {}
    }

    private fun unconfigured() = FirstRunConfigStatus(loaded = true, apiKeySet = false, cloneStatus = CloneStatus.NOT_CONFIGURED)
    private fun keySet_cloneFailed(reason: CloneFailReason) =
        FirstRunConfigStatus(loaded = true, apiKeySet = true, cloneStatus = CloneStatus.CLONE_FAILED, cloneReason = reason)
    private fun keySet_cloning() = FirstRunConfigStatus(loaded = true, apiKeySet = true, cloneStatus = CloneStatus.CLONING)
    private fun done() = FirstRunConfigStatus(loaded = true, apiKeySet = true, cloneStatus = CloneStatus.CLONED_OK)

    private fun runGate(status: FirstRunConfigStatus, block: androidx.compose.ui.test.ComposeUiTest.() -> Unit) =
        runComposeUiTest {
            setContent {
                MaterialTheme {
                    FirstRunGate(
                        enabled = true,
                        createViewModel = { FirstRunViewModel(StubFirstRunConfigSource(status)) },
                        createSettingsViewModel = { SettingsViewModel(StubRepo(), editable = true) },
                        createAgentMgmtViewModel = { AgentManagementViewModel(StubMgmtRepo(), editable = true) },
                        workspace = { Text("workspace", modifier = Modifier.testTag(WORKSPACE)) },
                    )
                }
            }
            block()
        }

    @Test
    fun skip_unconfigured_showsBanner_withResume_overWorkspace() = runGate(unconfigured()) {
        onNodeWithTag(FirstRunTags.SKIP).performClick()
        // The real workspace is present (the hub runs) AND the honest banner sits over it.
        onNodeWithTag(WORKSPACE).assertExists()
        onNodeWithTag(WorkspaceTags.UNCONFIGURED_BANNER).assertExists()
        onNodeWithTag(WorkspaceTags.SETUP_RESUME).assertExists()
    }

    @Test
    fun cloneFailed_showsCloneError_notRepoMissing() = runComposeUiTest {
        // ★ SHARP render: a set-but-failed repo shows the clone-error copy and NOT the "Repository" missing chip.
        // (No stepper is on screen post-skip, so the ONLY possible "Repository" text would be the missing chip.)
        lateinit var urlCopy: String
        lateinit var repoLabel: String
        setContent {
            MaterialTheme {
                urlCopy = stringResource(Res.string.first_run_repo_clone_failed_url)
                repoLabel = stringResource(Res.string.first_run_step_repo)
                FirstRunGate(
                    enabled = true,
                    createViewModel = { FirstRunViewModel(StubFirstRunConfigSource(keySet_cloneFailed(CloneFailReason.URL_UNREACHABLE))) },
                    createSettingsViewModel = { SettingsViewModel(StubRepo(), editable = true) },
                    createAgentMgmtViewModel = { AgentManagementViewModel(StubMgmtRepo(), editable = true) },
                    workspace = { Text("workspace", modifier = Modifier.testTag(WORKSPACE)) },
                )
            }
        }
        onNodeWithTag(FirstRunTags.SKIP).performClick()
        onNodeWithTag(WorkspaceTags.UNCONFIGURED_BANNER).assertExists()
        onNodeWithText(urlCopy).assertExists() // the clone-error copy — the specific truth
        onNodeWithText(repoLabel).assertDoesNotExist() // NOT "repository missing" — it IS set, just failed
    }

    @Test
    fun collapse_showsPassiveChip_hidesBanner() = runGate(unconfigured()) {
        onNodeWithTag(FirstRunTags.SKIP).performClick()
        onNodeWithTag(WorkspaceTags.UNCONFIGURED_COLLAPSE).performClick()
        onNodeWithTag(WorkspaceTags.UNCONFIGURED_CHIP).assertExists()
        onNodeWithTag(WorkspaceTags.UNCONFIGURED_BANNER).assertDoesNotExist()
        // Never wholly hidden: the chip stays (honesty), the workspace is still usable underneath.
        onNodeWithTag(WORKSPACE).assertExists()
    }

    @Test
    fun degradedWorkspace_whenConfigured_showsNoBanner() = runComposeUiTest {
        // Direct render: once configured (done/TRANSPARENT) the surface is invisible — workspace only, no nag.
        setContent {
            MaterialTheme {
                DegradedWorkspace(
                    status = done(),
                    collapsedPref = false,
                    onCollapse = {},
                    onResume = {},
                    workspace = { Text("workspace", modifier = Modifier.testTag(WORKSPACE)) },
                )
            }
        }
        onNodeWithTag(WORKSPACE).assertExists()
        onNodeWithTag(WorkspaceTags.UNCONFIGURED_BANNER).assertDoesNotExist()
        onNodeWithTag(WorkspaceTags.UNCONFIGURED_CHIP).assertDoesNotExist()
    }

    @Test
    fun resume_reopensGate_atFirstOpenStep() = runGate(keySet_cloning()) {
        // Key already set, repo cloning → mode ACTIVE. Skip, then resume: the gate reopens at the FIRST OPEN step
        // (REPO — because the key is set), not step 1.
        onNodeWithTag(FirstRunTags.SKIP).performClick()
        onNodeWithTag(WorkspaceTags.SETUP_RESUME).performClick()
        onNodeWithTag(SettingsTags.SECTION_REPO).assertExists()
        onNodeWithTag(SettingsTags.SECTION_API_KEY).assertDoesNotExist()
    }

    @Test
    fun collapse_isStickyAcrossResumeCycle_butFullOnFreshStart() = runGate(unconfigured()) {
        // ★ PO LIFECYCLE TOOTH. Fresh session-start ⇒ the FULL banner (once per session).
        onNodeWithTag(FirstRunTags.SKIP).performClick()
        onNodeWithTag(WorkspaceTags.UNCONFIGURED_BANNER).assertExists()

        // Collapse ⇒ the passive chip.
        onNodeWithTag(WorkspaceTags.UNCONFIGURED_COLLAPSE).performClick()
        onNodeWithTag(WorkspaceTags.UNCONFIGURED_CHIP).assertExists()

        // Bring the banner back on demand (chip tap) to reach the resume CTA, then run a full resume cycle:
        // chip → banner → resume → gate → skip back out.
        onNodeWithTag(WorkspaceTags.UNCONFIGURED_CHIP).performClick()
        onNodeWithTag(WorkspaceTags.SETUP_RESUME).performClick()
        onNodeWithTag(FirstRunTags.SKIP).performClick() // out of the gate again (DegradedWorkspace remounts)

        // STICKY: after the resume → gate → skip round-trip the collapse preference persists ⇒ the passive chip,
        // NOT the full nag banner. Mutation: move `bannerCollapsed` from gate scope INTO DegradedWorkspace (local) →
        // the unmount on resume resets it → the full banner re-nags here → this reddens. (A pure-state tooth stays
        // green while the surface nags — computing ≠ achieved, the CYP-573 wiring lesson.)
        onNodeWithTag(WorkspaceTags.UNCONFIGURED_CHIP).assertExists()
        onNodeWithTag(WorkspaceTags.UNCONFIGURED_BANNER).assertDoesNotExist()
    }
}
