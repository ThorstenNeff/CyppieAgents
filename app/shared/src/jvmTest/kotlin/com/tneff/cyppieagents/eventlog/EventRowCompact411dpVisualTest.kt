package com.tneff.cyppieagents.eventlog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-158 — Tester visual-confirm at the **Android-empirical Compact width 411dp** (the EventRow reflow
 * breakpoint is 560dp, so 411dp is in 2-line mode). Confirms the three things the PO asked for, at the
 * exact width, with **real-bounds** assertions (the CYP-159 idiom):
 *  1. the shared [EventRow] renders ≤2-line and the trailing identity-line cells stay ON-SCREEN, and
 *     ⭐ the **correlation-ID no longer vertically char-stacks** (`r-u-n--1`, the exact earlier finding) —
 *     pinned via the row's real height: a char-stacked corrId balloons the row to many lines.
 *  2. the EventBrowse FilterBar chips wrap (FlowRow), trailing chips on-screen.
 *  3. the EventTail TailHeader chips wrap (FlowRow), Pause + trailing chips on-screen.
 * Same tags as the 1-line layout (CYP-7 contract: no new tags/keys introduced).
 */
@OptIn(ExperimentalTestApi::class)
class EventRowCompact411dpVisualTest {

    private val compact = 411.dp

    private fun ev() = Event(
        id = "e1", ts = 1L, seq = 1L, agentId = "backend", projectId = "proj-1",
        type = EventType.TOOL_CALL, severity = Severity.ERROR, correlationId = "run-1234abcd",
    )

    @Test
    fun eventRow_at411dp_isTwoLine_corrIdNotVerticallySqueezed() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.width(compact).height(400.dp)) {
                    EventRow(ev(), rowTag = "row", qualifierTag = "qual", byIdTag = "byId", showProject = true, projectTag = "proj")
                }
            }
        }
        onNodeWithTag("row").assertExists()
        // Sanity: the identity-line cells (project, type, severity rail) are present. NOTE: at 411dp these
        // stay displayed in BOTH the 1- and 2-line layouts — so this is a presence sanity, NOT the test's
        // teeth. The regression the 1-line layout actually introduces at 411dp is the corrId char-stacking
        // (it has horizontal room only on its own line), which balloons the row height — pinned below.
        onNodeWithTag("qual").assertIsDisplayed()
        onNodeWithTag("byId").assertIsDisplayed()
        onNodeWithTag("proj").assertIsDisplayed()
        // ⭐ THE teeth of this test (the exact finding): the 2-line grouping keeps the corrId on one
        // horizontal line, so the row stays ~2 lines tall (measured ~54dp). Forcing the 1-line layout at
        // 411dp char-stacks the corrId and balloons the row to ~120dp (empirically verified) → the 80dp
        // bound catches exactly that. (The reflow THRESHOLD itself is guarded by EventRowReflowTest; this
        // test's unique value is this height / char-stack guard, not the threshold.)
        val rowBounds = onNodeWithTag("row").getBoundsInRoot()
        val rowHeight = rowBounds.bottom - rowBounds.top
        println("CYP-158 visual-confirm @411dp: EventRow height = $rowHeight (2-line; corrId not squeezed)")
        assertTrue(rowHeight.value < 80f, "EventRow @411dp must stay ~2-line — corrId vertically squeezed? measured height=$rowHeight")
    }

    @Test
    fun filterBar_at411dp_wraps_trailingChipsOnScreen() = runComposeUiTest {
        val vm = EventBrowseViewModel(StubEventsApi())
        setContent { MaterialTheme { Box(Modifier.width(compact).height(700.dp)) { EventBrowsePanel(vm) } } }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(EventBrowseTags.FILTER_CORRELATION).fetchSemanticsNodes().isNotEmpty()
        }
        // FilterBar Row → FlowRow: the trailing time-window + correlation chips stay on-screen at 411dp.
        onNodeWithTag(EventBrowseTags.FILTER_TIME_WINDOW).assertIsDisplayed()
        onNodeWithTag(EventBrowseTags.FILTER_CORRELATION).assertIsDisplayed()
    }

    @Test
    fun tailHeader_at411dp_wraps_pauseAndTrailingChipsOnScreen() = runComposeUiTest {
        val vm = EventTailViewModel(StubEventsSource())
        setContent { MaterialTheme { Box(Modifier.width(compact).height(700.dp)) { EventTailPanel(vm) } } }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(EventTailTags.FILTER_PROJECT).fetchSemanticsNodes().isNotEmpty()
        }
        // TailHeader chips flow in a FlowRow below the Pause control: Pause + trailing severity/project on-screen.
        onNodeWithTag(EventTailTags.PAUSE_TOGGLE).assertIsDisplayed()
        onNodeWithTag(EventTailTags.FILTER_SEVERITY).assertIsDisplayed()
        onNodeWithTag(EventTailTags.FILTER_PROJECT).assertIsDisplayed()
    }
}
