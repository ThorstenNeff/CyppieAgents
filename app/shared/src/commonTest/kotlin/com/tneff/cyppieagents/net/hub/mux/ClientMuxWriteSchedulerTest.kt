package com.tneff.cyppieagents.net.hub.mux

import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-620 — the load-bearing **control-frame non-starvation** tooth (client half; the server's `MuxWriteScheduler` is
 * the peer). The FIFO write path let a bulk REST stream's queued frames delay a lifecycle STOP; the priority scheduler
 * must drain a CONTROL frame ahead of already-queued bulk. This is where CYP-620 goes live — a lifecycle stop/restart
 * must never wait behind avatar/bulk traffic on the shared tunnel.
 */
class ClientMuxWriteSchedulerTest {

    /** A carrier whose `send` RECORDS the frame's marker byte in order, then waits for a test-issued permit — so the
     *  test paces the single writer frame-by-frame and observes the exact write order. */
    private class GatedCarrier : NoiseTunnel {
        override val handshakeHash = ByteArray(32)
        val order = mutableListOf<Int>()
        private val permits = Channel<Unit>(Channel.UNLIMITED)
        override suspend fun send(plaintext: ByteArray) { order.add(plaintext[0].toInt()); permits.receive() }
        fun permit() { permits.trySend(Unit) }
        override suspend fun receive(): ByteArray? = awaitCancellation() // the scheduler is write-only
        override suspend fun close() {}
    }

    @Test
    fun controlFrame_preemptsQueuedBulk_noStarvation_CYP620() = runTest {
        val carrier = GatedCarrier()
        val sched = MuxWriteScheduler(carrier, scope = this)

        // Bulk (REST) floods first; the writer picks bulk-1 and blocks on its permit, bulk-2/3 still queued.
        sched.enqueue(byteArrayOf(11), StreamClass.REST.wire)
        sched.enqueue(byteArrayOf(12), StreamClass.REST.wire)
        sched.enqueue(byteArrayOf(13), StreamClass.REST.wire)
        advanceUntilIdle()
        assertEquals(listOf(11), carrier.order, "writer sent bulk-1 and is blocked on its permit")

        // A CONTROL (lifecycle STOP) frame arrives while bulk-2/3 sit queued behind bulk-1.
        sched.enqueue(byteArrayOf(0), StreamClass.CONTROL.wire)
        advanceUntilIdle()
        assertEquals(listOf(11), carrier.order, "still blocked on bulk-1's permit — nothing else sent yet")

        // Release bulk-1 → the writer picks the HIGHEST-priority pending = CONTROL, BEFORE bulk-2/3.
        carrier.permit(); advanceUntilIdle()
        assertEquals(listOf(11, 0), carrier.order, "CONTROL preempts the queued bulk (mutant: no priority ⇒ bulk-2 here ⇒ RED)")

        // The bulk backlog drains only after the control frame.
        carrier.permit(); advanceUntilIdle()
        carrier.permit(); advanceUntilIdle()
        assertEquals(listOf(11, 0, 12, 13), carrier.order, "remaining bulk drains after the control frame")
        sched.close()
    }

    @Test
    fun drainAndClose_stalledCarrier_fallsBackToAbrupt_doesNotHang_CYP620belt() = runTest {
        // Backend2 belt: drainAndClose flushes queued frames, but if carrier.send STALLS at clean-close it must not hang
        // teardown — after DRAIN_FLUSH_TIMEOUT it falls back to an abrupt cancel. Here the carrier never permits, so the
        // writer blocks forever. Mutant: an unbounded writer.join() ⇒ drainAndClose hangs ⇒ the outer withTimeout ⇒ RED.
        val carrier = GatedCarrier() // never permits → carrier.send blocks forever
        val sched = MuxWriteScheduler(carrier, scope = this)
        sched.enqueue(byteArrayOf(1), StreamClass.CONTROL.wire)
        advanceUntilIdle() // the writer sends frame-1, then blocks on the never-issued permit
        withTimeout(30_000) { sched.drainAndClose() } // returns via the timeout fallback — reaching here = no hang
    }
}
