package com.tneff.cyppieagents.net.hub.operator.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/**
 * CYP-479 §3.2 — recovery-input honesty teeth: the **no-central-login** disclosure is always present (HE); the
 * **multi-device** recommendation is **seam-gated** (absent by default — `null≠0`, followable-advice-only-when-
 * real); a wrong code surfaces a retryable error; **exhausted** shows the terminal (neutral) copy and
 * **disables** the field (fail-closed, no more tries).
 */
@OptIn(ExperimentalTestApi::class)
class RecoveryInputRenderTest {

    private fun state(error: RecoveryError? = null) = RemoteRecoveryViewModel.State(error = error)

    @Test
    fun noCentralAlways_multideviceSeamGatedAbsent() = runComposeUiTest {
        setContent { MaterialTheme { RecoveryInputContent(state(), onCodeChange = {}, onSubmit = {}) } }
        onNodeWithTag(RemoteRecoveryTags.START, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteRecoveryTags.CODE_FIELD, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteRecoveryTags.NO_CENTRAL, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteRecoveryTags.MULTIDEVICE, useUnmergedTree = true).assertDoesNotExist() // seam-gated
        onNodeWithTag(RemoteRecoveryTags.ERROR, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun multidevice_shownOnlyWhenAvailable() = runComposeUiTest {
        setContent {
            MaterialTheme { RecoveryInputContent(state(), onCodeChange = {}, onSubmit = {}, multiDeviceAvailable = true) }
        }
        onNodeWithTag(RemoteRecoveryTags.MULTIDEVICE, useUnmergedTree = true).assertExists()
    }

    @Test
    fun invalidCode_showsError_fieldStaysUsable() = runComposeUiTest {
        setContent { MaterialTheme { RecoveryInputContent(state(RecoveryError.InvalidCode), {}, {}) } }
        onNodeWithTag(RemoteRecoveryTags.ERROR, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteRecoveryTags.CODE_FIELD, useUnmergedTree = true).assertExists()
    }

    @Test
    fun exhausted_showsTerminalCopy_disablesField() = runComposeUiTest {
        setContent { MaterialTheme { RecoveryInputContent(state(RecoveryError.Exhausted), {}, {}) } }
        onNodeWithTag(RemoteRecoveryTags.ERROR, useUnmergedTree = true).assertExists()
        onNodeWithTag(RemoteRecoveryTags.CODE_FIELD, useUnmergedTree = true).assertIsNotEnabled() // fail-closed
    }
}
