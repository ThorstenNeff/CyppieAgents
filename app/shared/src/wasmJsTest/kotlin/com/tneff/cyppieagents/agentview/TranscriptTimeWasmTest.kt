package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
        // Real zones span UTC-12:00 … UTC+14:00. A sign error still lands inside this window, so the sign is
        // pinned separately below.
        assertTrue(offset in -12 * 3_600_000L..14 * 3_600_000L, "offset out of the range of real zones: $offset")
    }

    @Test
    fun browserOffset_matchesTheBrowsersOwnWallClock_toTheMinute() {
        // `getTimezoneOffset()` counts minutes BEHIND UTC, so it must be negated, and it is expressed in MINUTES.
        // Whatever zone karma runs in, `local = utc + offset` must reproduce the browser's own wall clock.
        //
        // CYP-343: compare the full `HH:mm`, not just the hour. An hour-only assertion is blind to a half-hour
        // error for most of the day — a dropped 30-minute component only crosses an hour boundary part of the
        // time, so the same mutation passes at 08:56 and fails at 08:26. No choice of timezone repairs that;
        // only the assertion does. The zone is pinned in the build (see `karma.config.d/timezone.js`) so this
        // runs at a NEGATIVE, HALF-HOUR offset that also exercises the floor-mod across the day boundary.
        //
        // Both readings use the SAME instant, so this cannot flake across a minute boundary.
        val nowMs = clock.nowMs()
        val browserClock = "${pad2(browserLocalHours(nowMs))}:${pad2(browserLocalMinutes(nowMs))}"
        assertEquals(
            browserClock,
            formatLocalHhMm(nowMs, clock),
            "our seam must reproduce the browser's own local wall clock, to the minute",
        )
    }

    @Test
    fun formatLocalHhMm_rendersTwoZeroPaddedFields() {
        val rendered = formatLocalHhMm(clock.nowMs(), clock)
        assertTrue(Regex("""^\d{2}:\d{2}$""").matches(rendered), "expected HH:mm, got '$rendered'")
    }

    @Test
    fun transcriptRow_rendersItsTimeCellInTheBrowser() = runComposeUiTest {
        // A REAL Compose/skiko render of the transcript in headless Chrome — the CYP-216 class of failure that
        // compiles cleanly and dies at runtime. Addressed by tag, because the cell's semantics are cleared.
        //
        // What this canNOT assert here: the labelled `contentDescription`. Compose resources do not resolve under
        // the karma runner (see [composeResourcesDoNotResolveUnderKarma] — every `stringResource` reads as ""),
        // so the announcement is pinned on the JVM instead (`TranscriptTimestampRenderTest`). Asserting it here
        // would fail for the environment, not for the code.
        val stamp = 1_783_628_386_000L
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
        onNodeWithTag(AgentViewTags.eventTime("backend", 0), useUnmergedTree = true).assertExists()
    }

    @Test
    fun composeResourcesDoNotResolveUnderKarma() = runComposeUiTest {
        // Pins the environment limitation above so it is a KNOWN fact, not a mystery for the next person: the
        // agent header renders `a11y_agent_status` through `stringResource`, and NO node carries it here. If this
        // test ever starts failing, resources DID become available — then the row above should assert its
        // labelled description in the browser too, and this test should be deleted.
        val session = object : AgentSession {
            override val events: Flow<AgentEvent> = flowOf()
            override fun sendMessage(text: String) {}
        }
        setContent {
            MaterialTheme {
                val vm = remember { AgentViewModel(session, "backend") }
                AgentWindow(agentId = "backend", viewModel = vm)
            }
        }
        val resourceBacked = onAllNodesWithContentDescription("status", substring = true, ignoreCase = true)
            .fetchSemanticsNodes().size
        assertEquals(
            0,
            resourceBacked,
            "compose resources now resolve under karma — assert the labelled time announcement here and drop this test",
        )
    }
}

/** The browser's own local wall clock at [atEpochMs], read independently of [TranscriptClock] to cross-check it. */
private fun browserLocalHours(atEpochMs: Long): Int = jsLocalHours(atEpochMs.toDouble())

private fun browserLocalMinutes(atEpochMs: Long): Int = jsLocalMinutes(atEpochMs.toDouble())

private fun pad2(value: Int): String = value.toString().padStart(2, '0')

private fun jsLocalHours(atEpochMs: Double): Int = js("new Date(atEpochMs).getHours()")

private fun jsLocalMinutes(atEpochMs: Double): Int = js("new Date(atEpochMs).getMinutes()")
