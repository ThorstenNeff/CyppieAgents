package com.tneff.cyppieagents.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

/**
 * CYP-57 (Phone-Pager polish) guards for the two UIUX low-findings that have local coverage:
 *
 *  * **Hit-area (WCAG 2.5.8):** the dots + prev/next affordances must be ≥ 48 dp touch targets —
 *    asserted on the **real bounds** (`assertWidthIsAtLeast`/`assertHeightIsAtLeast`), not just node
 *    presence (mirrors the CYP-52 lesson that `assertExists` can't catch an undersized node).
 *  * **Omission count:** the indicator must reflect only the windows that actually exist — no phantom
 *    dot/page for an operator-gated window that was omitted (the same honesty contract as CYP-41/42).
 *
 * (The third finding — RTL chevron mirroring — is a visual/`graphicsLayer` change with no headless
 * signal; it is verified by the iOS/Android device pass.)
 */
@OptIn(ExperimentalTestApi::class)
class PhonePagerPolishTest {

    private fun state(vararg ids: String) = WindowManagerState(
        ids.map { WindowState(it, it.replaceFirstChar(Char::uppercase), 0f, 0f, 200f, 150f) },
    )

    /** Compact viewport → the pager renders. */
    private fun phoneContent(state: WindowManagerState): @androidx.compose.runtime.Composable () -> Unit = {
        MaterialTheme {
            Box(Modifier.size(360.dp, 700.dp)) {
                WindowHost(state = state, windowContent = { Text("stub ${it.id}") })
            }
        }
    }

    @Test
    fun hitTargets_dotsAndPrevNext_areAtLeast48dp() = runComposeUiTest {
        val st = state("po", "frontend", "backend") // multi-window → indicator + prev/next present
        setContent(phoneContent(st))

        for (tag in listOf(PhonePagerTags.PREV, PhonePagerTags.NEXT, PhonePagerTags.dot("po"))) {
            onNodeWithTag(tag).assertWidthIsAtLeast(48.dp)
            onNodeWithTag(tag).assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun omission_absentOperatorWindows_showNoPhantomDots() = runComposeUiTest {
        // No operator token → AgentShell omits the event-log windows; the pager must show no dot for them.
        val st = state("po", "frontend", "backend", "comm", "acl")
        setContent(phoneContent(st))

        // The five real windows each have a dot ...
        for (id in listOf("po", "frontend", "backend", "comm", "acl")) {
            onNodeWithTag(PhonePagerTags.dot(id)).assertExists()
        }
        // ... and the omitted operator-gated windows have NONE (no phantom page/dot).
        onNodeWithTag(PhonePagerTags.dot("eventlog")).assertDoesNotExist()
        onNodeWithTag(PhonePagerTags.dot("eventtail")).assertDoesNotExist()
    }

    @Test
    fun omission_presentOperatorWindows_includeTheirRealDots() = runComposeUiTest {
        // Operator present → event-log windows exist → they ARE real pages (6 ≤ threshold → dots).
        val st = state("po", "frontend", "backend", "comm", "acl", "eventlog")
        setContent(phoneContent(st))

        onNodeWithTag(PhonePagerTags.dot("eventlog")).assertExists()
        onNodeWithTag(PhonePagerTags.dot("acl")).assertExists()
    }

    @Test
    fun counter_aboveThreshold_showsTruePageCount() = runComposeUiTest {
        // 7 windows (> dot threshold) → the compact "N / M" counter, not dots; M is the TRUE count.
        val st = state("po", "frontend", "backend", "comm", "acl", "eventlog", "eventtail")
        setContent(phoneContent(st))

        onNodeWithTag(PhonePagerTags.INDICATOR_POSITION).assertExists()
        // Beyond the threshold there are no per-page dots (the counter replaces them).
        onNodeWithTag(PhonePagerTags.dot("po")).assertDoesNotExist()

        // The pager opens on the focused (last) page, where current == total, so step back one page to
        // decouple the two: the displayed total "7" must then be distinct from the current page "6".
        // This pins the TOTAL specifically — a phantom-inflated count (e.g. "von 9") turns this RED.
        onNodeWithTag(PhonePagerTags.PREV).performClick()
        waitForIdle()
        onNodeWithTag(PhonePagerTags.INDICATOR_POSITION).assertTextContains("7", substring = true)
    }
}
