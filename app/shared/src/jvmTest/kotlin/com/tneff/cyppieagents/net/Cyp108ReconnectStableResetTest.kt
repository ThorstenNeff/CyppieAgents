package com.tneff.cyppieagents.net

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tunnel-warmth incident #108 — the `reconnecting()` stale-backoff fix. `attempt` used to be monotonic (climbed to
 * the cap over the flow's lifetime, never reset), so a stream that stayed healthy then blipped waited the stale cap.
 * The reset is **duration-based** (a connection that delivered data AND stayed up ≥ stableConnectionMs was healthy),
 * NOT emission-based (the sources emit a synthetic marker on every reconnect → emission-reset would floor-hammer a flap).
 *
 * The injected `nowMs` clock is SEPARATE from the test's virtual time: `nowMs` drives the stability window; the
 * virtual `testScheduler.currentTime` measures the actual backoff delays (`delay(backoff.delayFor(attempt))`).
 */
class Cyp108ReconnectStableResetTest {

    // Backoff 100,200,400,800(cap) — initialMs=100 makes a reset (→100) clearly distinct from the climbed cap (→800).
    private val backoff = Backoff(initialMs = 100, maxMs = 800, factor = 2.0)

    @Test
    fun healthyConnection_resetsLadder_nextReconnectIsPrompt() = runTest {
        val sched = testScheduler
        var clock = 0L
        var sub = 0
        val source = flow {
            sub++
            emit(sub)
            // The 4th subscription stays "connected" ≥ stableConnectionMs (bump the injected clock AFTER the emit,
            // still inside collect) → the post-collect `nowMs() - firstEmissionAt` = 1000 ≥ 800 → healthy → reset.
            if (sub == 4) clock += 1000
            // then the source completes (a drop) → reconnecting waits backoff.delayFor(attempt).
        }
        val gaps = mutableListOf<Long>()
        var last = 0L
        val job = launch {
            source.reconnecting(backoff = backoff, stableConnectionMs = 800, nowMs = { clock })
                .take(6)
                .collect { gaps.add(sched.currentTime - last); last = sched.currentTime }
        }
        job.join()
        // gaps = [~0, 100, 200, 400, 100, 200]: subs 1-3 climb (100,200,400); sub4 is HEALTHY → reset → the reconnect
        // after it is initialMs (100), NOT the climbed cap (800). gaps[4] is the delay that preceded sub5's emission.
        assertEquals(100L, gaps[4], "a healthy connection (up ≥ stableConnectionMs) resets the ladder → the next reconnect is prompt (initialMs), not the stale cap")
        // Reddening mutation: drop `if (started != null && nowMs()-started >= stableConnectionMs) attempt = 0`
        // ⇒ sub4 keeps climbing (attempt 3→4 → delayFor(4)=800 cap) ⇒ gaps[4]==800 ⇒ RED.
    }

    @Test
    fun flapConnection_doesNotReset_escalatesToCap() = runTest {
        val sched = testScheduler
        // Every subscription emits (the synthetic marker) then instantly drops with duration 0 (< stableConnectionMs)
        // → NO reset → the ladder escalates to the cap. A `nowMs` that never advances = a zero-duration flap.
        val source = flow { emit(1) }
        val gaps = mutableListOf<Long>()
        var last = 0L
        val job = launch {
            source.reconnecting(backoff = backoff, stableConnectionMs = 800, nowMs = { 0L })
                .take(5)
                .collect { gaps.add(sched.currentTime - last); last = sched.currentTime }
        }
        job.join()
        // gaps = [~0, 100, 200, 400, 800]: a flap escalates to the cap, it does NOT reset on the synthetic marker.
        assertEquals(800L, gaps[4], "a flapping connection (zero-duration, only a synthetic marker emitted) does NOT reset — it escalates to the cap")
        // Reddening mutation: emission-based reset (reset whenever firstEmissionAt != null, ignoring the duration)
        // ⇒ every marker resets ⇒ all delays are initialMs ⇒ gaps[4]==100 ⇒ RED (the floor-hammer the duration-guard prevents).
    }
}
