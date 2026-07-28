package com.tneff.cyppieagents.multihub

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-856 Slice-2 — the reachability shape marker is a filled `●` online / hollow `○` offline, paired with the WORD
 * so the shape is never the sole signal (WCAG 1.4.1). Pure; the NEUTRAL colouring (never green/error) is enforced by
 * the source-scan `Cyp856SwitcherStylingGuardTest`.
 */
class HubReachabilityGlyphTest {

    @Test
    fun online_isFilledDot() {
        assertEquals("●", hubReachabilityGlyph(online = true))
    }

    @Test
    fun offline_isHollowDot() {
        assertEquals("○", hubReachabilityGlyph(online = false))
    }

    @Test
    fun onlineAndOffline_areDistinctShapes() {
        assertEquals(false, hubReachabilityGlyph(true) == hubReachabilityGlyph(false))
    }
}
