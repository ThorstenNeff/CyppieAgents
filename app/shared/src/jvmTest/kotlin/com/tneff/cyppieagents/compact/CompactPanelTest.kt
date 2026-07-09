package com.tneff.cyppieagents.compact

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.CompactConfig
import com.tneff.cyppieagents.model.CompactRunSummary
import com.tneff.cyppieagents.model.CompactStatus
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.ui.MaritimeDark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-326 — the compact-window render honesty.
 *  - operator → live checkbox (no chip, no gate hint); non-operator → read-only chip + gate hint (no checkbox);
 *  - the INFO disclosure hint is ALWAYS present (§3-1);
 *  - UNKNOWN server state → the threshold/status rows are ABSENT, never a defaulted idle/off (§3-3);
 *  - a timeout last-run renders WARN amber, never neutral (§3-4).
 */
@OptIn(ExperimentalTestApi::class)
class CompactPanelTest {

    private fun vm(repo: CompactRepository, editable: Boolean) =
        CompactViewModel(repo, editable, CoroutineScope(Dispatchers.Unconfined))

    private class ThrowingRepo : CompactRepository {
        override suspend fun getStatus(): CompactStatus = throw RuntimeException("boom")
        override suspend fun setConfig(config: CompactConfig): CompactStatus = throw RuntimeException("boom")
    }

    @Test
    fun operator_showsCheckbox_notChip_withServerMirrorRows() = runComposeUiTest {
        val model = vm(StubCompactRepository(CompactStatus(false, 500_000, armed = false, running = false)), editable = true)
        setContent { MaterialTheme { CompactPanel(model) } }
        onNodeWithTag(CompactTags.ALLOW_TOGGLE).assertExists()
        onNodeWithTag(CompactTags.ALLOW_CHIP).assertDoesNotExist()
        onNodeWithTag(CompactTags.ALLOW_HINT).assertExists()   // disclosure always
        onNodeWithTag(CompactTags.GATE_HINT).assertDoesNotExist() // operator: no gate hint
        onNodeWithTag(CompactTags.THRESHOLD_INPUT).assertExists() // CYP-327: an operator edits the threshold (not the read-only row)
        onNodeWithTag(CompactTags.STATUS).assertExists()
    }

    @Test
    fun nonOperator_showsChip_andGateHint_notCheckbox() = runComposeUiTest {
        val model = vm(StubCompactRepository(CompactStatus(true, 500_000, armed = true, running = false)), editable = false)
        setContent { MaterialTheme { CompactPanel(model) } }
        onNodeWithTag(CompactTags.ALLOW_CHIP).assertExists()
        onNodeWithTag(CompactTags.ALLOW_TOGGLE).assertDoesNotExist() // no fake/disabled switch
        onNodeWithTag(CompactTags.GATE_HINT).assertExists()
        onNodeWithTag(CompactTags.ALLOW_HINT).assertExists()
    }

    @Test
    fun unknownStatus_hidesServerMirrorRows_keepsDisclosure() = runComposeUiTest {
        val model = vm(ThrowingRepo(), editable = true) // failed load → status null (UNKNOWN)
        setContent { MaterialTheme { CompactPanel(model) } }
        onNodeWithTag(CompactTags.ALLOW_HINT).assertExists()        // disclosure always
        onNodeWithTag(CompactTags.THRESHOLD).assertDoesNotExist()   // §3-3: unknown → absent
        onNodeWithTag(CompactTags.STATUS).assertDoesNotExist()      // never a defaulted idle/off
    }

