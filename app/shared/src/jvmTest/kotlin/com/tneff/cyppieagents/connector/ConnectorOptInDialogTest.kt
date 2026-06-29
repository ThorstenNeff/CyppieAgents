package com.tneff.cyppieagents.connector

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.ConnectorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test

/**
 * CYP-123 Inc 2 (spec §3) — the connector picker + B opt-in dialog render contract (default locale = DE).
 * Proves the security-visible structure:
 *  - **S1** B is never pre-selected in the picker (the MCP radio is OFF initially);
 *  - the deliberate opt-in dialog shows the three amber risk lines, the B capability preview, the ack row;
 *  - **S6** the load-bearing human-only note node is present;
 *  - **S2** the confirm is disabled until the ack, then enabled — the ack-gate made visible.
 */
@OptIn(ExperimentalTestApi::class)
class ConnectorOptInDialogTest {

    private fun vm(editable: Boolean = true) = ConnectorSelectionViewModel(
        StubConnectorSelectionRepository(),
        agentId = "frontend",
        editable = editable,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    /** S1: B is never pre-selected — the MCP radio is OFF initially, the stream-json radio is the default. */
    @Test
    fun s1_bRadioNotSelectedInitially() = runComposeUiTest {
        setContent { MaterialTheme { ConnectorPicker(vm()) } }
        onNodeWithTag(ConnectorTags.pickerOption(ConnectorKind.MCP)).assertIsNotSelected()
        // ...and A is the first-class default, pre-selected.
        onNodeWithTag(ConnectorTags.pickerOption(ConnectorKind.STREAM_JSON)).assertIsSelected()
    }

    /** Selecting B opens the dialog with the risk disclosure, B preview, ack row, and the human-only note. */
    @Test
    fun selectingB_opensDialog_withRisksPreviewAndHumanOnly() = runComposeUiTest {
        setContent { MaterialTheme { ConnectorPicker(vm()) } }
        onNodeWithTag(ConnectorTags.pickerOption(ConnectorKind.MCP)).performClick()

        onNodeWithTag(ConnectorTags.OPTIN_DIALOG).assertExists()
        onNodeWithTag(ConnectorTags.OPTIN_RISK_BYPASS).assertExists()
        onNodeWithTag(ConnectorTags.OPTIN_RISK_ACCOUNT).assertExists()
        onNodeWithTag(ConnectorTags.OPTIN_RISK_FRAGILE).assertExists()
        onNodeWithTag(ConnectorTags.OPTIN_PREVIEW).assertExists()
        onNodeWithTag(ConnectorTags.OPTIN_ACK).assertExists()
    }

    /** S6: the load-bearing anti-injection human-only note is rendered in the opt-in dialog. */
    @Test
    fun s6_humanOnlyNotePresent() = runComposeUiTest {
        setContent { MaterialTheme { ConnectorPicker(vm()) } }
        onNodeWithTag(ConnectorTags.pickerOption(ConnectorKind.MCP)).performClick()
        onNodeWithTag(ConnectorTags.OPTIN_HUMAN_ONLY).assertExists()
    }

    /** S2 (UI): the confirm is disabled until the ack row is toggled, then enabled — the ack-gate is visible. */
    @Test
    fun s2_confirmDisabledUntilAck() = runComposeUiTest {
        setContent { MaterialTheme { ConnectorPicker(vm()) } }
        onNodeWithTag(ConnectorTags.pickerOption(ConnectorKind.MCP)).performClick()

        onNodeWithTag(ConnectorTags.OPTIN_CONFIRM).assertIsNotEnabled()
        onNodeWithTag(ConnectorTags.OPTIN_ACK).performClick() // whole-row toggles the acknowledgment
        onNodeWithTag(ConnectorTags.OPTIN_CONFIRM).assertIsEnabled()
    }

    /**
     * S8: the three risk lines are a hazard disclosure (amber EFFECT_DEFERRED), NOT an app error — no ERROR-toned
     * risk node. The error line (ERROR-red `✕`) is absent while there is no failure; the risk nodes are present.
     */
    @Test
    fun s8_riskLinesAreNotErrorToned() = runComposeUiTest {
        setContent { MaterialTheme { ConnectorPicker(vm()) } }
        onNodeWithTag(ConnectorTags.pickerOption(ConnectorKind.MCP)).performClick()
        // The risk disclosure exists...
        onNodeWithTag(ConnectorTags.OPTIN_RISK_BYPASS).assertExists()
        // ...and there is NO error-toned line in the dialog (the only ERROR node, the error line, is absent).
        onNodeWithTag(ConnectorTags.OPTIN_ERROR).assertDoesNotExist()
    }

    /** The B capability-preview renders the §2.3 status chip for connector B's structuredUsage (UNAVAILABLE). */
    @Test
    fun preview_rendersBStatusChips() = runComposeUiTest {
        setContent { MaterialTheme { ConnectorPicker(vm()) } }
        onNodeWithTag(ConnectorTags.pickerOption(ConnectorKind.MCP)).performClick()
        onNodeWithTag(
            ConnectorTags.capabilityStatus(CAPABILITY_PREVIEW_SCOPE, CapabilityDimension.STRUCTURED_USAGE),
        ).assertExists()
    }

    /**
     * PO honesty constraint: opting into B must NEVER read as "B is active" while the write-seam is a stub. After
     * the acknowledged confirm the picker shows B as the *intent* AND surfaces the reused amber "saved ≠ active —
     * restart" hint — the active connector is the server-truth capability display only. Mutation: drop the
     * `draftKind == MCP` condition on the effect hint → this goes RED.
     */
    @Test
    fun afterBOptIn_showsSavedNotActiveHint_neverSuggestsBActive() = runComposeUiTest {
        setContent { MaterialTheme { ConnectorPicker(vm()) } }
        onNodeWithTag(ConnectorTags.pickerOption(ConnectorKind.MCP)).performClick() // open the opt-in dialog
        onNodeWithTag(ConnectorTags.OPTIN_ACK).performClick() // acknowledge
        onNodeWithTag(ConnectorTags.OPTIN_CONFIRM).performClick() // confirm → draftKind=MCP, dialog closes
        onNodeWithTag(ConnectorTags.pickerOption(ConnectorKind.MCP)).assertIsSelected()
        onNodeWithTag("${ConnectorTags.PICKER}.effectHint").assertExists()
    }
}
