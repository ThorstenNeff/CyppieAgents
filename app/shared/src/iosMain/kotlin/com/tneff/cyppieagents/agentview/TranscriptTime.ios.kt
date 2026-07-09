package com.tneff.cyppieagents.agentview

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970

private val IosTranscriptClock = object : TranscriptClock {
    override fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1_000.0).toLong()

    /** `secondsFromGMTForDate` resolves the offset in force at that instant (DST folded in). */
    override fun utcOffsetMs(atEpochMs: Long): Long {
        val instant = NSDate.dateWithTimeIntervalSince1970(atEpochMs / 1_000.0)
        return NSTimeZone.localTimeZone.secondsFromGMTForDate(instant) * 1_000L
    }
}

actual fun platformTranscriptClock(): TranscriptClock = IosTranscriptClock
