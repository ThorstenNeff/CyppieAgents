package com.tneff.cyppieagents.net

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-73: the reconnect primitive. [Backoff] grows then caps; [reconnecting] re-subscribes the cold
 * source after each completion/failure until the collector cancels (here via `take`).
 */
class ReconnectTest {

    @Test
    fun backoff_growsExponentiallyThenCaps() {
        val b = Backoff(initialMs = 100, maxMs = 800, factor = 2.0)
        assertEquals(0L, b.delayFor(0))
        assertEquals(100L, b.delayFor(1))
        assertEquals(200L, b.delayFor(2))
        assertEquals(400L, b.delayFor(3))
        assertEquals(800L, b.delayFor(4))
        assertEquals(800L, b.delayFor(5), "capped at maxMs")
        assertEquals(800L, b.delayFor(50), "stays capped")
    }

    @Test
    fun reconnecting_reSubscribesEachCycleUntilCancelled() = runBlocking {
        // A source that yields one item then completes; reconnecting must re-open it every cycle, so
        // taking 3 items proves 3 (re)subscriptions. `take` then cancels the loop (no infinite spin).
        val items = withTimeout(5_000) {
            flowOf("x").reconnecting(Backoff(initialMs = 1, maxMs = 1)).take(3).toList()
        }
        assertEquals(listOf("x", "x", "x"), items)
    }
}
