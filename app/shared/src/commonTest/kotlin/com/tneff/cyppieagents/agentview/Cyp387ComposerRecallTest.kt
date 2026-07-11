package com.tneff.cyppieagents.agentview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-387 §2 — the arrow recall cursor [ComposerRecall]. Pure state machine; the acceptance numbers refer to the
 * interaction spec §5. **Test 3 (history immutability) is the core acceptance.** Each test names the mutation it reds.
 */
class Cyp387ComposerRecallTest {

    private val history = listOf("a", "b", "c") // newest last → "c" is the most recent send

    /** Spec test 6: ↑ on empty history is a no-op AND unconsumed (null), so the field swallows nothing.
     *  Mutation: return "" instead of null ⇒ the keypress is consumed with no history ⇒ red. */
    @Test
    fun emptyHistory_up_isNoOpAndUnconsumed() {
        val r = ComposerRecall()
        assertNull(r.older(emptyList(), "draft"))
        assertNull(r.navIndex)
    }

    /** ↓ while already at the live draft is unconsumed (null). Mutation: return stash ⇒ consumed at draft ⇒ red. */
    @Test
    fun down_atLiveDraft_isUnconsumed() {
        val r = ComposerRecall()
        assertNull(r.newer(history, "draft"))
    }

    /** Spec test 1 (basic flow): ↑ from draft → newest; ↑ → older; at oldest ↑ = consumed no-op; ↓ steps newer;
     *  ↓ at newest → the (stashed) draft. Mutation: ↓ at newest returns history[0] instead of stash ⇒ red. */
    @Test
    fun basicFlow_upEntersNewest_stepsOlder_downReturnsToDraft() {
        val r = ComposerRecall()
        assertEquals("c", r.older(history, "typed"), "↑ from draft enters at the NEWEST")
        assertEquals(2, r.navIndex)
        assertEquals("b", r.older(history, "c"))
        assertEquals("a", r.older(history, "b"))
        assertEquals(0, r.navIndex, "now at the oldest")
        assertEquals("a", r.older(history, "a"), "↑ at the oldest is a consumed no-op (draft unchanged)")
        assertEquals(0, r.navIndex)
        assertEquals("b", r.newer(history, "a"))
        assertEquals("c", r.newer(history, "b"))
        assertEquals("typed", r.newer(history, "c"), "↓ at the newest restores the stashed draft")
        assertNull(r.navIndex, "back at the live draft")
    }

    /** Spec test 2 (draft survives): type "foo", ↑↑ then ↓↓ back ⇒ "foo" returns.
     *  Mutation: don't set `stash` on entry ⇒ "foo" is lost ⇒ red. */
    @Test
    fun draftSurvivesTheRoundTrip() {
        val r = ComposerRecall()
        r.older(history, "foo") // entry stashes "foo" → "c"
        r.older(history, "c")   // → "b"
        r.newer(history, "b")   // → "c"
        assertEquals("foo", r.newer(history, "c"), "↓ at the newest brings back the stashed draft")
        assertNull(r.navIndex)
    }

    /** **Spec test 3 — history is IMMUTABLE (the core).** Edit a recalled value (transient working copy); the next
     *  ↑ shows the ORIGINAL neighbour and the store is never mutated.
     *  Mutation: write the working copy into the list / return the edited draft as the neighbour ⇒ red. */
    @Test
    fun test3_historyImmutable_recallEditIsDiscarded_storeUnchanged() {
        val store = history.toMutableList() // the cursor must not touch this
        val r = ComposerRecall()
        assertEquals("c", r.older(store, "draft")) // at the newest
        // user edits the recalled text → a transient working copy the COMPOSER holds; the cursor is not told.
        assertEquals("b", r.older(store, "c-EDITED"), "the next ↑ shows the ORIGINAL 'b', not the edit")
        assertEquals(listOf("a", "b", "c"), store, "recall never mutates the history/store")
    }

    /** Spec §2.3: an EMPTY draft round-trips too — ↑ → newest; ↓ at newest → the empty field returns. */
    @Test
    fun emptyDraft_roundTripsToEmpty() {
        val r = ComposerRecall()
        assertEquals("c", r.older(history, ""))
        assertEquals("", r.newer(history, "c"), "the stashed empty draft comes back")
    }

    /** After a send, [reset] leaves history so the next ↓ is a no-op again (the composer cleared the field). */
    @Test
    fun reset_afterSend_leavesHistory() {
        val r = ComposerRecall()
        r.older(history, "x")
        r.reset()
        assertNull(r.navIndex)
        assertNull(r.newer(history, ""), "after reset we are back at the live draft")
    }

    /** A cursor that outlives an N-shrink (global size lowered mid-recall) clamps instead of crashing.
     *  Mutation: drop the coerceAtMost clamp ⇒ index-out-of-bounds on the shrunk list ⇒ red (exception). */
    @Test
    fun staleCursor_afterShrink_clampsInsteadOfCrashing() {
        val r = ComposerRecall()
        r.older(listOf("a", "b", "c", "d", "e"), "e") // navIndex = 4
        assertEquals("a", r.older(listOf("a", "b"), "e"), "clamped to the shrunk list, steps to a valid entry")
    }
}
