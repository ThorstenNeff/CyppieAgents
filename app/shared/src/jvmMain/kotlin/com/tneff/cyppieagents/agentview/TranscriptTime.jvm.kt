package com.tneff.cyppieagents.agentview

import java.util.TimeZone

private val JvmTranscriptClock = object : TranscriptClock {
    override fun nowMs(): Long = System.currentTimeMillis()

    /** `TimeZone.getOffset(long)` folds the DST correction in force at that instant into the total offset. */
    override fun utcOffsetMs(atEpochMs: Long): Long = TimeZone.getDefault().getOffset(atEpochMs).toLong()
}

actual fun platformTranscriptClock(): TranscriptClock = JvmTranscriptClock
