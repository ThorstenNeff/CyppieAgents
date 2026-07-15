package com.tneff.cyppieagents.mux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CYP-620 — teeth for the shared `:core` per-stream flow-control window. These pin the G1–G4 correctness (deadlock,
 * fairness/backpressure, init credit) that the yamux algorithm gives us — validated once for BOTH hub + client.
 */
class YamuxWindowTest {

    // ---- SEND side: credit, backpressure, fail-closed under/overflow ----

    @Test
    fun send_consume_thenBackpressureAtZero_thenGrantResumes() {
        val w = YamuxSendWindow(initial = 10)
        w.consume(6)
        assertEquals(4, w.available(), "credit decremented by what we sent")
        w.consume(4)
        assertEquals(0, w.available(), "at 0 credit the stream's pump must block (backpressure) — only THIS stream")
        w.grant(8) // peer drained + returned credit
        assertEquals(8, w.available(), "a WINDOW_UPDATE grant resumes the stream")
    }

    @Test
    fun send_consumeBeyondCredit_failsClosed() {
        val w = YamuxSendWindow(initial = 3)
        assertFailsWith<IllegalArgumentException> { w.consume(4) } // a caller bug — never silently over-send
    }

    @Test
    fun send_grant_negativeOrOverflow_failsClosed() {
        assertFailsWith<YamuxProtocolException> { YamuxSendWindow(0).grant(-1) }
        assertFailsWith<YamuxProtocolException> { YamuxSendWindow(YamuxFrame.U32_MAX).grant(1) } // > u32
    }

    // ---- RECEIVE side: the window-update algorithm + deadlock-freedom + overshoot guard ----

    @Test
    fun recv_updateOnlyWhenAvailableExceedsHalf_noTinyStorm() {
        val max = 1000L
        val w = YamuxRecvWindow(maxWindow = max)
        w.onReceived(600) // buffered=600, advertised=400
        // Drain a SMALL amount → available (1000-590=410) is NOT > max/2 (500) → no update (avoids tiny-update storm).
        assertEquals(0, w.onDrained(10), "a small drain below the half-window threshold announces nothing")
        // Drain more so available climbs over half → an update IS due, restoring the peer's window.
        val delta = w.onDrained(300) // buffered=290, available=710 > 500 → delta = 710 - 400 = 310
        assertEquals(310, delta, "once available > max/2, return the delta that restores the advertised window")
        assertEquals(710, w.advertised(), "advertised window restored to the available level")
    }

    @Test
    fun recv_creditReturnedOnDRAIN_notEnqueue_deadlockFree() {
        // Deadlock-freedom: receiving data does NOT return credit (that would let a sender race itself); ONLY draining
        // returns it. So a full buffer with no drain returns 0 — the sender is (correctly) backpressured until we drain.
        val w = YamuxRecvWindow(maxWindow = 1000)
        w.onReceived(1000) // window fully consumed, buffered=1000, advertised=0
        assertEquals(0, w.advertised(), "receiving alone returns NO credit (would-be self-race)")
        val delta = w.onDrained(1000) // now the app drained everything → full window returns
        assertEquals(1000, delta, "credit returns on DRAIN → the sender unblocks only after we made room")
    }

    @Test
    fun recv_peerOvershootsWindow_failsClosed() {
        val w = YamuxRecvWindow(maxWindow = 100)
        w.onReceived(80) // advertised now 20
        assertFailsWith<YamuxProtocolException> { w.onReceived(21) } // 21 > advertised 20 → protocol error, not a bleed
    }

    @Test
    fun recv_synForcesAnUpdate_evenBelowThreshold() {
        val w = YamuxRecvWindow(maxWindow = 1000)
        w.onReceived(600) // buffered=600, advertised=400
        // available after this drain = 1000 - 590 = 410, which is NOT > max/2 (500) → normally NO update...
        assertEquals(0, YamuxRecvWindow(maxWindow = 1000).also { it.onReceived(600) }.onDrained(10), "control: below-threshold drain announces nothing")
        // ...but syn (stream open) FORCES the announcement of the available window.
        val delta = w.onDrained(10, syn = true) // 410 ≤ 500, but syn → delta = 410 - 400 = 10
        assertTrue(delta > 0, "syn (stream open) forces the window announcement even below the half-window threshold")
    }

    @Test
    fun recv_endToEnd_withSendWindow_conserveCredit() {
        // A round-trip: sender credit == what the receiver advertises + returns, conserved (no credit created/lost).
        val max = 500L
        val send = YamuxSendWindow(initial = max)
        val recv = YamuxRecvWindow(maxWindow = max)
        // send 300 → consume 300; receiver buffers 300 (advertised 200)
        send.consume(300); recv.onReceived(300)
        assertEquals(200, send.available())
        // app drains 300 → returns 300 → grant back to the sender → full credit restored
        val delta = recv.onDrained(300)
        send.grant(delta)
        assertEquals(500, send.available(), "credit is conserved end-to-end (drain returns exactly what was consumed)")
    }
}
