package com.tneff.cyppieagents.firstrun

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/**
 * CYP-629 (2a) — the FirstRunGate WIRING tooth: the opt-in-off inertness and the mode→surface routing. The pure
 * mode decision is covered by [Cyp629FirstRunGateModelTest]; this pins that the composable actually honours it.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp629FirstRunGateRenderTest {

    private val WORKSPACE = "workspace-marker"

    private fun unconfigured() = FirstRunConfigStatus(loaded = true, apiKeySet = false, cloneStatus = CloneStatus.NOT_CONFIGURED)
    private fun done() = FirstRunConfigStatus(loaded = true, apiKeySet = true, cloneStatus = CloneStatus.CLONED_OK)

    @Test
    fun disabled_isInert_rendersWorkspaceDirectly() = runComposeUiTest {
        setContent {
            MaterialTheme {
                FirstRunGate(
                    enabled = false,
                    createViewModel = { error("VM must NOT be constructed when disabled") },
                    workspace = { Text("workspace", modifier = Modifier.testTag(WORKSPACE)) },
                )
            }
        }
        // OFF ⇒ byte-identical: workspace shows, the gate never renders.
        onNodeWithTag(WORKSPACE).assertExists()
        onNodeWithTag(FirstRunTags.GATE).assertDoesNotExist()
    }

    @Test
    fun enabledAndUnconfigured_showsSetupNotWorkspace() = runComposeUiTest {
        setContent {
            MaterialTheme {
                FirstRunGate(
                    enabled = true,
                    createViewModel = { FirstRunViewModel(StubFirstRunConfigSource(unconfigured())) },
                    workspace = { Text("workspace", modifier = Modifier.testTag(WORKSPACE)) },
                )
            }
        }
        // ACTIVE ⇒ the setup surface (orientation), NOT the workspace.
        onNodeWithTag(FirstRunTags.INTRO).assertExists()
        onNodeWithTag(WORKSPACE).assertDoesNotExist()
    }

    @Test
    fun enabledAndDone_isTransparent_rendersWorkspace() = runComposeUiTest {
        setContent {
            MaterialTheme {
                FirstRunGate(
                    enabled = true,
                    createViewModel = { FirstRunViewModel(StubFirstRunConfigSource(done())) },
                    workspace = { Text("workspace", modifier = Modifier.testTag(WORKSPACE)) },
                )
            }
        }
        // TRANSPARENT (key set AND CLONED_OK) ⇒ straight to the workspace, no gate.
        onNodeWithTag(WORKSPACE).assertExists()
        onNodeWithTag(FirstRunTags.GATE).assertDoesNotExist()
    }

    @Test
    fun skip_dropsToWorkspace() = runComposeUiTest {
        setContent {
            MaterialTheme {
                FirstRunGate(
                    enabled = true,
                    createViewModel = { FirstRunViewModel(StubFirstRunConfigSource(unconfigured())) },
                    workspace = { Text("workspace", modifier = Modifier.testTag(WORKSPACE)) },
                )
            }
        }
        onNodeWithTag(WORKSPACE).assertDoesNotExist() // gate up first
        onNodeWithTag(FirstRunTags.SKIP).performClick()
        onNodeWithTag(WORKSPACE).assertExists() // skip → honest degraded workspace (banner is a later sub-slice)
    }
}
