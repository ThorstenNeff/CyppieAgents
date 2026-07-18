package com.tneff.cyppieagents.agentview

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-342 — the guard on the guard: does the zone pinned by the BUILD actually reach the BROWSER?
 *
 * `karma.config.d/timezone.js` sets `process.env.TZ`, and karma launches Chrome as a child process that is
 * expected to inherit it. That inheritance is the load-bearing assumption of the whole CYP-342 fix, and it was
 * an assumption — the ticket says so in as many words ("ob `environment(...)` auf dem Task dasselbe leistet,
 * ist beim Umsetzen zu pruefen und **nicht** anzunehmen"). Measured here instead: under an ambient `TZ=UTC`
 * host, headless Chrome reports `America/St_Johns` / `getTimezoneOffset() == 150`.
 *
 * Why this test has to exist at all: every sign and minute assertion in [TranscriptTimeWasmTest] is only
 * non-vacuous BECAUSE of that zone. Delete `timezone.js`, or have a karma upgrade stop forwarding the env, and
 * those tests keep passing — silently, under UTC, asserting `-0 == 0` again. That is the exact CYP-342 disease
 * one level up: a check whose precondition nothing checks. This test fails loudly in that case.
 *
 * It pins the three PROPERTIES `timezone.js` claims, not the zone's NAME. The name is not a stable identifier
 * (ICU normalises `Asia/Kolkata` to `Asia/Calcutta`, and zone aliases shift between Chrome releases), so
 * asserting the string would be a brittleness with no safety payoff. The properties are what the assertions in
 * [TranscriptTimeWasmTest] actually consume.
 */
class Cyp342PinnedZoneReachesTheBrowserTest {

    @Test
    fun pinnedZone_reachesTheBrowser_andIsNonVacuousForTheTimeAssertions() {
        val offsetMinutes = browserOffsetMinutes()
        val zone = browserResolvedZone()
        val diag = "browser resolved zone='$zone', getTimezoneOffset()=$offsetMinutes " +
            "(pinned in app/shared/karma.config.d/timezone.js)"

        // 1. NOT UTC — the property the sign assertion lives or dies on. Under UTC the offset is 0 and a dropped
        //    minus is invisible (`-0 == 0`), which is the bug this whole ticket is about.
        assertTrue(offsetMinutes != 0, "CYP-342: browser is at UTC — the build's TZ pin did not reach it, so the sign assertions in TranscriptTimeWasmTest are VACUOUS. $diag")

        // 2. A HALF-HOUR component — CYP-343's `HH:mm` assertion is blind to a dropped 30-minute term in a
        //    whole-hour zone for most of the day.
        assertTrue(offsetMinutes % 60 != 0, "CYP-342: zone is at a whole-hour offset — the minute field of the CYP-343 HH:mm assertion is not exercised. $diag")

        // 3. WEST of UTC (getTimezoneOffset counts minutes BEHIND UTC, so positive = west) — drives the
        //    floor-mod in formatHhMm across the day boundary.
        assertTrue(offsetMinutes > 0, "CYP-342: zone is east of UTC — the floor-mod across the day boundary in formatLocalHhMm is not exercised. $diag")
    }
}

private fun browserResolvedZone(): String = js("Intl.DateTimeFormat().resolvedOptions().timeZone")

private fun browserOffsetMinutes(): Int = js("new Date().getTimezoneOffset()")
