package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-335 — the wasm actuals of [TranscriptClock] run in a REAL browser.
 *
 * The commonTest formatter suite injects a fake clock, so it never touches `Date.now()` /
 * `new Date(x).getTimezoneOffset()`. Those `js()` bridges compile cleanly and can still fail at runtime (the
 * CYP-216 lesson: a wasm runtime error is invisible to `compile` + `jvmTest`). This test executes them under
 * headless Chrome, and renders one real transcript row so the gutter is exercised through Compose/skiko too.
 */
@OptIn(ExperimentalTestApi::class)
class TranscriptTimeWasmTest {

    private val clock = platformTranscriptClock()

    @Test
    fun browserClock_returnsAPlausibleWallClock() {
        // Not a fixed instant (the test would rot); a floor the browser cannot be below. 2026-01-01T00:00:00Z.
        assertTrue(clock.nowMs() > 1_767_225_600_000L, "Date.now() must return a real epoch-ms, got ${clock.nowMs()}")
    }

    @Test
    fun browserOffset_isAWholeMinuteAndWithinTheRangeOfRealZones() {
        val offset = clock.utcOffsetMs(clock.nowMs())
        assertEquals(0L, offset % 60_000L, "getTimezoneOffset() is in minutes; the conversion must stay whole")
        // Real zones span UTC-12:00 … UTC+14:00. A sign error (the `-` on getTimezoneOffset) still lands inside
        // this window, so the sign is pinned separately below.
        assertTrue(offset in -12 * 3_600_000L..14 * 3_600_000L, "offset out of the range of real zones: $offset")
    }

    @Test
    fun browserOffset_hasTheSignConventionCommonMainExpects() {
        // `getTimezoneOffset()` counts minutes BEHIND UTC, so it must be negated. Karma runs the browser in the
        // host's zone; whichever it is, `local = utc + offset` must reproduce the browser's own local hour.
        // Both readings use the SAME instant, so this cannot flake across an hour boundary.
        val nowMs = clock.nowMs()
        val fromOurSeam = formatLocalHhMm(nowMs, clock).substringBefore(':').toInt()
        assertEquals(browserLocalHours(nowMs), fromOurSeam, "our offset sign must agree with the browser's own hour")
    }

    @Test
    fun formatLocalHhMm_rendersTwoZeroPaddedFields() {
        val rendered = formatLocalHhMm(clock.nowMs(), clock)
        assertTrue(Regex("""^\d{2}:\d{2}$""").matches(rendered), "expected HH:mm, got '$rendered'")
    }

    @Test
    fun transcriptRow_rendersItsTimestampInTheBrowser() = runComposeUiTest {
        // 2026-07-09T20:19:46Z. The row must show whatever THIS browser's zone makes of it — computed through the
        // same seam, so the assertion holds in CI (UTC) and on a developer's machine (Berlin) alike.
        val stamp = 1_783_628_386_000L
        val expected = formatLocalHhMm(stamp, clock)
        val session = object : AgentSession {
            override val events: Flow<AgentEvent> =
                flowOf(AgentEvent.AssistantText("a-1", "hallo", complete = true, tsMs = stamp))
            override fun sendMessage(text: String) {}
        }
        setContent {
            MaterialTheme {
                val vm = remember { AgentViewModel(session, "backend") }
                AgentWindow(agentId = "backend", viewModel = vm)
            }
        }
        onNodeWithText(expected).assertExists()
    }
}

/** The browser's own local hour at [atEpochMs], read independently of [TranscriptClock] to cross-check the sign. */
private fun browserLocalHours(atEpochMs: Long): Int = jsLocalHours(atEpochMs.toDouble())

private fun jsLocalHours(atEpochMs: Double): Int = js("new Date(atEpochMs).getHours()")
