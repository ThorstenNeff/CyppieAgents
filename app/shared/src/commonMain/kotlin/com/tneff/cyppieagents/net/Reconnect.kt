package com.tneff.cyppieagents.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.TimeSource

/**
 * Exponential backoff schedule for WebSocket reconnects (CYP-73). [delayFor] is pure and unit-tested;
 * the first reconnect waits [initialMs], each subsequent attempt multiplies by [factor], capped at
 * [maxMs] so a long outage never stretches the gap unboundedly.
 */
data class Backoff(
    val initialMs: Long = 250L,
    val maxMs: Long = 5_000L,
    val factor: Double = 2.0,
) {
    /** Delay before reconnect [attempt] (1-based). attempt ≤ 0 → no wait. */
    fun delayFor(attempt: Int): Long {
        if (attempt <= 0) return 0L
        val raw = initialMs.toDouble() * factor.pow(attempt - 1)
        return min(maxMs.toDouble(), raw).toLong()
    }
}

private val RECONNECT_CLOCK = TimeSource.Monotonic.markNow()
private fun monotonicNowMs(): Long = RECONNECT_CLOCK.elapsedNow().inWholeMilliseconds

/**
 * Re-subscribes to this cold source whenever it completes **or** fails, with exponential [backoff],
 * until the collector is cancelled (cancellation propagates — never swallowed). Each re-subscription
 * replays whatever the source emits on (re)connect (e.g. a `Connected` marker + a backfill); making
 * those replays idempotent — e.g. dedup by `message.id` — is the consumer's job (CYP-73: "no
 * duplicates / no visible loss"). Generic so the comm and agent streams share one tested implementation.
 *
 * **Stale-backoff fix (the 598-B sibling, tunnel-warmth incident):** `attempt` was previously monotonic —
 * it climbed to the [Backoff.maxMs] cap over the flow's lifetime and NEVER reset, so a stream that stayed
 * healthy for hours and then blipped once waited the stale cap (~5s) instead of reconnecting promptly.
 * The reset is **duration-based, not emission-based**: a connection that DELIVERED data AND stayed up
 * ≥ [stableConnectionMs] (measured from its first emission = the point the socket really carried traffic)
 * was healthy → reset the ladder so the next transient blip reconnects fast. Emission-based reset would be
 * wrong here — the comm/acl/event sources emit a synthetic `Connected` marker on EVERY (re)connect, so a
 * flapping endpoint (accept → marker → instant drop) would reset every time and re-introduce the floor-hammer.
 * A zero-emission dial-fail OR a connect-then-quick-drop keeps escalating → the cap (no hammer).
 *
 * [nowMs] is an injected monotonic clock (default = a process-monotonic source) so a test drives the
 * stability window deterministically without real time.
 */
fun <T> Flow<T>.reconnecting(
    backoff: Backoff = Backoff(),
    stableConnectionMs: Long = backoff.maxMs,
    nowMs: () -> Long = ::monotonicNowMs,
): Flow<T> = flow {
    var attempt = 0
    while (true) {
        var firstEmissionAt: Long? = null
        try {
            this@reconnecting.collect { value ->
                if (firstEmissionAt == null) firstEmissionAt = nowMs() // the socket carried real traffic here
                emit(value)
            }
            // Source completed cleanly (socket closed) → reconnect after a backoff.
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            // Transient failure (socket error) → reconnect after a backoff.
        }
        // A connection that delivered data AND stayed up ≥ stableConnectionMs was healthy (not a flap) → reset the
        // ladder so the next transient blip reconnects fast (initialMs), not at the stale cap. A zero-emission
        // dial-fail OR a connect-then-quick-drop does NOT reset → it escalates to the cap (no floor-hammer).
        val started = firstEmissionAt
        if (started != null && nowMs() - started >= stableConnectionMs) attempt = 0
        attempt += 1
        delay(backoff.delayFor(attempt))
    }
}
