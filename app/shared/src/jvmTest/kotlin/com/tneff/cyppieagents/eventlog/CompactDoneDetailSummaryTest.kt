package com.tneff.cyppieagents.eventlog

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.ui.MaritimeDark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-326 §2.5 — the localized X/N summary for a `compact.orchestration.done` event, from its content-free
 * detail payload (the `CompactRunSummary` keys). A timeout (pendingAgentIds non-empty) renders WARN amber; a
 * full run is neutral; a non-orchestration event carries no summary. Drives the REAL EventBrowsePanel detail.
 */
@OptIn(ExperimentalTestApi::class)
class CompactDoneDetailSummaryTest {

    private fun doneEvent(completed: Int, total: Int, pending: List<String>, sev: Severity) = Event(
        id = "d1", ts = 2_000, seq = 1, agentId = "po", projectId = "team-1",
        type = EventType.COMPACT_ORCHESTRATION_DONE, severity = sev, correlationId = "run-9", sessionId = null,
        detail = buildJsonObject {
            put("completed", completed)
            put("total", total)
            putJsonArray("pendingAgentIds") { pending.forEach { add(it) } }
        },
    )

    private fun vmWith(event: Event) = EventBrowseViewModel(StubEventsApi(listOf(event)), scope = CoroutineScope(Dispatchers.Unconfined))

    private fun amberPixels(node: androidx.compose.ui.test.SemanticsNodeInteraction): Int {
        val pm = node.captureToImage().toPixelMap()
        var amber = 0
        for (y in 0 until pm.height) for (x in 0 until pm.width) {
            val c = pm[x, y]
            // WARN Night amber #FFC857: red≈1.0, green≈0.78, blue≈0.34 — onSurface #DCE7ED has blue≈0.93.
            if (c.red > 0.85f && c.green > 0.6f && c.blue < 0.5f) amber++
        }
        return amber
    }

    @Test
    fun timeout_showsWarnAmberSummary() = runComposeUiTest {
        val vm = vmWith(doneEvent(completed = 3, total = 5, pending = listOf("a", "b"), sev = Severity.WARN))
        setContent { MaterialTheme(colorScheme = MaritimeDark) { EventBrowsePanel(vm) } }
        onNodeWithTag(EventBrowseTags.row(0)).performClick()
        onNodeWithTag(EventBrowseTags.DETAIL_COMPACT_SUMMARY).assertExists()
        assertTrue(
            amberPixels(onNodeWithTag(EventBrowseTags.DETAIL_COMPACT_SUMMARY, useUnmergedTree = true)) > 0,
            "a timeout done-summary must render WARN amber (§3-4), not neutral",
        )
    }

    @Test
    fun fullRun_showsNeutralSummary_noAmber() = runComposeUiTest {
        val vm = vmWith(doneEvent(completed = 5, total = 5, pending = emptyList(), sev = Severity.INFO))
        setContent { MaterialTheme(colorScheme = MaritimeDark) { EventBrowsePanel(vm) } }
        onNodeWithTag(EventBrowseTags.row(0)).performClick()
        onNodeWithTag(EventBrowseTags.DETAIL_COMPACT_SUMMARY).assertExists()
        assertTrue(
            amberPixels(onNodeWithTag(EventBrowseTags.DETAIL_COMPACT_SUMMARY, useUnmergedTree = true)) == 0,
            "a full N/N run is neutral — never amber (and never green)",
        )
    }

    @Test
    fun nonOrchestrationEvent_hasNoCompactSummary() = runComposeUiTest {
        val turn = Event(
            id = "t1", ts = 1_000, seq = 1, agentId = "backend", projectId = "team-1",
            type = EventType.TURN_START, severity = Severity.INFO, correlationId = "run-1", sessionId = "s1",
        )
        val vm = EventBrowseViewModel(StubEventsApi(listOf(turn)), scope = CoroutineScope(Dispatchers.Unconfined))
        setContent { MaterialTheme { EventBrowsePanel(vm) } }
        onNodeWithTag(EventBrowseTags.row(0)).performClick()
        onNodeWithTag(EventBrowseTags.DETAIL).assertExists()
        onNodeWithTag(EventBrowseTags.DETAIL_COMPACT_SUMMARY).assertDoesNotExist()
    }
}
