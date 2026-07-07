package com.tneff.cyppieagents.report

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-277 — the clickable Product-Lead snapshot row now announces its selectable purpose (report type + as-of)
 * to a screen reader, not just its two child texts.
 *
 * Mutation proof: drop the `.semantics { contentDescription = snapshotA11y }` on the snapshot Column → no node
 * carries a "Select report …" contentDescription → the assertion REDs.
 */
@OptIn(ExperimentalTestApi::class)
class ProductLeadA11yTest {

    @Test
    fun snapshotRow_hasContentDescription_namingTheSelectableReport() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember { ProductLeadViewModel(StubReportRepository(), accessible = true) }
                ProductLeadPanel(vm)
            }
        }
        // Generate a report → a snapshot row appears in the list.
        onNodeWithTag(ProductLeadTags.TRIGGER_DEFECTS).performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(ProductLeadTags.DETAIL_ADVISORY).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            onAllNodesWithContentDescription("Select report", substring = true).fetchSemanticsNodes().isNotEmpty(),
            "the generated snapshot row carries a 'Select report …' contentDescription",
        )
    }
}
