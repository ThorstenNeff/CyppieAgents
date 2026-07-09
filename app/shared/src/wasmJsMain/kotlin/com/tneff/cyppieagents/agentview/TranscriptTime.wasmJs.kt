package com.tneff.cyppieagents.agentview

// The deploy-critical target (the public serve is the wasmJs SPA). Kotlin/Wasm requires js() to be a
// package-level single-expression body with an explicit type, and Long is not a legal interop type
// (Long ↔ BigInt) — so both bridges carry Double and convert on the Kotlin side.
private fun rawNowMs(): Double = js("Date.now()")

/** `getTimezoneOffset()` counts minutes the zone lies **behind** UTC (Berlin in summer → `-120`). */
private fun rawOffsetMinutes(atEpochMs: Double): Double = js("new Date(atEpochMs).getTimezoneOffset()")

private val WasmTranscriptClock = object : TranscriptClock {
    override fun nowMs(): Long = rawNowMs().toLong()
    override fun utcOffsetMs(atEpochMs: Long): Long =
        (-rawOffsetMinutes(atEpochMs.toDouble()) * 60_000.0).toLong()
}

actual fun platformTranscriptClock(): TranscriptClock = WasmTranscriptClock
