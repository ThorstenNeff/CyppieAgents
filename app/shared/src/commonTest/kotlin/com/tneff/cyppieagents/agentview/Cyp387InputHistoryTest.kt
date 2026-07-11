package com.tneff.cyppieagents.agentview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-387 layer A — the sent-message ring buffer [InputHistory] (the STORE; navigation/keys are the composer).
 * Built against the interaction spec (§1 model, §3 capacity/`0=aus`). Each test names the mutation it reds.
 */
class Cyp387InputHistoryTest {

    /** Records in send order, newest last. Mutation: append to the front ⇒ order flips ⇒ red. */
    @Test
    fun recordsSentMessages_newestLast() {
        val h = InputHistory(capacity = 10)
        h.record("a"); h.record("b"); h.record("c")
        assertEquals(listOf("a", "b", "c"), h.entries)
    }

    /** At capacity only the newest N are VISIBLE (terminal HISTSIZE). Spec test 5 — observable window.
     *  (This is enforced jointly by eviction and the `takeLast(capacity)` read; the read alone is pinned by
     *  [zeroCapacity_disablesHistory] / [liveCapacityShrink…], and the actual EVICTION by
     *  [evictionIsPermanent_growingNCannotResurrectDropped].) */
    @Test
    fun evictsOldestAtCapacity() {
        val h = InputHistory(capacity = 3)
        h.record("a"); h.record("b"); h.record("c"); h.record("d")
        assertEquals(listOf("b", "c", "d"), h.entries, "N=3 shows the three newest; 'a' gone")
        assertEquals(3, h.size)
    }

    /** Eviction is PERMANENT (a real memory bound), not merely hidden by the `takeLast(capacity)` read: over-fill
     *  at a small N, then RAISE N — the dropped oldest entries must NOT reappear.
     *  Mutation: remove the eviction `while` in record ⇒ the buffer grew unbounded, raising N resurrects a,b ⇒ red.
     *  (Without this test the eviction is unguarded — `takeLast` would mask its absence at constant N.) */
    @Test
    fun evictionIsPermanent_growingNCannotResurrectDropped() {
        var n = 3
        val h = InputHistory(capacity = { n })
        h.record("a"); h.record("b"); h.record("c"); h.record("d"); h.record("e") // trimmed to [c,d,e] at N=3
        n = 10
        assertEquals(listOf("c", "d", "e"), h.entries, "a,b were evicted at record time — a larger N cannot bring them back")
    }

    /** Spec §1: NO dedup in v1 — consecutive duplicates are BOTH kept (predictable; shell without HISTCONTROL).
     *  Mutation: add an `ignoredups` guard ⇒ [a] instead of [a, a] ⇒ red. */
    @Test
    fun noDedup_consecutiveDuplicatesBothKept() {
        val h = InputHistory(capacity = 10)
        h.record("a"); h.record("a"); h.record("b")
        assertEquals(listOf("a", "a", "b"), h.entries, "v1 keeps every send, including an immediate repeat")
    }

    /** Blank text is never recorded (defence in depth beside onSend's own trim/empty check).
     *  Mutation: drop the blank guard ⇒ a whitespace entry appears ⇒ red. */
    @Test
    fun blankOrWhitespace_isNotRecorded() {
        val h = InputHistory(capacity = 10)
        h.record(""); h.record("   "); h.record("\n\t")
        assertTrue(h.entries.isEmpty())
    }

    /** Recorded text is trimmed (recall replays the sent form). Mutation: record raw `text` ⇒ padding survives ⇒ red. */
    @Test
    fun recordedTextIsTrimmed() {
        val h = InputHistory(capacity = 10)
        h.record("  hello agent  ")
        assertEquals(listOf("hello agent"), h.entries)
    }

    /** Spec §3.1 `0 = aus`: history off → nothing recorded and nothing shown (so the composer's ↑/↓ no-op).
     *  Defense in depth (record's `cap <= 0` guard AND the `takeLast(0)` read), so no SINGLE mutation reds the
     *  constructed-at-0 case; the read side of `0 = off` is pinned by [liveCapacityShrink…]'s N=0 step (a populated
     *  buffer viewed at N=0 must be empty). This test anchors the constructed-at-0 behaviour. */
    @Test
    fun zeroCapacity_disablesHistory() {
        val h = InputHistory(capacity = 0)
        h.record("a"); h.record("b")
        assertTrue(h.entries.isEmpty(), "N=0 turns recall off — nothing recorded")
        assertEquals(0, h.size)
    }

    /** Capacity is a LIVE supplier: lowering N (or 0) is reflected on the next read without rebuilding the store
     *  (so a global-N change never wipes an open agent's content). Mutation: read the raw buffer instead of
     *  takeLast(capacity) ⇒ the shrunk view still shows the old entries ⇒ red. */
    @Test
    fun liveCapacityShrink_isReflectedOnRead_withoutLosingContentOnRegrow() {
        var n = 5
        val h = InputHistory(capacity = { n })
        h.record("a"); h.record("b"); h.record("c")
        assertEquals(listOf("a", "b", "c"), h.entries)
        n = 2
        assertEquals(listOf("b", "c"), h.entries, "shrink to 2 shows the two newest immediately")
        n = 0
        assertTrue(h.entries.isEmpty(), "0 shows nothing (recall off)")
        n = 5
        assertEquals(listOf("a", "b", "c"), h.entries, "re-growing N restores the still-buffered content")
    }

    /** The configurable default is 20; the stepper ceiling is 200. Mutation: change either constant ⇒ red. */
    @Test
    fun defaultAndMaxCapacityConstants() {
        assertEquals(20, InputHistory.DEFAULT_CAPACITY)
        assertEquals(200, InputHistory.MAX_CAPACITY)
        val h = InputHistory(capacity = InputHistory.DEFAULT_CAPACITY)
        repeat(25) { h.record("m$it") }
        assertEquals(20, h.size, "default keeps the 20 newest")
        assertEquals("m5", h.entries.first(), "m0..m4 evicted")
        assertEquals("m24", h.entries.last())
    }
}
