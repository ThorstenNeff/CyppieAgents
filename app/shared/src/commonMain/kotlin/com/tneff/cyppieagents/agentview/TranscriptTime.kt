package com.tneff.cyppieagents.agentview

/**
 * CYP-335 — the transcript's time seam.
 *
 * The *formatting* (epoch-ms + offset → `HH:mm`) lives here in `commonMain` and is unit-tested directly.
 * Exactly two things are a platform's to answer, and they are bundled into ONE `expect` surface rather than
 * two loose functions: the wall clock, and the local UTC offset.
 *
 * **Why not kotlinx-datetime:** on `js`/`wasmJs` it needs the `@js-joda/timezone` npm dependency plus init code
 * for real zones; without it a zone resolves to `SYSTEM` with a *fixed* offset, which mis-dates historical
 * stamps across a DST boundary. `new Date(atEpochMs).getTimezoneOffset()` is DST-correct and costs nothing in
 * the bundle. That is why [TranscriptClock.utcOffsetMs] is parameterised **per instant** — an offset asked for
 * "now" and applied to an old event is precisely the bug this avoids.
 *
 * Deliberately NOT `eventlog.formatTs`, which renders **UTC** `HH:MM:SS.mmm` for the operator's event log
 * (ordering there is by `seq`, and a load-test timeline wants UTC + millis). The transcript is a human
 * conversation view, so it shows *local* time to the minute. Two audiences, two formats — no sharing.
 */
interface TranscriptClock {
    /** Wall clock, epoch ms. */
    fun nowMs(): Long

    /**
     * The local zone's total offset from UTC **at [atEpochMs]**, in ms; east of UTC is positive
     * (Berlin in summer → `+7_200_000`). Per-instant, so an event replayed after a DST change still
     * formats with the offset that was in force when it happened.
     */
    fun utcOffsetMs(atEpochMs: Long): Long
}

/** The platform's [TranscriptClock]. Five actuals: `wasmJs` / `js` / `jvm` / `android` / `ios`. */
expect fun platformTranscriptClock(): TranscriptClock

private const val DAY_MS = 86_400_000L

/**
 * Pure: the 24-hour, zero-padded `HH:mm` of [tsMs] as seen from a zone [offsetMs] east of UTC.
 *
 * Floor-mod on the day, so a negative offset that pushes the instant back across midnight (or a pre-1970
 * [tsMs]) wraps to the previous day's clock rather than producing a negative hour.
 */
fun formatHhMm(tsMs: Long, offsetMs: Long): String {
    val dayMs = (((tsMs + offsetMs) % DAY_MS) + DAY_MS) % DAY_MS
    val hours = dayMs / 3_600_000L
    val minutes = (dayMs / 60_000L) % 60L
    return "${pad2(hours)}:${pad2(minutes)}"
}

/** [formatHhMm] in the platform's local zone — what every transcript row renders. */
fun formatLocalHhMm(tsMs: Long, clock: TranscriptClock = platformTranscriptClock()): String =
    formatHhMm(tsMs, clock.utcOffsetMs(tsMs))

private fun pad2(value: Long): String = value.toString().padStart(2, '0')
