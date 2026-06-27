package com.tneff.cyppieagents.eventlog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

/**
 * CYP-41: the Browse panel renders the master table against the stub API, opens the detail pane with
 * the two explicit drilldown actions on selection, and enters the correlation drilldown on "show run".
 */
@OptIn(ExperimentalTestApi::class)
class EventBrowsePanelRenderTest {

    @Test
    fun table_select_detail_drilldown() = runComposeUiTest {
        val vm = EventBrowseViewModel(StubEventsApi())
        setContent { MaterialTheme { EventBrowsePanel(vm) } }

        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(EventBrowseTags.row(0)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(EventBrowseTags.TABLE).assertExists()

        // Select the first row → detail pane with both drilldown actions (§6.3).
        onNodeWithTag(EventBrowseTags.row(0)).performClick()
        waitForIdle()
        onNodeWithTag(EventBrowseTags.DETAIL).assertExists()
        onNodeWithTag(EventBrowseTags.DETAIL_SHOW_RUN).assertExists()
        onNodeWithTag(EventBrowseTags.DETAIL_SHOW_SESSION).assertExists()

        // "Show the whole run" → the correlation drilldown timeline.
        onNodeWithTag(EventBrowseTags.DETAIL_SHOW_RUN).performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(EventBrowseTags.DRILLDOWN).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(EventBrowseTags.DRILLDOWN_HEADER).assertExists()
    }

    @Test
    fun filterChip_appliesFilter_showsActiveSubset() = runComposeUiTest {
        val vm = EventBrowseViewModel(StubEventsApi())
        setContent { MaterialTheme { EventBrowsePanel(vm) } }

        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(EventBrowseTags.row(0)).fetchSemanticsNodes().isNotEmpty() }
        // Tap the severity filter chip → applyFilter → the "filter active – subset" cue appears.
        onNodeWithTag(EventBrowseTags.FILTER_SEVERITY).performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(EventBrowseTags.FILTER_ACTIVE).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(EventBrowseTags.FILTER_ACTIVE).assertExists()
    }

    /**
     * CYP-47 P1-A regression: on a narrow phone tile the master-detail must collapse to a SINGLE pane
     * (EVENT-LOG-UI §6.1). Before the fix a hard 300dp detail starved the master `weight(1f)` to 0dp →
     * the FilterBar/Table never composed → every `eventBrowse.*` testTag vanished (the Android-Maestro
     * failure). At ~140dp the table must be present, selection must navigate to the detail, and Back must
     * return to the table.
     */
    @Test
    fun narrowWidth_collapsesToSinglePane_selectionNavigates_backReturns() = runComposeUiTest {
        val vm = EventBrowseViewModel(StubEventsApi())
        setContent { MaterialTheme { Box(Modifier.width(140.dp).height(600.dp)) { EventBrowsePanel(vm) } } }

        // Master table composed full-width on the narrow tile; detail not shown until a selection.
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(EventBrowseTags.row(0)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(EventBrowseTags.TABLE).assertExists()
        onNodeWithTag(EventBrowseTags.DETAIL).assertDoesNotExist()

        // Select → navigate to the detail; the table is no longer present (single-pane).
        onNodeWithTag(EventBrowseTags.row(0)).performClick()
        waitForIdle()
        onNodeWithTag(EventBrowseTags.DETAIL).assertExists()
        onNodeWithTag(EventBrowseTags.DETAIL_SHOW_RUN).assertExists()
        onNodeWithTag(EventBrowseTags.TABLE).assertDoesNotExist()

        // Back → return to the master table.
        onNodeWithTag(EventBrowseTags.BACK).performClick()
        waitForIdle()
        onNodeWithTag(EventBrowseTags.TABLE).assertExists()
        onNodeWithTag(EventBrowseTags.DETAIL).assertDoesNotExist()
    }
}
