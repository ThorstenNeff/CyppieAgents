package com.tneff.cyppieagents.agentview

import kotlin.js.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-335 — the **js** actual of [TranscriptClock], executed in a real browser (`jsBrowserTest`, karma).
 *
 * It is not the wasm one. `TranscriptTime.js.kt` is separate code with its own sign handling
 * (`-(Date(x).getTimezoneOffset() * 60_000)`), and until this file existed nothing ever ran it: the commonTest
 * formatter suite injects a fake clock, and `TranscriptTimeWasmTest` only covers `wasmJs`. A dropped negation
 * here would compile, ship in the js build, and silently render every time in the wrong zone.
 *
 * Deliberately clock-only, no Compose: the renderer is proven on jvm + wasmJs. What is unique to this target is
 * the two-line bridge to `Date`.
 *
 * The zone is pinned in the build (`karma.config.d/timezone.js` → `America/St_Johns`, a NEGATIVE, HALF-HOUR
 * offset). Under `TZ=UTC` the sign assertion would be vacuous (`-0 == 0`) and a whole-hour zone would never
 * exercise the 30-minute component. The assertion itself compares the full `HH:mm` (CYP-343) — the zone makes it
 * non-vacuous, the assertion makes it correct.
 */
class TranscriptTimeJsTest {

    private val clock = platformTranscriptClock()

    @Test
    fun browserClock_returnsAPlausibleWallClock() {
        // A floor the browser cannot be below rather than a fixed instant (which would rot): 2026-01-01T00:00:00Z.
        assertTrue(clock.nowMs() > 1_767_225_600_000L, "Date.now() must return a real epoch-ms, got ${clock.nowMs()}")
    }

    @Test
    fun browserOffset_isAWholeMinuteAndWithinTheRangeOfRealZones() {
        val offset = clock.utcOffsetMs(clock.nowMs())
        assertEquals(0L, offset % 60_000L, "getTimezoneOffset() is in minutes; the conversion must stay whole")
        assertTrue(offset in -12 * 3_600_000L..14 * 3_600_000L, "offset out of the range of real zones: $offset")
    }

    @Test
    fun browserOffset_matchesTheBrowsersOwnWallClock_toTheMinute() {
        // Compare the full HH:mm, not just the hour: a dropped half-hour crosses an hour boundary only part of
        // the day, so an hour-only assertion passes at 08:56 and fails at 08:26 (CYP-343). Both readings use the
        // SAME instant, so this cannot flake across a minute boundary.
        val nowMs = clock.nowMs()
        val date = Date(nowMs.toDouble())
        val browserClock = "${pad2(date.getHours())}:${pad2(date.getMinutes())}"
        assertEquals(
            browserClock,
            formatLocalHhMm(nowMs, clock),
            "the js seam must reproduce the browser's own local wall clock, to the minute",
        )
    }

    @Test
    fun formatLocalHhMm_rendersTwoZeroPaddedFields() {
        val rendered = formatLocalHhMm(clock.nowMs(), clock)
        assertTrue(Regex("""^\d{2}:\d{2}$""").matches(rendered), "expected HH:mm, got '$rendered'")
    }
}

private fun pad2(value: Int): String = value.toString().padStart(2, '0')
