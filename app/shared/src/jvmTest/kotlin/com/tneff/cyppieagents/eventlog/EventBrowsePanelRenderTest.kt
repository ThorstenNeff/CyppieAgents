package com.tneff.cyppieagents.eventlog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test

/**
 * CYP-41: the Browse panel renders the master table against the stub API, opens the detail pane with
 * the two explicit drilldown actions on selection, and enters the correlation drilldown on "show run".
 *
 * CYP-271 de-flake (CYP-263 precedent): the first-page load is driven on an **[Dispatchers.Unconfined]** scope
 * ([browseVm]) so it runs SYNCHRONOUSLY at VM construction (StubEventsApi.query is pure in-memory — no real
 * suspension). The panel therefore composes with the rows already in state, so the tests use only the
 * deterministic [runComposeUiTest.waitForIdle] barrier — never a wall-clock `waitUntil(timeout)` precondition that
 * could starve under parallel CPU load (the old `narrowWidth…backReturns` flake). No load-padding; the assertions
 * (the CYP-41/47 teeth) are unchanged and non-vacuous.
 */
@OptIn(ExperimentalTestApi::class)
class EventBrowsePanelRenderTest {

    /** The load runs synchronously at construction (Unconfined) → the panel renders with rows already present. */
    private fun browseVm() = EventBrowseViewModel(StubEventsApi(), scope = CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun narrowWidth_filterBarWraps_trailingChipsOnScreen() = runComposeUiTest {
        setContent { MaterialTheme { Box(Modifier.width(400.dp).height(700.dp)) { EventBrowsePanel(browseVm()) } } }
        waitForIdle()
        // CYP-158 §2.1: FilterBar Row → FlowRow → the 6 chips wrap on a narrow width so the trailing
        // time-window + correlation chips stay ON-SCREEN (they would clip off the right edge un-wrapped).
        onNodeWithTag(EventBrowseTags.FILTER_TIME_WINDOW).assertIsDisplayed()
        onNodeWithTag(EventBrowseTags.FILTER_CORRELATION).assertIsDisplayed()
    }

    @Test
    fun table_select_detail_drilldown() = runComposeUiTest {
        setContent { MaterialTheme { EventBrowsePanel(browseVm()) } }
        waitForIdle()
        onNodeWithTag(EventBrowseTags.TABLE).assertExists()

        // Select the first row → detail pane with both drilldown actions (§6.3).
        onNodeWithTag(EventBrowseTags.row(0)).performClick()
        waitForIdle()
        onNodeWithTag(EventBrowseTags.DETAIL).assertExists()
        onNodeWithTag(EventBrowseTags.DETAIL_SHOW_RUN).assertExists()
        onNodeWithTag(EventBrowseTags.DETAIL_SHOW_SESSION).assertExists()

        // "Show the whole run" → the correlation drilldown timeline.
        onNodeWithTag(EventBrowseTags.DETAIL_SHOW_RUN).performClick()
        waitForIdle()
        onNodeWithTag(EventBrowseTags.DRILLDOWN_HEADER).assertExists()
    }

    @Test
    fun filterChip_appliesFilter_showsActiveSubset() = runComposeUiTest {
        setContent { MaterialTheme { EventBrowsePanel(browseVm()) } }
        waitForIdle()
        // Tap the severity filter chip → applyFilter → the "filter active – subset" cue appears.
        onNodeWithTag(EventBrowseTags.FILTER_SEVERITY).performClick()
        waitForIdle()
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
        setContent { MaterialTheme { Box(Modifier.width(140.dp).height(600.dp)) { EventBrowsePanel(browseVm()) } } }
        waitForIdle()

        // Master table composed full-width on the narrow tile; detail not shown until a selection.
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
