package com.tneff.cyppieagents.window

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-316 — [formatCompactTokens] boundary contract (UIUX spec §1): exact below 1000, integer-`k` in the
 * thousands, one-decimal-`M` at a million (truncated tenths, never over). The 999/1000 and 999999/1M edges are
 * the load-bearing transitions.
 */
class TokenFormatTest {

    @Test
    fun compactBoundaries() {
        assertEquals("0", formatCompactTokens(0))
        assertEquals("842", formatCompactTokens(842))
        assertEquals("999", formatCompactTokens(999))          // last exact
        assertEquals("1k", formatCompactTokens(1_000))          // first k (the 999/1000 edge)
        assertEquals("8k", formatCompactTokens(8_432))          // integer-k (truncated, no decimal)
        assertEquals("137k", formatCompactTokens(137_214))
        assertEquals("999k", formatCompactTokens(999_999))      // last k
        assertEquals("1.0M", formatCompactTokens(1_000_000))    // first M (the 999999/1M edge)
        assertEquals("1.2M", formatCompactTokens(1_234_567))    // tenths truncated (never over)
    }
}
