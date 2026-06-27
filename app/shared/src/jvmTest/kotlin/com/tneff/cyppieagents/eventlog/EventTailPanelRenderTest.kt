package com.tneff.cyppieagents.eventlog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-42: the Live-Tail panel renders the stream against the stub source and honours the disclosure
 * anchors (event-log-tags.md §3): paused != live, and a `log.dropped` event surfaces a gap row.
 */
@OptIn(ExperimentalTestApi::class)
class EventTailPanelRenderTest {

    @Test
    fun streams_showsLive_thenPauseHidesLive() = runComposeUiTest {
        val vm = EventTailViewModel(StubEventsSource())
        setContent { MaterialTheme { EventTailPanel(vm) } }

        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(EventTailTags.row(0)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(EventTailTags.STREAM).assertExists()
        onNodeWithTag(EventTailTags.LIVE_INDICATOR).assertExists()

        // Pause: paused != live — the live ● disappears, the paused marker appears (§7.2).
        onNodeWithTag(EventTailTags.PAUSE_TOGGLE).performClick()
        waitForIdle()
        onNodeWithTag(EventTailTags.PAUSED_INDICATOR).assertExists()
        onNodeWithTag(EventTailTags.LIVE_INDICATOR).assertDoesNotExist()
    }

    @Test
    fun logDropped_rendersGapRow() = runComposeUiTest {
        val gap = Event(
            id = "g1", ts = 1, seq = 1, agentId = "backend", teamId = "t",
            type = EventType.LOG_DROPPED, severity = Severity.WARN,
            detail = buildJsonObject { put("count", "5") },
        )
        val vm = EventTailViewModel(StubEventsSource(scripted = listOf(gap)))
        setContent { MaterialTheme { EventTailPanel(vm) } }

        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(EventTailTags.row(0)).fetchSemanticsNodes().isNotEmpty() }
        // The gap qualifier tag lives on the rail child → unmerged lookup.
        onNodeWithTag(EventTailTags.row(0, "gap"), useUnmergedTree = true).assertExists()
    }

    /**
     * CYP-47 P1-B regression (made to BITE — reviewer note): the VM reaches LIVE on `Connected` (statusOf),
     * so the failure was never state — it was geometric: on the narrow tile the non-wrapping header `Row`
     * laid its children out past the right edge, so Maestro `assertVisible` failed. The live ● alone is a
     * VACUOUS target (2nd child, ~123dp → fits 140dp whether Row or FlowRow); the elements that actually
     * overflow are the LATER filter labels. So we assert the **rightmost** header child (`filter_severity`,
     * the last one) lays out WITHIN the tile width. With the header as a `FlowRow` it wraps and every child
     * is contained; mutate `FlowRow`→`Row` and this child's right edge shoots past the tile → RED.
     */
    @Test
    fun narrowWidth_headerWraps_rightmostChildStaysWithinTile() = runComposeUiTest {
        val tile = 140.dp
        val vm = EventTailViewModel(StubEventsSource())
        setContent { MaterialTheme { Box(Modifier.width(tile).height(600.dp)) { EventTailPanel(vm) } } }

        // Header composed + VM live (statusOf(Connected)=LIVE) before we measure.
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(EventTailTags.LIVE_INDICATOR).fetchSemanticsNodes().isNotEmpty() }
        // BITE on BOTH failure modes of a non-wrapping Row at narrow width: the last child is either
        // squeezed toward 0-width (Row clamps the depleted remaining-width constraint) OR pushed past the
        // edge. So require real width AND containment. A wrapping FlowRow satisfies both; a Row fails width.
        val b = onNodeWithTag(EventTailTags.FILTER_SEVERITY, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val width = b.right - b.left
        assertTrue(
            width >= 20.dp && b.right <= tile,
            "rightmost header child (filter_severity) must lay out with real width WITHIN the $tile tile " +
                "(width=$width, right=${b.right}) — header must wrap, not squeeze/overflow",
        )
    }
}
