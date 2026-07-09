package com.tneff.cyppieagents.agentview

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-335 — the pure `HH:mm` formatter. [formatHhMm] is the whole timezone story that lives in commonMain;
 * the platforms only supply the offset, so this is where the format is pinned.
 */
class TranscriptTimeTest {

    private val hour = 3_600_000L
    private val minute = 60_000L

    /** 1970-01-01T00:00:00Z — every case below is an offset from this, so the arithmetic stays readable. */
    private val epoch = 0L

    @Test
    fun utc_formatsHoursAndMinutes() {
        assertEquals("14:03", formatHhMm(epoch + 14 * hour + 3 * minute, offsetMs = 0L))
    }

    @Test
    fun midnight_isZeroZeroZeroZero() {
        assertEquals("00:00", formatHhMm(epoch, offsetMs = 0L))
    }

    @Test
    fun justAfterMidnight_padsBothFields() {
        // The `00:0x` case the story calls out — a naive `"$h:$m"` renders "0:7".
        assertEquals("00:07", formatHhMm(epoch + 7 * minute, offsetMs = 0L))
    }

    @Test
    fun singleDigitHourAndMinute_getLeadingZeros() {
        assertEquals("09:04", formatHhMm(epoch + 9 * hour + 4 * minute, offsetMs = 0L))
    }

    @Test
    fun positiveOffset_shiftsForward() {
        // Berlin in summer (UTC+2): 07:14 UTC is 09:14 local.
        assertEquals("09:14", formatHhMm(epoch + 7 * hour + 14 * minute, offsetMs = 2 * hour))
    }

    @Test
    fun negativeOffset_shiftsBackward() {
        // New York (UTC-5): 14:03 UTC is 09:03 local.
        assertEquals("09:03", formatHhMm(epoch + 14 * hour + 3 * minute, offsetMs = -5 * hour))
    }

    @Test
    fun negativeOffset_wrappingBackAcrossMidnight_showsPreviousDaysClock() {
        // 02:00 UTC in New York (UTC-5) is 21:00 the previous day. A plain `%` would go negative here.
        assertEquals("21:00", formatHhMm(epoch + 2 * hour, offsetMs = -5 * hour))
    }

    @Test
    fun positiveOffset_wrappingForwardAcrossMidnight_showsNextDaysClock() {
        // 23:30 UTC at UTC+2 is 01:30 the next day.
        assertEquals("01:30", formatHhMm(epoch + 23 * hour + 30 * minute, offsetMs = 2 * hour))
    }

    @Test
    fun halfHourOffset_isHandled() {
        // India (UTC+5:30) — not every zone is a whole hour off.
        assertEquals("12:30", formatHhMm(epoch + 7 * hour, offsetMs = 5 * hour + 30 * minute))
    }

    @Test
    fun preEpochInstant_doesNotProduceANegativeHour() {
        // Floor-mod, not remainder: one minute before the epoch is 23:59, never "-1:-1".
        assertEquals("23:59", formatHhMm(epoch - minute, offsetMs = 0L))
    }

    @Test
    fun realisticEpochMs_formatsCorrectly() {
        // 2026-07-09T20:19:46Z (the ticket's own creation instant) at UTC+2 → 22:19 local.
        assertEquals("22:19", formatHhMm(1_783_628_386_000L, offsetMs = 2 * hour))
    }

    @Test
    fun formatLocalHhMm_usesTheInjectedClockPerInstant() {
        // The offset is asked for AT the event's instant, not "now" — this is what keeps a replayed
        // pre-DST event formatted with the offset that was in force when it happened.
        val asked = mutableListOf<Long>()
        val clock = object : TranscriptClock {
            override fun nowMs(): Long = 0L
            override fun utcOffsetMs(atEpochMs: Long): Long {
                asked += atEpochMs
                return if (atEpochMs < 5_000L) hour else 2 * hour // a DST step at 5_000
            }
        }
        assertEquals("01:00", formatLocalHhMm(epoch, clock), "before the step: UTC+1")
        assertEquals("02:00", formatLocalHhMm(6_000L, clock), "after the step: UTC+2")
        assertEquals(listOf(0L, 6_000L), asked, "the offset is resolved for the EVENT's instant")
    }
}
