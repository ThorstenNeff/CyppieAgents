package com.tneff.cyppieagents.transport

/**
 * CYP-528b (dogfood 2026-07-15) — the SINGLE-SOURCED re-dial jitter for the relay reconnect paths.
 *
 * Both re-dial herd paths were DETERMINISTIC and thus **re-synchronized** under a burst of simultaneous tunnel-ends
 * (the dogfood: unconsumed-tunnel churn → all N responders re-dial in lock-step):
 *  - the [NoiseRelayConnector] clean-end path (a tunnel that held ≥ the clean-end floor re-dialed at 0ms — the
 *    dominant amplifier: a synchronized 0-5ms hammer that saturated the relay), and its failure-backoff path
 *    (`AdmissionRetry.delayForAttempt` — a fixed exponential curve, identical for every responder), and
 *  - the [ConcurrentRelayResponderManager] set-fetch retry (same fixed curve).
 *
 * A small **additive, decorrelated** jitter (`base + random(0..spread)`, an independent draw per responder) breaks the
 * lock-step so the re-dials spread over a window instead of all landing at once. Small enough not to meaningfully delay
 * a legitimate single re-establish; wide enough to de-synchronize a burst of N. Injectable at each call site (a
 * deterministic function) so the reconnect teeth stay deterministic.
 */
internal object ReDialJitter {
    /** The minimum jitter floor — the instant (0-5ms) clean-end re-dial is capped to at least this. */
    const val BASE_MS: Long = 100L

    /** The random spread added on top of [BASE_MS] — the de-synchronization window. */
    const val SPREAD_MS: Long = 500L

    /** One independent jitter draw in `[BASE_MS, BASE_MS + SPREAD_MS)` ms. */
    fun next(): Long = BASE_MS + kotlin.random.Random.nextLong(SPREAD_MS)
}
