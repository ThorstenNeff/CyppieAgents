package com.tneff.cyppieagents.eventlog

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-381 (CYP-356) — the 3-stage ResumeOutcome summary rendered in the REAL EventBrowsePanel detail from a
 * `resume.outcome` event's content-free `detail={outcome}` payload:
 *  - **CONTEXT_LOST → WARN amber** — the authoritative unintended-memory-loss signal (never neutral/success);
 *  - **RESUMED_WITH_CONTEXT / FRESH_NO_RESUME → neutral** — a resume that kept context, or a by-design fresh start,
 *    are not failures (never amber, never green);
 *  - a non-`resume.outcome` event (or an unparseable outcome) carries NO summary.
 *  jvmTest render locale = EN.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp381ResumeOutcomeDetailTest {

    private fun resumeEvent(outcome: String, sev: Severity) = Event(
        id = "r1", ts = 3_000, seq = 1, agentId = "backend", projectId = "team-1",
        type = EventType.RESUME_OUTCOME, severity = sev, correlationId = null, sessionId = "sid-1",
        detail = buildJsonObject { put("outcome", outcome) },
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
    fun contextLost_showsWarnAmberSummary() = runComposeUiTest {
        val vm = vmWith(resumeEvent("CONTEXT_LOST", Severity.WARN))
        setContent { MaterialTheme(colorScheme = MaritimeDark) { EventBrowsePanel(vm) } }
        onNodeWithTag(EventBrowseTags.row(0)).performClick()
        onNodeWithTag(EventBrowseTags.DETAIL_RESUME_OUTCOME).assertExists()
        onNodeWithTag(EventBrowseTags.DETAIL_RESUME_OUTCOME).assertTextContains("Context lost", substring = true)
        // Mutation: render CONTEXT_LOST neutral (warn=false) → no amber → RED.
        assertTrue(
            amberPixels(onNodeWithTag(EventBrowseTags.DETAIL_RESUME_OUTCOME, useUnmergedTree = true)) > 0,
            "CONTEXT_LOST is an unintended memory loss → WARN amber, never neutral",
        )
    }

    @Test
    fun resumedWithContext_showsNeutralSummary_noAmber() = runComposeUiTest {
        val vm = vmWith(resumeEvent("RESUMED_WITH_CONTEXT", Severity.INFO))
        setContent { MaterialTheme(colorScheme = MaritimeDark) { EventBrowsePanel(vm) } }
        onNodeWithTag(EventBrowseTags.row(0)).performClick()
        onNodeWithTag(EventBrowseTags.DETAIL_RESUME_OUTCOME).assertExists()
        // Mutation: mark RESUMED_WITH_CONTEXT as warn → amber → RED.
        assertTrue(
            amberPixels(onNodeWithTag(EventBrowseTags.DETAIL_RESUME_OUTCOME, useUnmergedTree = true)) == 0,
            "a resume that kept its context is neutral — never a warning (and never green)",
        )
    }

    @Test
    fun freshNoResume_showsNeutralSummary_noAmber() = runComposeUiTest {
        val vm = vmWith(resumeEvent("FRESH_NO_RESUME", Severity.INFO))
        setContent { MaterialTheme(colorScheme = MaritimeDark) { EventBrowsePanel(vm) } }
        onNodeWithTag(EventBrowseTags.row(0)).performClick()
        onNodeWithTag(EventBrowseTags.DETAIL_RESUME_OUTCOME).assertExists()
        assertTrue(
            amberPixels(onNodeWithTag(EventBrowseTags.DETAIL_RESUME_OUTCOME, useUnmergedTree = true)) == 0,
            "a by-design fresh start is neutral, not a memory-loss warning",
        )
    }

    @Test
    fun nonResumeEvent_hasNoResumeSummary() = runComposeUiTest {
        val turn = Event(
            id = "t1", ts = 1_000, seq = 1, agentId = "backend", projectId = "team-1",
            type = EventType.TURN_START, severity = Severity.INFO, correlationId = "run-1", sessionId = "s1",
        )
        val vm = EventBrowseViewModel(StubEventsApi(listOf(turn)), scope = CoroutineScope(Dispatchers.Unconfined))
        setContent { MaterialTheme { EventBrowsePanel(vm) } }
        onNodeWithTag(EventBrowseTags.row(0)).performClick()
        onNodeWithTag(EventBrowseTags.DETAIL).assertExists()
        onNodeWithTag(EventBrowseTags.DETAIL_RESUME_OUTCOME).assertDoesNotExist()
    }
}
