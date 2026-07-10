package com.tneff.cyppieagents.eventlog

import com.tneff.cyppieagents.agentview.TranscriptClock
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CYP-336 — the event log shows the operator's **local** clock, at millisecond resolution.
 *
 * `formatTs` rendered UTC and said so only in a KDoc. Its callers — event log, Product-Lead, compact panel,
 * cross-project — therefore showed an operator in `Europe/Berlin` every timestamp two hours off, unlabelled.
 * After CYP-335 put the transcript on local time, the same event carried two different clocks depending on which
 * window you read it in. That is what this closes.
 *
 * **Every assertion here injects a fake clock.** A test that depended on the runner's `TZ` would be vacuous under
 * `TZ=UTC` (offset 0 — a UTC regression and a correct implementation render identically) and would also lean on
 * an environment the build pins for a different reason. With a fake offset, a fallback to UTC is red **in every
 * timezone, including UTC**. The real per-platform bridge is exercised separately in the browser
 * (`TranscriptTimeWasmTest` / `TranscriptTimeJsTest`).
 */
class Cyp336LocalTimestampTest {

    private val hour = 3_600_000L
    private val minute = 60_000L

    /** A clock in a fixed zone. Per-instant, like the real seam — the offset is a function, not a constant. */
    private fun clockAt(offsetMs: Long) = object : TranscriptClock {
        override fun nowMs(): Long = 0L
        override fun utcOffsetMs(atEpochMs: Long): Long = offsetMs
    }

    /** 2026-07-09T20:19:46.123Z. */
    private val stamp = 1_783_628_386_123L

    // --- The resolution is kept (AC2/AC3): seconds and milliseconds, not the transcript's HH:mm ---

    @Test
    fun keepsMillisecondResolution() {
        val rendered = formatLocalHhMmSsMillis(stamp, clockAt(0L))
        assertEquals("20:19:46.123", rendered, "the event log needs seconds + millis for correlation")
        assertTrue(Regex("""^\d{2}:\d{2}:\d{2}\.\d{3}$""").matches(rendered))
    }

    // --- The zone is the operator's (AC1/AC5). Kolkata: +05:30 catches sign AND the half-hour component ---

    @Test
    fun rendersTheOperatorsLocalTime_notUtc() {
        // Asia/Kolkata, +05:30 — the zone the ticket names, expressed as an offset so no TZ env is involved.
        val kolkata = clockAt(5 * hour + 30 * minute)
        assertEquals("01:49:46.123", formatLocalHhMmSsMillis(stamp, kolkata))

        // The teeth: a fallback to UTC renders a *different* string. This assertion is what goes red on a
        // regression, and it does so under TZ=UTC as well, because the offset comes from the fake clock.
        assertNotEquals(
            formatLocalHhMmSsMillis(stamp, clockAt(0L)),
            formatLocalHhMmSsMillis(stamp, kolkata),
            "a non-zero offset must change the rendering — otherwise the offset is being ignored (UTC regression)",
        )
    }

    @Test
    fun negativeOffset_wrapsBackAcrossMidnight_withoutANegativeHour() {
        // 02:00:00.500 UTC in a −03:30 zone is 22:30 the previous day. Floor-mod, not remainder.
        assertEquals("22:30:00.500", formatHhMmSsMillis(2 * hour + 500L, -(3 * hour + 30 * minute)))
    }

    @Test
    fun positiveOffset_wrapsForwardAcrossMidnight() {
        assertEquals("01:30:00.000", formatHhMmSsMillis(23 * hour + 30 * minute, 2 * hour))
    }

    @Test
    fun preEpochInstant_doesNotProduceANegativeHour() {
        assertEquals("23:59:59.999", formatHhMmSsMillis(-1L, 0L))
    }

    @Test
    fun midnight_padsEveryField() {
        assertEquals("00:00:00.000", formatHhMmSsMillis(0L, 0L))
        assertEquals("00:00:07.005", formatHhMmSsMillis(7_005L, 0L))
    }

    // --- The transcript and the event log now agree (the point of the whole ticket) ---

    @Test
    fun theEventLogAndTheTranscript_agreeOnTheSameInstant() {
        // Same instant, same clock, two resolutions: the event-log string must START with the transcript's HH:mm.
        // Before CYP-336 this held only in UTC — which is exactly how an operator got a `12:03` event next to a
        // `14:03` transcript row and no way to see why.
        val berlinSummer = clockAt(2 * hour)
        val eventLog = formatLocalHhMmSsMillis(stamp, berlinSummer)
        val transcript = com.tneff.cyppieagents.agentview.formatLocalHhMm(stamp, berlinSummer)

        assertEquals("22:19", transcript)
        assertTrue(
            eventLog.startsWith(transcript),
            "one clock: the event log ($eventLog) must open with the transcript's minute ($transcript)",
        )
    }

    // --- AC4: rendering does not decide order ---

    @Test
    fun renderingDoesNotDecideOrder() {
        // Two events whose LOCAL clock strings sort OPPOSITE to their seq. At +02:00, an event at 21:00 UTC
        // renders "23:00:…" (same day) while a LATER one at 22:00 UTC renders "00:00:…" (past local midnight).
        // So the later event's string sorts first. If anything ever ordered on the rendered timestamp — or on a
        // local wall clock at all — this list would flip. It must follow `seq`.
        val clock = clockAt(2 * hour)
        val earlier = event(seq = 1, id = "a", ts = 21 * hour) // local "23:00:00.000"
        val later = event(seq = 2, id = "b", ts = 22 * hour) // local "00:00:00.000", next day

        assertTrue(
            formatLocalHhMmSsMillis(later.ts, clock) < formatLocalHhMmSsMillis(earlier.ts, clock),
            "precondition: the LATER event renders the SMALLER string (${formatLocalHhMmSsMillis(later.ts, clock)} " +
                "< ${formatLocalHhMmSsMillis(earlier.ts, clock)}) — otherwise this test proves nothing",
        )
        val merged = EventReducer.mergeAll(emptyList(), listOf(later, earlier))
        assertEquals(listOf("a", "b"), merged.map { it.id }, "order follows seq, never the rendered timestamp")
    }

    private fun event(seq: Long, id: String, ts: Long) = Event(
        id = id, ts = ts, seq = seq, agentId = "backend", projectId = "p",
        type = EventType.TURN_START, severity = Severity.INFO,
    )
}
