package com.tneff.cyppieagents.net.hub.operator.vault

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-542 / B1 (Assist BLOCK-1, part 2 — the idle path) — [DecryptedKeyHold] proactively zeroizes the held device key
 * at window-expiry via its [scope] timer, so a hold that is never [DecryptedKeyHold.get]-touched and never torn down
 * (the operator idles) still clears at the ≤120s bound (§4.4). Without the timer the "bounded exposure" claim is false
 * for idle: the lazy get()-path never fires and the crown-jewel key lingers GC-reachable past the window.
 *
 * The hold's clock is tied to [testScheduler].currentTime so its `delay()`-based timer and `advanceTimeBy` agree.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DecryptedKeyHoldTest {

    @Test
    fun idleExpiry_proactivelyZeroizes_withoutAnyGet() = runTest {
        val hold = DecryptedKeyHold(nowMs = { testScheduler.currentTime }, scope = backgroundScope)
        val key = ByteArray(32) { 5 }
        hold.put(key, expiresAtMs = testScheduler.currentTime + 100L) // window = 100 virtual ms
        // The operator idles: NO get(), NO teardown. Advance past the window.
        advanceTimeBy(101); runCurrent()
        assertTrue(key.all { it == 0.toByte() }, "idle path: the proactive timer zeroized the held key at window-expiry")
        assertNull(hold.get(), "the hold is empty after the proactive clear")
    }

    @Test
    fun freshPut_reschedulesExpiry_supersedesTheOldTimer() = runTest {
        val hold = DecryptedKeyHold(nowMs = { testScheduler.currentTime }, scope = backgroundScope)
        val first = ByteArray(16) { 1 }
        hold.put(first, expiresAtMs = testScheduler.currentTime + 100L) // old window ends at t=100
        advanceTimeBy(50); runCurrent() // t=50: a re-auth before the first window elapses
        val second = ByteArray(16) { 2 }
        hold.put(second, expiresAtMs = testScheduler.currentTime + 100L) // new window ends at t=150; clear() zeroed `first`
        assertTrue(first.all { it == 0.toByte() }, "a fresh put zeroizes the prior key immediately")
        // At t=101 the OLD timer would have fired (t=100) — but it was cancelled; the second key must still be live.
        advanceTimeBy(51); runCurrent() // t=101
        assertTrue(second.any { it != 0.toByte() }, "the superseded (cancelled) old timer must NOT clear the new key early")
        // At t=150 the new window elapses ⇒ proactively zeroized.
        advanceTimeBy(50); runCurrent() // t=151
        assertTrue(second.all { it == 0.toByte() }, "the rescheduled timer zeroizes the new key at its own window-expiry")
    }
}
