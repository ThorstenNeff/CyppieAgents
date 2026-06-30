package com.tneff.cyppieagents.report

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.ReportType
import kotlin.test.Test

/**
 * CYP-156 (Klasse A): the Product-Lead panel collapses its snapshot-list/detail master/detail to a
 * SINGLE pane below `PANE_COLLAPSE_WIDTH`. The TriggerBar stays put (it is not a pane); only the
 * master/detail adapts, with a new `productLead.back` affordance. Wide = two-pane (unchanged).
 */
@OptIn(ExperimentalTestApi::class)
class ProductLeadPaneCollapseTest {

    @Test
    fun narrowWidth_triggerBarWraps_allButtonsOnScreen() = runComposeUiTest {
        // CYP-156 §3.1: Row → FlowRow. On a 360dp phone the label + 3 buttons don't fit one line; with
        // the FlowRow they wrap, so every trigger button stays ON-SCREEN. (Before the fix the rightmost
        // button overflowed the right edge → not displayed → unreachable to the user/Maestro.)
        setContent {
            MaterialTheme {
                val vm = remember { ProductLeadViewModel(StubReportRepository(), accessible = true) }
                Box(Modifier.width(360.dp).height(700.dp)) { ProductLeadPanel(vm) }
            }
        }
        onNodeWithTag(ProductLeadTags.TRIGGER_USAGE).assertIsDisplayed()
        onNodeWithTag(ProductLeadTags.TRIGGER_STATUS).assertIsDisplayed()
        onNodeWithTag(ProductLeadTags.TRIGGER_DEFECTS).assertIsDisplayed()
    }

    @Test
    fun narrowWidth_collapsesToSinglePane_selectionNavigates_backReturns() = runComposeUiTest {
        // Seed one snapshot via the VM (generate auto-selects it). Driving the VM directly keeps this
        // test on CYP-156's subject — the pane collapse — not the TriggerBar's narrow reachability (a
        // separate CYP-158 concern). The trigger bar's presence is asserted separately below.
        val vm = ProductLeadViewModel(StubReportRepository(), accessible = true)
        vm.generate(ReportType.DEFECTS)
        setContent { MaterialTheme { Box(Modifier.width(360.dp).height(700.dp)) { ProductLeadPanel(vm) } } }

        // Auto-selected → single-pane shows the detail with a back affordance; the list is NOT composed
        // beside it, and the trigger bar stays put above the master/detail (it is not a pane).
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(ProductLeadTags.BACK).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(ProductLeadTags.DETAIL).assertExists()
        onNodeWithTag(ProductLeadTags.TRIGGER).assertExists()
        onNodeWithTag(ProductLeadTags.LIST).assertDoesNotExist()

        // Back → the snapshot list owns the pane; the detail is gone.
        onNodeWithTag(ProductLeadTags.BACK).performClick()
        waitForIdle()
        onNodeWithTag(ProductLeadTags.LIST).assertExists()
        onNodeWithTag(ProductLeadTags.snapshot("rep-0")).assertExists()
        onNodeWithTag(ProductLeadTags.DETAIL).assertDoesNotExist()
        onNodeWithTag(ProductLeadTags.BACK).assertDoesNotExist()

        // Select the snapshot from the list → navigate back to the detail (single-pane again).
        onNodeWithTag(ProductLeadTags.snapshot("rep-0")).performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(ProductLeadTags.BACK).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(ProductLeadTags.DETAIL).assertExists()
        onNodeWithTag(ProductLeadTags.LIST).assertDoesNotExist()
    }

    @Test
    fun wideWidth_keepsTwoPane_noBackAffordance() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember { ProductLeadViewModel(StubReportRepository(), accessible = true) }
                Box(Modifier.width(900.dp).height(700.dp)) { ProductLeadPanel(vm) }
            }
        }

        // Generate → two-pane keeps list AND detail side by side, with no single-pane back affordance.
        onNodeWithTag(ProductLeadTags.TRIGGER_DEFECTS).performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(ProductLeadTags.DETAIL).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(ProductLeadTags.LIST).assertExists()
        onNodeWithTag(ProductLeadTags.DETAIL).assertExists()
        onNodeWithTag(ProductLeadTags.BACK).assertDoesNotExist()
    }
}
