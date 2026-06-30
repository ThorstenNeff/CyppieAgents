package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.comm.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * E2.5a / CYP-161 — single-sourced wire rate-limit constants. The named lands-dark obligation from the
 * E2.2 plan (M2): now `/ws/hub` accepts real `WireSend` traffic, the flow must be bounded. Tunable; no
 * magic numbers scattered (CLAUDE.md single-source rule). Defaults PO/reviewer-endorsed.
 */
object WireRateLimit {
    /** Burst tolerance — tokens available immediately. */
    const val CAPACITY: Int = 20
    /** Sustained refill rate (tokens per second). */
    const val REFILL_PER_SEC: Int = 5
    /** Consecutive throttled sends on ONE connection before it is closed (the flood backstop). */
    const val FLOOD_CLOSE_AFTER: Int = 50
}

/**
 * E2.5a / CYP-161 — a token-bucket rate limiter for the `/ws/hub` remote send path, **keyed per bound
 * `agentId`** (the abuse unit). The bucket is **shared across all of an agent's connections**, so opening
 * N connections cannot multiply the budget (RL5). One instance is shared across the whole server.
 *
 * **RC3 (bounded key):** the map key MUST be the registry-resolved `agentId` (a finite set), NEVER an
 * attacker-supplied value — otherwise the limiter itself becomes a memory-DoS (unbounded map growth). The
 * caller ([com.tneff.cyppieagents.routing.hubWireRoutes]) only ever passes the `agentFor(token)`-resolved id.
 *
 * The consecutive-reject **close-counter is NOT here** — it is a per-connection local in the `/ws/hub`
 * handler (RC2), so a fresh connection of a throttled agent isn't insta-closed by a stale count (yet its
 * sends are still throttled, because *this* shared bucket is still empty).
 */
class WireRateLimiter(
    private val capacity: Int = WireRateLimit.CAPACITY,
    private val refillPerSec: Int = WireRateLimit.REFILL_PER_SEC,
    /** Threshold the per-connection close-counter compares against (the policy value; the COUNTER itself
     *  stays per-connection in the route, RC2). Defaulted to the single source; overridable for tests. */
    val floodCloseAfter: Int = WireRateLimit.FLOOD_CLOSE_AFTER,
    private val clock: Clock = Clock.SYSTEM,
) {
    private class Bucket(var tokens: Double, var lastRefillMs: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /**
     * Try to consume one token for [agentId]; refills lazily from elapsed time first. Returns `true` if a
     * token was available (the send proceeds), `false` if the bucket is empty (throttle). Thread-safe per
     * bucket. Refill-elapsed is clamped `≥ 0` so a non-monotonic clock can never mint negative tokens.
     */
    fun tryAcquire(agentId: String): Boolean {
        val now = clock.now()
        val bucket = buckets.getOrPut(agentId) { Bucket(capacity.toDouble(), now) }
        synchronized(bucket) {
            val elapsedMs = (now - bucket.lastRefillMs).coerceAtLeast(0L) // non-monotonic-clock guard
            bucket.tokens = (bucket.tokens + elapsedMs / 1000.0 * refillPerSec).coerceAtMost(capacity.toDouble())
            bucket.lastRefillMs = now
            return if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                true
            } else {
                false
            }
        }
    }
}