    @Test
    fun lastRunAborted_showsDistinctAbortedLabel_warnAmber() = runComposeUiTest {
        // A kill-switch abort: incomplete, pendingAgentIds non-empty — but the panel labels it "aborted", NOT
        // "timeout", and never success. WARN amber like a timeout, distinct label. (jvmTest locale = EN.)
        val aborted = CompactRunSummary(completed = 2, total = 5, pendingAgentIds = listOf("a", "b", "c"), startedTs = 1, finishedTs = 2, aborted = true)
        val model = vm(StubCompactRepository(CompactStatus(true, 500_000, armed = false, running = false, lastRun = aborted)), editable = false)
        setContent { MaterialTheme(colorScheme = MaritimeDark) { CompactPanel(model) } }
        onNodeWithTag(CompactTags.LAST_RUN).assertExists()
        onNodeWithTag(CompactTags.LAST_RUN).assertTextContains("aborted", substring = true)
        val pm = onNodeWithTag(CompactTags.LAST_RUN, useUnmergedTree = true).captureToImage().toPixelMap()
        var amber = 0
        for (y in 0 until pm.height) {
            for (x in 0 until pm.width) {
                val c = pm[x, y]
                if (c.red > 0.85f && c.green > 0.6f && c.blue < 0.5f) amber++
            }
        }
        assertTrue(amber > 0, "an aborted last-run is incomplete → WARN amber (never neutral/green)")
    }

    @Test
    fun lastRunTimeout_rendersWarnAmber_notNeutral() = runComposeUiTest {
        val timeout = CompactRunSummary(completed = 3, total = 5, pendingAgentIds = listOf("a", "b"), startedTs = 1, finishedTs = 2)
        val model = vm(StubCompactRepository(CompactStatus(true, 500_000, armed = false, running = false, lastRun = timeout)), editable = false)
        setContent { MaterialTheme(colorScheme = MaritimeDark) { CompactPanel(model) } }
        onNodeWithTag(CompactTags.LAST_RUN).assertExists()
        val pm = onNodeWithTag(CompactTags.LAST_RUN, useUnmergedTree = true).captureToImage().toPixelMap()
        // WARN Night amber #FFC857 → red≈1.0, green≈0.78, blue≈0.34 — distinct from neutral onSurfaceVariant
        // #A6BECD (blue≈0.80). A timeout row MUST carry amber glyph pixels.
        var amber = 0
        for (y in 0 until pm.height) {
            for (x in 0 until pm.width) {
                val c = pm[x, y]
                if (c.red > 0.85f && c.green > 0.6f && c.blue < 0.5f) amber++
            }
        }
        assertTrue(amber > 0, "a timeout last-run must render WARN amber (severityColor(WARN)), not neutral onSurfaceVariant")
    }

    // --- CYP-327 Feature A: editable threshold (operator input) vs read-only (member) ---

    @Test
    fun operator_threshold_isEditableInput_notReadOnlyRow() = runComposeUiTest {
        val model = vm(StubCompactRepository(CompactStatus(true, 500_000, armed = false, running = false)), editable = true)
        setContent { MaterialTheme { CompactPanel(model) } }
        onNodeWithTag(CompactTags.THRESHOLD_INPUT).assertExists()
        onNodeWithTag(CompactTags.THRESHOLD_SET).assertExists()
        onNodeWithTag(CompactTags.THRESHOLD).assertDoesNotExist() // the read-only row is replaced by the editor
    }

    @Test
    fun nonOperator_threshold_isReadOnly_noInput() = runComposeUiTest {
        val model = vm(StubCompactRepository(CompactStatus(true, 500_000, armed = false, running = false)), editable = false)
        setContent { MaterialTheme { CompactPanel(model) } }
        onNodeWithTag(CompactTags.THRESHOLD).assertExists()
        onNodeWithTag(CompactTags.THRESHOLD_INPUT).assertDoesNotExist() // no editable field for a non-operator
        onNodeWithTag(CompactTags.THRESHOLD_SET).assertDoesNotExist()
    }

    @Test
    fun operator_editThresholdAndSave_writesNewValue_serverMirror() = runComposeUiTest {
        val model = vm(StubCompactRepository(CompactStatus(true, 500_000, armed = false, running = false)), editable = true)
        setContent { MaterialTheme { CompactPanel(model) } }
        onNodeWithTag(CompactTags.THRESHOLD_INPUT).performTextClearance() // clear the prefilled current value
        onNodeWithTag(CompactTags.THRESHOLD_INPUT).performTextInput("750000")
        onNodeWithTag(CompactTags.THRESHOLD_SET).performClick()
        waitForIdle()
        assertEquals(750_000, model.state.value.status?.thresholdTokens, "save writes the new threshold (server mirror)")
    }

