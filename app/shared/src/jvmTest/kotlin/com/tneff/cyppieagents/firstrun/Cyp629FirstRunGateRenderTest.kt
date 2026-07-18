package com.tneff.cyppieagents.firstrun

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.agentmgmt.AgentManagementRepository
import com.tneff.cyppieagents.agentmgmt.AgentManagementViewModel
import com.tneff.cyppieagents.agentmgmt.AgentMgmtException
import com.tneff.cyppieagents.agentmgmt.AgentMgmtTags
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
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.first_run_apikey_posture
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test

/**
 * CYP-629 (stepper) — the FirstRunGate WIRING tooth, pinning the four PO non-negotiables at the render layer (the
 * pure mode/step decisions are covered by [Cyp629FirstRunGateModelTest]):
 *  - **①** LOADING (unknown) shows neither a step nor the workspace (unknown ≠ unconfigured);
 *  - **③** the completion surface appears from mode == TRANSPARENT, not a click; only its CTA opens the workspace;
 *  - **④** the stepper lands on the first OPEN step (REPO when the key is already set), not step 1;
 *  - opt-in-off inertness (neither VM constructed).
 */
@OptIn(ExperimentalTestApi::class)
class Cyp629FirstRunGateRenderTest {

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

    /** A source whose returned status can be flipped between reads, to drive an in-session ACTIVE → TRANSPARENT transition. */
    private class MutableFirstRunConfigSource(var current: FirstRunConfigStatus) : FirstRunConfigSource {
        override suspend fun status(): FirstRunConfigStatus = current
    }

    private fun unconfigured() = FirstRunConfigStatus(loaded = true, apiKeySet = false, cloneStatus = CloneStatus.NOT_CONFIGURED)
    // B1: "key set, repo NOT set" is the ACTIVE state that lands on the REPO step (a set repo would be TRANSPARENT).
    private fun keySetRepoNotSet() = FirstRunConfigStatus(loaded = true, apiKeySet = true, cloneStatus = CloneStatus.NOT_CONFIGURED)
    private fun done() = FirstRunConfigStatus(loaded = true, apiKeySet = true, cloneStatus = CloneStatus.CLONED_OK)

