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
 * **One clock, two resolutions (CYP-336).** The event log renders `HH:MM:SS.mmm` because correlation and the
 * load test need milliseconds; the transcript renders `HH:mm` because it is a conversation. Both now resolve
 * their offset through *this* seam, so a transcript row and the event describing it read the same time. Until
 * CYP-336 they did not: `eventlog.formatTs` rendered **UTC** and said so only in a comment, showing an operator
 * in Berlin every event two hours off with nothing on screen to reveal it. Its successor is named
 * [com.tneff.cyppieagents.eventlog.formatLocalHhMmSsMillis] — the frame of reference belongs in the name.
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
