package com.tneff.cyppieagents.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlin.math.min
import kotlin.math.pow

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

/**
 * Re-subscribes to this cold source whenever it completes **or** fails, with exponential [backoff],
 * until the collector is cancelled (cancellation propagates — never swallowed). Each re-subscription
 * replays whatever the source emits on (re)connect (e.g. a `Connected` marker + a backfill); making
 * those replays idempotent — e.g. dedup by `message.id` — is the consumer's job (CYP-73: "no
 * duplicates / no visible loss"). Generic so the comm and agent streams share one tested implementation.
 */
fun <T> Flow<T>.reconnecting(backoff: Backoff = Backoff()): Flow<T> = flow {
    var attempt = 0
    while (true) {
        try {
            emitAll(this@reconnecting)
            // Source completed cleanly (socket closed) → reconnect after a backoff.
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            // Transient failure (socket error) → reconnect after a backoff.
        }
        attempt += 1
        delay(backoff.delayFor(attempt))
    }
}
