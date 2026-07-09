package com.tneff.cyppieagents.agentview

import java.util.TimeZone

private val AndroidTranscriptClock = object : TranscriptClock {
    override fun nowMs(): Long = System.currentTimeMillis()

    /** `java.util.TimeZone` (not `java.time`) — no core-library desugaring needed at the project's `minSdk`. */
    override fun utcOffsetMs(atEpochMs: Long): Long = TimeZone.getDefault().getOffset(atEpochMs).toLong()
}

actual fun platformTranscriptClock(): TranscriptClock = AndroidTranscriptClock
