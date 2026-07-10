package com.tneff.cyppieagents.agentview

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970

/**
 * CYP-335 — **the one `TranscriptClock` actual no test executes.** jvm/android are covered by `jvmTest`,
 * wasmJs by `TranscriptTimeWasmTest`, js by `TranscriptTimeJsTest`; iOS cannot run here (Kotlin/Native tests
 * need a macOS host). It compiles, nothing more. Named gap, not an assumed pass.
 *
 * The trap, spelled out because it is exactly what a copy from the js actual would get wrong: `getTimezoneOffset()`
 * (js/wasm) counts minutes **behind** UTC and must be negated, whereas `secondsFromGMTForDate` is **east-positive**
 * and must **not** be. Opposite conventions, identical-looking code.
 */
private val IosTranscriptClock = object : TranscriptClock {
    override fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1_000.0).toLong()

    /** `secondsFromGMTForDate` resolves the offset in force at that instant (DST folded in), east-positive. */
    override fun utcOffsetMs(atEpochMs: Long): Long {
        val instant = NSDate.dateWithTimeIntervalSince1970(atEpochMs / 1_000.0)
        return NSTimeZone.localTimeZone.secondsFromGMTForDate(instant) * 1_000L
    }
}

actual fun platformTranscriptClock(): TranscriptClock = IosTranscriptClock
