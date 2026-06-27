package com.tneff.cyppieagents.eventlog

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
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
}