    private fun runGate(status: FirstRunConfigStatus, enabled: Boolean = true, block: androidx.compose.ui.test.ComposeUiTest.() -> Unit) =
        runComposeUiTest {
            setContent {
                MaterialTheme {
                    FirstRunGate(
                        enabled = enabled,
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
    fun disabled_isInert_rendersWorkspaceDirectly() = runComposeUiTest {
        setContent {
            MaterialTheme {
                FirstRunGate(
                    enabled = false,
                    createViewModel = { error("VM must NOT be constructed when disabled") },
                    createSettingsViewModel = { error("settings VM must NOT be constructed when disabled") },
                    createAgentMgmtViewModel = { error("agent-mgmt VM must NOT be constructed when disabled") },
                    workspace = { Text("workspace", modifier = Modifier.testTag(WORKSPACE)) },
                )
            }
        }
        onNodeWithTag(WORKSPACE).assertExists()
        onNodeWithTag(FirstRunTags.GATE).assertDoesNotExist()
    }

    @Test
    fun unconfigured_landsOnApiKeyStep() = runGate(unconfigured()) {
        onNodeWithTag(FirstRunTags.INTRO).assertExists() // orientation shown
        onNodeWithTag(WORKSPACE).assertDoesNotExist()
        // ④: first open step = API_KEY → its section renders (repo section does not).
        onNodeWithTag(SettingsTags.SECTION_API_KEY).assertExists()
        onNodeWithTag(SettingsTags.SECTION_REPO).assertDoesNotExist()
    }

    @Test
    fun keySet_landsOnRepoStep_notStepOne() = runGate(keySetRepoNotSet()) {
        // ④: key already set → land on REPO, not API_KEY.
        onNodeWithTag(SettingsTags.SECTION_REPO).assertExists()
        onNodeWithTag(SettingsTags.SECTION_API_KEY).assertDoesNotExist()
    }

    @Test
    fun loading_showsNeitherStepNorWorkspace_failClosed() = runGate(FirstRunConfigStatus.Unknown) {
        // ① a fail-closed LOADING (status unknown, loaded=false): NO step surface and NO workspace — unknown is
        // never rendered as "step 1" nor passed through as done.
        onNodeWithTag(FirstRunTags.INTRO).assertDoesNotExist()
        onNodeWithTag(FirstRunTags.COMPLETE).assertDoesNotExist()
        onNodeWithTag(WORKSPACE).assertDoesNotExist()
    }

    @Test
    fun configuredAtLaunch_passesThroughTransparently_noCompletionStep() = runGate(done()) {
        // §1/§6.3b (CYP-629 live-wiring, A): a hub configured AT LAUNCH — the gate was NEVER ACTIVE this session —
        // is transparent: straight into the workspace, NO intermediate completion step (else a configured hub is
        // nagged with a "Workspace öffnen" CTA every launch). Like RemoteHubConnectGate passing through once satisfied.
        onNodeWithTag(WORKSPACE).assertExists()
        onNodeWithTag(FirstRunTags.OPEN_WORKSPACE).assertDoesNotExist() // no completion CTA on a configured launch
    }

    @Test
    fun configuredInSession_showsCompletion_derivedFromMode_thenCtaOpensWorkspace() = runComposeUiTest {
        // ③ preserved: the completion surface IS still DERIVED from mode == TRANSPARENT — it appears when the operator
        // completes setup DURING this session (the gate was ACTIVE, then becomes TRANSPARENT). `wasActive` gates only
        // its RECURRENCE, not its derivation. Start ACTIVE (unconfigured), then the source reports configured + reload.
        val src = MutableFirstRunConfigSource(
            FirstRunConfigStatus(loaded = true, apiKeySet = false, cloneStatus = CloneStatus.NOT_CONFIGURED),
        )
        lateinit var vm: FirstRunViewModel
        setContent {
            MaterialTheme {
                FirstRunGate(
                    enabled = true,
                    createViewModel = { FirstRunViewModel(src).also { vm = it } },
                    createSettingsViewModel = { SettingsViewModel(StubRepo(), editable = true) },
                    createAgentMgmtViewModel = { AgentManagementViewModel(StubMgmtRepo(), editable = true) },
                    workspace = { Text("workspace", modifier = Modifier.testTag(WORKSPACE)) },
                )
            }
        }
        // ACTIVE this session → the stepper (wasActive latches true), not the workspace.
        onNodeWithTag(FirstRunTags.GATE).assertExists()
        onNodeWithTag(WORKSPACE).assertDoesNotExist()
        // The operator finishes setup → the source now reports configured; the VM re-reads.
        src.current = FirstRunConfigStatus(loaded = true, apiKeySet = true, cloneStatus = CloneStatus.CLONED_OK)
        vm.reload()
        // reload() emits on the VM scope, so wait for the completion CTA to appear (not just a compose frame). Because
        // this session WAS active, TRANSPARENT shows the completion surface (not a silent passthrough). useUnmergedTree:
        // the CTA lives under FirstRunComplete's merged container after the ACTIVE→TRANSPARENT subtree swap.
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(FirstRunTags.OPEN_WORKSPACE, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(WORKSPACE).assertDoesNotExist()
        // Only the CTA advances into the workspace.
        onNodeWithTag(FirstRunTags.OPEN_WORKSPACE, useUnmergedTree = true).performClick()
        onNodeWithTag(WORKSPACE).assertExists()
    }

    @Test
    fun apiKeyStep_showsPostureLine() = runGate(unconfigured()) {
        // ② presence: the at-rest posture line — the ONLY place the product discloses "not encrypted at rest" —
        // must be in the API-key step. Mutation: remove the posture TonedHint → this node is gone → red.
        onNodeWithTag(FirstRunTags.APIKEY_POSTURE).assertExists()
    }

    @Test
    fun postureLine_carriesTheRatifiedString() = runComposeUiTest {
        // ② integrity: the posture line must carry the RATIFIED copy (`first_run_apikey_posture`), not just any
        // string. Assert the KEY's resolved value (i18n-robust — resolved here from the same key), so a refactor
        // that swaps in a different (e.g. falsely reassuring "your key is safe") key reddens. The literal text is
        // never hard-coded in the assertion.
        lateinit var postureText: String
        setContent {
            MaterialTheme {
                postureText = stringResource(Res.string.first_run_apikey_posture)
                FirstRunGate(
                    enabled = true,
                    createViewModel = { FirstRunViewModel(StubFirstRunConfigSource(unconfigured())) },
                    createSettingsViewModel = { SettingsViewModel(StubRepo(), editable = true) },
                    createAgentMgmtViewModel = { AgentManagementViewModel(StubMgmtRepo(), editable = true) },
                    workspace = { Text("workspace", modifier = Modifier.testTag(WORKSPACE)) },
                )
            }
        }
        onNodeWithText(postureText).assertExists()
    }

    @Test
    fun teamStep_embedsRoster_withIntro() = runGate(unconfigured()) {
        // Team is reachable (optional §5). Tapping its chip shows the intro framing + the reused roster panel.
        onNodeWithTag(FirstRunTags.STEP_TEAM).performClick()
        onNodeWithTag(FirstRunTags.TEAM_INTRO).assertExists()
        onNodeWithTag(AgentMgmtTags.PANEL).assertExists()
        // NOTE (PO constraint ③): team is OPTIONAL — there is deliberately NO tooth asserting the finish depends on
        // adding agents; completion hangs on Key + Repo CLONED_OK (covered by done_showsCompletionSurface…).
    }

    @Test
    fun skip_dropsToWorkspace() = runGate(unconfigured()) {
        onNodeWithTag(WORKSPACE).assertDoesNotExist()
        onNodeWithTag(FirstRunTags.SKIP).performClick()
        onNodeWithTag(WORKSPACE).assertExists()
    }
}
