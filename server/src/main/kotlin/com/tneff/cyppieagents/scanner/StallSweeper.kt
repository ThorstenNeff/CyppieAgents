package com.tneff.cyppieagents.scanner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * The periodic driver behind [StallDetector.sweep] (07 §5.2: "Stille-Erkennung ist timer-basiert").
 * Every [tickMs] it asks the detector for any agents that have crossed the silence threshold and
 * forwards the resulting `stall.suspected` signals to the bus via [SignalSink]. The detector keeps all
 * the state and the idempotency; this is just the clock that wakes it.
 *
 * Like the rest of the Sense stage it has **no agent-write authority** — its only output is Signals.
 * The [clock] is injected (`System::currentTimeMillis` in prod) so a test can drive time deterministically.
 */
class StallSweeper(
    private val detector: StallDetector,
    private val signals: SignalSink,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val tickMs: Long = DEFAULT_TICK_MS,
) {
    private val log = LoggerFactory.getLogger("scanner.stall-sweeper")

    fun start(): Job = scope.launch {
        log.info("stall sweeper started (tick={}ms)", tickMs)
        while (isActive) {
            delay(tickMs)
            for (signal in detector.sweep(clock())) signals.emit(signal)
        }
    }

    companion object {
        /** Sweep cadence — finer than the threshold so detection latency ≈ T (not T + tick). */
        const val DEFAULT_TICK_MS = 10_000L
    }
}
