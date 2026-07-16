package com.tneff.cyppieagents.firstrun

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.settings.ApiKeyState
import com.tneff.cyppieagents.settings.ConfigRepository
import com.tneff.cyppieagents.settings.RepoConfigState
import com.tneff.cyppieagents.settings.SettingsTags
import com.tneff.cyppieagents.settings.SettingsViewModel
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

    private fun unconfigured() = FirstRunConfigStatus(loaded = true, apiKeySet = false, cloneStatus = CloneStatus.NOT_CONFIGURED)
    private fun keyDoneCloning() = FirstRunConfigStatus(loaded = true, apiKeySet = true, cloneStatus = CloneStatus.CLONING)
    private fun done() = FirstRunConfigStatus(loaded = true, apiKeySet = true, cloneStatus = CloneStatus.CLONED_OK)

    private fun runGate(status: FirstRunConfigStatus, enabled: Boolean = true, block: androidx.compose.ui.test.ComposeUiTest.() -> Unit) =
        runComposeUiTest {
            setContent {
                MaterialTheme {
                    FirstRunGate(
                        enabled = enabled,
                        createViewModel = { FirstRunViewModel(StubFirstRunConfigSource(status)) },
                        createSettingsViewModel = { SettingsViewModel(StubRepo(), editable = true) },
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
    fun keySet_landsOnRepoStep_notStepOne() = runGate(keyDoneCloning()) {
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
    fun done_showsCompletionSurface_derivedFromMode_notWorkspaceUntilCta() = runGate(done()) {
        // ③: TRANSPARENT ⇒ the completion surface, NOT the workspace and NOT because a step was clicked.
        onNodeWithTag(FirstRunTags.COMPLETE).assertExists()
        onNodeWithTag(WORKSPACE).assertDoesNotExist()
        // Only the CTA advances into the workspace.
        onNodeWithTag(FirstRunTags.OPEN_WORKSPACE).performClick()
        onNodeWithTag(WORKSPACE).assertExists()
    }

    @Test
    fun skip_dropsToWorkspace() = runGate(unconfigured()) {
        onNodeWithTag(WORKSPACE).assertDoesNotExist()
        onNodeWithTag(FirstRunTags.SKIP).performClick()
        onNodeWithTag(WORKSPACE).assertExists()
    }
}
