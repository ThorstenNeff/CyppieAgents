package com.tneff.cyppieagents.report

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/**
 * CYP-90 render gate: the panel is operator-gated/fail-closed (no token → only the gate hint, no trigger,
 * no list), and a generated report carries the snapshot disclosure (prominent "As of" + advisory on the
 * defect register + a reused severity rail). testTags exactly per `docs/design/product-lead-tags.md`.
 */
@OptIn(ExperimentalTestApi::class)
class ProductLeadPanelTest {

    @Test
    fun noOperator_gateHintOnly_noTriggerNoList() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember { ProductLeadViewModel(StubReportRepository(), accessible = false) }
                ProductLeadPanel(vm)
            }
        }
        onNodeWithTag(ProductLeadTags.GATE_HINT).assertExists()
        onNodeWithTag(ProductLeadTags.TRIGGER).assertDoesNotExist()
        onNodeWithTag(ProductLeadTags.LIST).assertDoesNotExist()
    }

    @Test
    fun operator_triggersPresent_emptyStateHonest() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember { ProductLeadViewModel(StubReportRepository(), accessible = true) }
                ProductLeadPanel(vm)
            }
        }
        onNodeWithTag(ProductLeadTags.TRIGGER).assertExists()
        onNodeWithTag(ProductLeadTags.TRIGGER_USAGE).assertExists()
        onNodeWithTag(ProductLeadTags.TRIGGER_STATUS).assertExists()
        onNodeWithTag(ProductLeadTags.TRIGGER_DEFECTS).assertExists()
        onNodeWithTag(ProductLeadTags.EMPTY).assertExists() // no reports yet
    }

    @Test
    fun generateDefects_showsAsOf_advisory_andSeverityRail() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember { ProductLeadViewModel(StubReportRepository(), accessible = true) }
                ProductLeadPanel(vm)
            }
        }
        onNodeWithTag(ProductLeadTags.TRIGGER_DEFECTS).performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(ProductLeadTags.DETAIL_ADVISORY).fetchSemanticsNodes().isNotEmpty()
        }
        // Snapshot ≠ live: the prominent "As of" is present.
        onNodeWithTag(ProductLeadTags.DETAIL_AS_OF).assertExists()
        // Advisory disclosure for the defect register.
        onNodeWithTag(ProductLeadTags.DETAIL_ADVISORY).assertExists()
        // Reused severity rail on the first defect line (ERROR in the stub) — colour + symbol + label.
        onNodeWithTag(ProductLeadTags.defectSeverity(0, "error"), useUnmergedTree = true).assertExists()
    }
}
