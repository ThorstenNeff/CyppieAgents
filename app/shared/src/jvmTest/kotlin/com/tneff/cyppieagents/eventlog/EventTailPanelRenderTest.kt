package com.tneff.cyppieagents.eventlog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
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

/**
 * CYP-42: the Live-Tail panel renders the stream against the stub source and honours the disclosure
 * anchors (event-log-tags.md §3): paused != live, and a `log.dropped` event surfaces a gap row.
 */
@OptIn(ExperimentalTestApi::class)
class EventTailPanelRenderTest {

    @Test
    fun narrowWidth_tailHeaderChipsWrap_pauseStaysOnScreen() = runComposeUiTest {
        val vm = EventTailViewModel(StubEventsSource())
        setContent { MaterialTheme { Box(Modifier.width(360.dp).height(700.dp)) { EventTailPanel(vm) } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(EventTailTags.FILTER_PROJECT).fetchSemanticsNodes().isNotEmpty() }
        // CYP-158 §2.1: Pause control stays prominent on the first line; the filter chips flow in a
        // FlowRow below → the trailing severity + project chips stay ON-SCREEN at a narrow width
        // (un-wrapped they'd clip off the right edge). Pause/Live disclosure unchanged.
        onNodeWithTag(EventTailTags.PAUSE_TOGGLE).assertIsDisplayed()
        onNodeWithTag(EventTailTags.FILTER_SEVERITY).assertIsDisplayed()
        onNodeWithTag(EventTailTags.FILTER_PROJECT).assertIsDisplayed()
    }

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
            id = "g1", ts = 1, seq = 1, agentId = "backend", projectId = "t",
            type = EventType.LOG_DROPPED, severity = Severity.WARN,
            detail = buildJsonObject { put("count", "5") },
        )
        val vm = EventTailViewModel(StubEventsSource(scripted = listOf(gap)))
        setContent { MaterialTheme { EventTailPanel(vm) } }

        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(EventTailTags.row(0)).fetchSemanticsNodes().isNotEmpty() }
        // The gap qualifier tag lives on the rail child → unmerged lookup.
        onNodeWithTag(EventTailTags.row(0, "gap"), useUnmergedTree = true).assertExists()
    }
}
