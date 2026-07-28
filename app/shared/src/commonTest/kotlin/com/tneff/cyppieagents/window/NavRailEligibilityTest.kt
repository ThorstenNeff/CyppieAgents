package com.tneff.cyppieagents.window

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-894 (Nav-Rail S1) — the load-bearing responsive-gate predicate [railEligible]: the rail is offered ONLY at
 * **landscape** (width > height) AND **short-edge ≥ 600dp**. Below/portrait ⇒ the current canvas-only shell
 * (unchanged). This is the S1 mutation target (gate → always/never reddens the threshold + portrait teeth).
 */
class NavRailEligibilityTest {

    @Test
    fun landscape_shortEdgeAtOrAbove600_isEligible() {
        assertTrue(railEligible(900f, 650f)) // landscape, short-edge 650
        assertTrue(railEligible(601f, 600f)) // landscape, short-edge exactly 600 (boundary, inclusive)
        assertTrue(railEligible(1280f, 800f))
    }

    @Test
    fun portrait_isNeverEligible_evenWhenLarge() {
        // MUT: drop the landscape guard (min-edge only) → a large portrait would wrongly show the rail → reds.
        assertFalse(railEligible(650f, 900f)) // portrait, short-edge 650 but height > width
        assertFalse(railEligible(600f, 1280f))
    }

    @Test
    fun shortEdgeBelow600_isNotEligible() {
        // MUT: drop/lower the 600 threshold → a small landscape would wrongly show the rail → reds.
        assertFalse(railEligible(1000f, 599f)) // landscape but short-edge 599 < 600
        assertFalse(railEligible(700f, 500f))
    }

    @Test
    fun square_isNotLandscape_notEligible() {
        assertFalse(railEligible(600f, 600f)) // width == height is not landscape (strict >)
    }
}
