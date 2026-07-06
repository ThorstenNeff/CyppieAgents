package com.tneff.cyppieagents.eventlog

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.model.contrastRatio
import com.tneff.cyppieagents.ui.MaritimeDark
import com.tneff.cyppieagents.ui.MaritimeLight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-274 — the severity rail is scheme-adaptive: on the maritime LIGHT surface the ERROR/WARN/INFO tones clear
 * the ≥3:1 non-text-contrast target (WCAG 1.4.11), while DEBUG is DELIBERATELY dim in BOTH schemes (the quietest
 * severity earns no ≥3:1 rail; its meaning rides the glyph `·` + text — never colour alone). The CYP-12 dark
 * palette is preserved unchanged.
 *
 * Mutation proof (non-vacuous): drop the light palette (make the `else` branch return the dark colours) →
 * [lightScheme_errorWarnInfo_meetNonTextContrast] REDs (the dark pastels wash out < 3:1 on white) and
 * [lightPalette_distinctFromDark] REDs.
 */
class SeverityRailSchemeTest {

    private val lightSurface = MaritimeLight.surface.toArgb()
    private val darkSurface = MaritimeDark.surface.toArgb()
    private val nonTextMin = 3.0

    @Test
    fun lightScheme_errorWarnInfo_meetNonTextContrast() {
        for (s in listOf(Severity.ERROR, Severity.WARN, Severity.INFO)) {
            val c = s.railColor(dark = false).toArgb()
            assertTrue(contrastRatio(c, lightSurface) >= nonTextMin, "$s light rail must be ≥3:1 on the light surface")
        }
    }

    @Test
    fun debug_isDeliberatelyDim_inBothSchemes() {
        // A positive "dim by design" invariant: DEBUG must stay BELOW the non-text threshold on its own surface in
        // each scheme, so the quietest severity never competes with a real ERROR/WARN rail (§2 / WCAG 1.4.1).
        assertTrue(contrastRatio(Severity.DEBUG.railColor(false).toArgb(), lightSurface) < nonTextMin, "light DEBUG is dim")
        assertTrue(contrastRatio(Severity.DEBUG.railColor(true).toArgb(), darkSurface) < nonTextMin, "dark DEBUG is dim")
    }

    @Test
    fun darkScheme_paletteUnchanged_noRegression() {
        assertEquals(Color(0xFFFF6B6B), Severity.ERROR.railColor(dark = true))
        assertEquals(Color(0xFFFFC857), Severity.WARN.railColor(dark = true))
        assertEquals(Color(0xFFA0A4AD), Severity.INFO.railColor(dark = true))
        assertEquals(Color(0xFF5A5E66), Severity.DEBUG.railColor(dark = true))
    }

    @Test
    fun lightPalette_distinctFromDark() {
        // Every tone is scheme-adaptive (light ≠ dark) — this is what makes the contrast teeth non-vacuous.
        for (s in Severity.entries) {
            assertTrue(s.railColor(dark = false) != s.railColor(dark = true), "$s light tone differs from the dark tone")
        }
    }

    @Test
    fun lightTones_notConfusableWithBrandPrimary() {
        // Ticket: none of the four rail tones reads as the maritime brand primary (identity ≠ status).
        for (s in Severity.entries) {
            assertTrue(s.railColor(dark = false) != MaritimeLight.primary, "$s light rail must not equal the brand primary")
        }
    }
}
