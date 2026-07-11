package com.tneff.cyppieagents.agentview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-393 §2 — the pure "is the transcript at (within tolerance of) the live end?" predicate [transcriptAtBottom].
 * This is the testable core of the pin-retention (the release/resume glue is Compose-runtime, QA'd interactively).
 * The tolerance is the crux: streaming + the scroll landing sit a few px past exact, so an exact test flickers.
 */
class Cyp393TranscriptAutoScrollTest {

    @Test
    fun emptyTranscript_countsAsAtBottom() {
        assertTrue(transcriptAtBottom(totalItems = 0, lastVisibleIndex = null, lastVisibleItemBottom = 0, viewportEndOffset = 1000, tolerancePx = 48))
    }

    @Test
    fun lastItemNotEvenVisible_isNotAtBottom() {
        // scrolled well up: the last visible item is #5 of 10 → history below → not at the end
        assertFalse(transcriptAtBottom(totalItems = 10, lastVisibleIndex = 5, lastVisibleItemBottom = 900, viewportEndOffset = 1000, tolerancePx = 48))
    }

    @Test
    fun finalItemFullyWithinViewport_isAtBottom() {
        assertTrue(transcriptAtBottom(totalItems = 10, lastVisibleIndex = 9, lastVisibleItemBottom = 980, viewportEndOffset = 1000, tolerancePx = 48))
    }

    @Test
    fun finalItemBelowViewportPlusTolerance_isNotAtBottom() {
        // final item visible but its bottom is 1049 vs 1000 + 48 = 1048 → just past → not at bottom
        assertFalse(transcriptAtBottom(totalItems = 10, lastVisibleIndex = 9, lastVisibleItemBottom = 1049, viewportEndOffset = 1000, tolerancePx = 48))
    }

    /** THE tolerance test: a small overshoot past the exact end still counts as bottom (the flicker guard).
     *  Mutation: tolerance 0 ⇒ the same 40px overshoot reads as NOT at bottom ⇒ the pin would flicker off during
     *  streaming ⇒ this asserts the tolerance is load-bearing. */
    @Test
    fun smallOvershoot_isWithinTolerance_butZeroToleranceWouldFlicker() {
        val bottom40PastEnd = 1040 // 40px past the exact viewport end (1000)
        assertTrue(
            transcriptAtBottom(totalItems = 10, lastVisibleIndex = 9, lastVisibleItemBottom = bottom40PastEnd, viewportEndOffset = 1000, tolerancePx = 48),
            "a 40px overshoot is within the 48px tolerance → still pinned",
        )
        assertFalse(
            transcriptAtBottom(totalItems = 10, lastVisibleIndex = 9, lastVisibleItemBottom = bottom40PastEnd, viewportEndOffset = 1000, tolerancePx = 0),
            "with zero tolerance the same overshoot reads as NOT bottom — this is exactly the flicker the tolerance prevents",
        )
    }

    @Test
    fun toleranceDefaultIs48dp() {
        assertEquals(48, TRANSCRIPT_BOTTOM_TOLERANCE_DP)
    }
}