    @Test
    fun operator_invalidThreshold_showsRangeError_disablesSet() = runComposeUiTest {
        val model = vm(StubCompactRepository(CompactStatus(true, 500_000, armed = false, running = false)), editable = true)
        setContent { MaterialTheme { CompactPanel(model) } }
        onNodeWithTag(CompactTags.THRESHOLD_INPUT).performTextClearance()
        onNodeWithTag(CompactTags.THRESHOLD_INPUT).performTextInput("0") // 0 is outside 1..1,000,000
        onNodeWithTag(CompactTags.THRESHOLD_ERROR).assertExists()
        onNodeWithTag(CompactTags.THRESHOLD_SET).assertIsNotEnabled() // invalid → Set disabled
    }

    @Test
    fun operator_setThreshold_showsTransientConfirm_afterServer() = runComposeUiTest {
        val model = vm(StubCompactRepository(CompactStatus(true, 500_000, armed = false, running = false)), editable = true)
        setContent { MaterialTheme { CompactPanel(model) } }
        onNodeWithTag(CompactTags.THRESHOLD_CONFIRM).assertDoesNotExist() // nothing before a set
        onNodeWithTag(CompactTags.THRESHOLD_INPUT).performTextClearance()
        onNodeWithTag(CompactTags.THRESHOLD_INPUT).performTextInput("750000")
        onNodeWithTag(CompactTags.THRESHOLD_SET).performClick()
        waitForIdle()
        onNodeWithTag(CompactTags.THRESHOLD_CONFIRM).assertExists() // transient INFO confirmation, post-server
    }

    // --- CYP-327 Feature B: per-sequence compact-event list (correlationId-scoped, EventRow reuse) ---

    private fun ev(seq: Long, type: EventType, cid: String) = Event(
        id = "e$seq", ts = 1_000 + seq, seq = seq, agentId = "po", projectId = "team-1",
        type = type, severity = Severity.INFO, correlationId = cid, sessionId = null,
    )

    @Test
    fun operatorEvents_scopeToLastRunCorrelationId_excludeOtherRun_withHeader() = runComposeUiTest {
        val runId = "run-9"
        val status = CompactStatus(
            allowed = true, thresholdTokens = 500_000, armed = false, running = true,
            lastRun = CompactRunSummary(completed = 1, total = 2, startedTs = 1, finishedTs = null, correlationId = runId),
        )
        // Two events for run-9 + one for a DIFFERENT run — the panel must show only run-9's, scoped by correlationId.
        val all = listOf(
            ev(2, EventType.COMPACT_PREPARE_SENT, runId),
            ev(3, EventType.COMPACT_COMPLETED, runId),
            ev(1, EventType.COMPACT_PREPARE_SENT, "other-run"),
        )
        val model = vm(StubCompactRepository(status), editable = true)
        setContent { MaterialTheme { CompactPanel(model, compactEvents = all) } }
        onNodeWithTag(CompactTags.EVENTS).assertExists()
        onNodeWithTag(CompactTags.RUN_HEADER).assertExists() // running → "Current run"
        onNodeWithTag(CompactTags.eventRow(0)).assertExists()
        onNodeWithTag(CompactTags.eventRow(1)).assertExists()
        onNodeWithTag(CompactTags.eventRow(2)).assertDoesNotExist() // only 2 events belong to run-9
    }

    @Test
    fun operatorEvents_noRun_showsHonestEmptyState() = runComposeUiTest {
        val model = vm(StubCompactRepository(CompactStatus(true, 500_000, armed = false, running = false, lastRun = null)), editable = true)
        setContent { MaterialTheme { CompactPanel(model, compactEvents = emptyList()) } }
        onNodeWithTag(CompactTags.EVENTS_EMPTY).assertExists()
        onNodeWithTag(CompactTags.EVENTS).assertDoesNotExist()
    }

    @Test
    fun nonOperator_hasNoEventSection() = runComposeUiTest {
        val model = vm(StubCompactRepository(CompactStatus(true, 500_000, armed = false, running = false)), editable = false)
        setContent { MaterialTheme { CompactPanel(model) } } // compactEvents = null (member)
        onNodeWithTag(CompactTags.EVENTS).assertDoesNotExist()
        onNodeWithTag(CompactTags.EVENTS_EMPTY).assertDoesNotExist()
    }
}
