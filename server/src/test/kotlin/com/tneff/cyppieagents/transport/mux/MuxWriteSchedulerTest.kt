package com.tneff.cyppieagents.transport.mux

import com.tneff.cyppieagents.mux.YamuxFrame
import com.tneff.cyppieagents.mux.YamuxGoAway
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-620 Increment 3 (scheduler) teeth — reserved control priority (no-starvation, §4.8.4) + round-robin fairness
 * across data streams (§4.4) + park/close semantics + the sentinel-collision regression.
 */
class MuxWriteSchedulerTest {

    private fun d(stream: Long, tag: Int) = YamuxFrame.data(stream, byteArrayOf(tag.toByte()))

    @Test
    fun controlJumpsAheadOfBulkData_reservedPriority_noStarvation() = runTest {
        val s = MuxWriteScheduler()
        repeat(100) { s.enqueueData(1, d(1, it)) } // a bulk stream floods the tunnel...
        val stop = YamuxFrame.windowUpdate(0, delta = 1) // ...then a lifecycle/control frame arrives
        s.enqueueControl(stop)
        assertEquals(stop, s.next(), "control is served BEFORE the 100 queued data frames — never starved")
    }

    @Test
    fun dataStreamsServedRoundRobin_noSingleStreamMonopoly() = runTest {
        val s = MuxWriteScheduler()
        s.enqueueData(1, d(1, 1)); s.enqueueData(1, d(1, 2))
        s.enqueueData(2, d(2, 1)); s.enqueueData(2, d(2, 2))
        s.enqueueData(3, d(3, 1)); s.enqueueData(3, d(3, 2))
        val order = (1..6).map { s.next() }
        assertEquals(
            listOf(d(1, 1), d(2, 1), d(3, 1), d(1, 2), d(2, 2), d(3, 2)), order,
            "round-robin: one frame per stream per turn (not 1,1,2,2,3,3) — no monopoly, per-stream FIFO preserved",
        )
    }

    @Test
    fun controlFifoPreserved() = runTest {
        val s = MuxWriteScheduler()
        val c1 = YamuxFrame.windowUpdate(0, 1)
        val c2 = YamuxFrame.ping(opaque = 42, flags = com.tneff.cyppieagents.mux.YamuxFlags.SYN)
        s.enqueueControl(c1); s.enqueueControl(c2)
        assertEquals(listOf(c1, c2), listOf(s.next(), s.next()))
    }

    @Test
    fun next_suspendsWhenEmpty_resumesOnEnqueue() = runTest {
        val s = MuxWriteScheduler()
        var got: YamuxFrame? = null
        var done = false
        val job = launch { got = s.next(); done = true }
        runCurrent()
        assertFalse(done, "next() parks (no busy-spin) while nothing is ready")
        val f = d(5, 9)
        s.enqueueData(5, f)
        job.join()
        assertTrue(done); assertEquals(f, got)
    }

    @Test
    fun close_drainsRemainingThenReturnsNull() = runTest {
        val s = MuxWriteScheduler()
        s.enqueueControl(YamuxFrame.windowUpdate(0, 1))
        s.enqueueData(1, d(1, 7))
        s.close()
        // close drains what is already queued (control first, then data), THEN ends the writer loop with null.
        assertEquals(YamuxFrame.windowUpdate(0, 1), s.next())
        assertEquals(d(1, 7), s.next())
        assertNull(s.next(), "closed and drained → null ends the tunnel-writer loop cleanly")
    }

    @Test
    fun close_wakesAParkedNext() = runTest {
        val s = MuxWriteScheduler()
        var result: YamuxFrame? = d(0, 0) // sentinel non-null to prove it becomes null
        var done = false
        val job = launch { result = s.next(); done = true }
        runCurrent()
        assertFalse(done, "parked on empty")
        s.close()
        job.join()
        assertTrue(done); assertNull(result, "close() wakes the parked next() → null")
    }

    @Test
    fun enqueueAfterClose_isDropped_failClosed() = runTest {
        val s = MuxWriteScheduler()
        s.close()
        s.enqueueControl(YamuxFrame.windowUpdate(0, 1)) // no-op after close
        s.enqueueData(1, d(1, 1))
        assertNull(s.next(), "nothing accepted after close")
    }

    @Test
    fun goAwayInternalError_isDelivered_notMistakenForDrainedSentinel() = runTest {
        // Regression: the drained-vs-frame result must be a TYPED result, not a YamuxFrame sentinel — else a real
        // GO_AWAY(INTERNAL_ERROR) control frame (content-equal to a naive sentinel) would be read as "drained → null".
        val s = MuxWriteScheduler()
        val goAway = YamuxFrame.goAway(YamuxGoAway.INTERNAL_ERROR)
        s.enqueueControl(goAway)
        assertEquals(goAway, s.next(), "a real GO_AWAY(INTERNAL_ERROR) frame is delivered, not swallowed as the sentinel")
    }
}
