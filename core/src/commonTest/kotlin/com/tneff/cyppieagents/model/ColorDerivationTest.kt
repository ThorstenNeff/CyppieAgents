package com.tneff.cyppieagents.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-211 — the compose-free `:core` colour derivation (spec §5): the shared, deterministic contrast-guard. These
 * teeth pin the honesty invariants (§7.4): text ≥ 4.5:1, border ≥ 3:1, hue-preserving self-correction, determinism.
 */
class ColorDerivationTest {

    private val neutralSurface = 0xFF1E1E1E.toInt()

    // A spread of bases: light, dark, mid-tone (needs correction), and the 8 palette fills.
    private val bases = listOf(
        0xFF3B82F6.toInt(), // blue
        0xFFFFFFFF.toInt(), // white (needs BLACK on-color)
        0xFF000000.toInt(), // black (needs WHITE on-color)
        0xFF808080.toInt(), // mid gray (classic contrast-fail mid-tone)
        0xFF1F7A6E.toInt(), 0xFF6E4BD0.toInt(), 0xFFB5419A.toInt(), 0xFFC24D6A.toInt(),
    )

    @Test
    fun onColor_isTheHigherContrastOfWhiteOrBlack() {
        for (base in bases) {
            val s = deriveScheme(base)
            assertTrue(s.onColor == WHITE_ARGB || s.onColor == BLACK_ARGB)
            // The chosen onColor must contrast the RAW base at least as well as the other choice would.
            val other = if (s.onColor == WHITE_ARGB) BLACK_ARGB else WHITE_ARGB
            assertTrue(contrastRatio(s.onColor, base) >= contrastRatio(other, base) - 1e-9)
        }
    }

    @Test
    fun text_meets_4_5_to_1_afterSelfCorrection() {
        for (base in bases) {
            val s = deriveScheme(base)
            assertTrue(
                contrastRatio(s.onColor, s.background) >= CONTRAST_TEXT_MIN - 1e-6,
                "onColor↔background must reach ≥4.5:1 for base ${base.toUInt().toString(16)} (got ${contrastRatio(s.onColor, s.background)})",
            )
        }
    }

    @Test
    fun border_meets_3_to_1_againstNeutralSurface() {
        for (base in bases) {
            val s = deriveScheme(base)
            assertTrue(
                contrastRatio(s.border, neutralSurface) >= CONTRAST_BORDER_MIN - 1e-6,
                "border must reach ≥3:1 vs the neutral surface for base ${base.toUInt().toString(16)}",
            )
        }
    }

    @Test
    fun deriveScheme_isDeterministic_noDrift() {
        for (base in bases) assertEquals(deriveScheme(base), deriveScheme(base))
    }

    @Test
    fun background_isPreserved_adjustedFalse_onColorAdaptsToMeetTarget() {
        // Honesty: with a free WHITE/BLACK onColor choice the AA 4.5:1 target is ALWAYS reachable (the min-max
        // contrast is ~4.58 at the crossover), so `deriveScheme` keeps the operator's base as-is (background==base,
        // adjusted=false) and adapts onColor instead — it never silently recolours the input. The self-correction
        // is a defensive floor for that guarantee, not a routine transform.
        for (base in bases) {
            val s = deriveScheme(base)
            assertEquals(base, s.background, "background must equal the raw base (no silent recolour)")
            assertFalse(s.adjusted, "no nudge needed → adjusted=false for base ${base.toUInt().toString(16)}")
        }
    }

    @Test
    fun parseHexColor_validAndInvalid() {
        assertEquals(0xFF3B82F6.toInt(), parseHexColor("#3B82F6"))
        assertEquals(0xFF3B82F6.toInt(), parseHexColor("  #3b82f6 "))
        assertNull(parseHexColor("3B82F6"))     // no leading #
        assertNull(parseHexColor("#3B82F"))     // too short
        assertNull(parseHexColor("#GGGGGG"))    // non-hex
        assertNull(parseHexColor("#3B82F6FF"))  // 8 digits (no alpha in the seam)
    }
}
