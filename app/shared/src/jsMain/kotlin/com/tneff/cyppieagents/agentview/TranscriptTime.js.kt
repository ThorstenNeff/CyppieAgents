package com.tneff.cyppieagents.agentview

import kotlin.js.Date

private val JsTranscriptClock = object : TranscriptClock {
    override fun nowMs(): Long = Date.now().toLong()

    /** `getTimezoneOffset()` counts minutes the zone lies **behind** UTC (Berlin in summer → `-120`). */
    override fun utcOffsetMs(atEpochMs: Long): Long =
        -(Date(atEpochMs.toDouble()).getTimezoneOffset().toLong() * 60_000L)
}

actual fun platformTranscriptClock(): TranscriptClock = JsTranscriptClock
