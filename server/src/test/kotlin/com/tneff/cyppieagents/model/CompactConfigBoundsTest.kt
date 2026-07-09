package com.tneff.cyppieagents.model

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * CYP-329 — the single-sourced timing bounds ([CompactConfig.timingBoundsError]) that BOTH the server
 * (`POST /api/compact/config` → 400) and the client editors import. Per-field boundary coverage so a mutation
 * to any bound or comparator reds. Values are the PO-ratified bounds: stagger 30s..10min, round-gap 30s..30min,
 * window 60s..30min.
 */
class CompactConfigBoundsTest {

    private fun cfg(stagger: Long = 120_000, gap: Long = 120_000, window: Long = 600_000) =
        CompactConfig(allowed = true, thresholdTokens = 500_000, staggerMs = stagger, roundGapMs = gap, roundWindowMs = window)

    @Test
    fun defaults_areInRange() = assertNull(cfg().timingBoundsError(), "the 2-min defaults must be valid")

    @Test
    fun boundaries_inclusive_areValid() {
        assertNull(cfg(stagger = CompactConfig.STAGGER_MIN_MS).timingBoundsError())
        assertNull(cfg(stagger = CompactConfig.STAGGER_MAX_MS).timingBoundsError())
        assertNull(cfg(gap = CompactConfig.ROUND_GAP_MIN_MS).timingBoundsError())
        assertNull(cfg(gap = CompactConfig.ROUND_GAP_MAX_MS).timingBoundsError())
        assertNull(cfg(window = CompactConfig.ROUND_WINDOW_MIN_MS).timingBoundsError())
        assertNull(cfg(window = CompactConfig.ROUND_WINDOW_MAX_MS).timingBoundsError())
    }

    @Test
    fun stagger_belowMin_orAboveMax_isRejected() {
        assertNotNull(cfg(stagger = CompactConfig.STAGGER_MIN_MS - 1).timingBoundsError())
        assertNotNull(cfg(stagger = CompactConfig.STAGGER_MAX_MS + 1).timingBoundsError())
    }

    @Test
    fun roundGap_belowMin_orAboveMax_isRejected() {
        assertNotNull(cfg(gap = CompactConfig.ROUND_GAP_MIN_MS - 1).timingBoundsError())
        assertNotNull(cfg(gap = CompactConfig.ROUND_GAP_MAX_MS + 1).timingBoundsError())
    }

    @Test
    fun roundWindow_belowMin_orAboveMax_isRejected() {
        assertNotNull(cfg(window = CompactConfig.ROUND_WINDOW_MIN_MS - 1).timingBoundsError())
        assertNotNull(cfg(window = CompactConfig.ROUND_WINDOW_MAX_MS + 1).timingBoundsError())
    }

    @Test
    fun zeroAndNegative_areRejected() {
        assertNotNull(cfg(stagger = 0).timingBoundsError())
        assertNotNull(cfg(gap = -1).timingBoundsError())
        assertNotNull(cfg(window = 0).timingBoundsError())
    }
}
