package com.tneff.cyppieagents.eventlog

import androidx.compose.ui.graphics.Color
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.ui.maritimeColorScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CYP-300 (a0) — the shared severity source de-overloads `tertiary`. WARN must be **amber** (foreground +
 * container), NEVER the brand `tertiary`/`tertiaryContainer` (E1 turns `tertiary` GREEN at night → a warning /
 * offline would read as "ok/connected", inverted). The consolidation must NOT drift the other severities
 * (error / secondary / outline). Both schemes, WCAG-AA on the onColor / surface.
 *
 * Mutation proof: revert the WARN branch to `scheme.tertiary` / `scheme.tertiaryContainer` → the "WARN ≠
 * tertiary" + amber asserts RED; change a non-WARN role → the role-parity asserts RED.
 */
class SeverityColorsTest {

    /** WCAG relative luminance (sRGB linearised). */
    private fun relLum(c: Color): Double {
        fun lin(v: Float): Double { val d = v.toDouble(); return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }
    private fun contrast(a: Color, b: Color): Double {
        val la = relLum(a); val lb = relLum(b); val hi = maxOf(la, lb); val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun warnIsAmber_notTertiary_aaCompliant_bothSchemes() {
        for (dark in listOf(false, true)) {
            val scheme = maritimeColorScheme(dark)
            // Foreground WARN = amber (the CYP-274 rail tone), not the brand `tertiary`.
            val warnFg = severityColorFor(Severity.WARN, scheme, dark)
            assertNotEquals(scheme.tertiary, warnFg, "WARN foreground must be amber, not tertiary (dark=$dark)")
            assertTrue(warnFg.red > warnFg.blue, "WARN foreground is amber: red > blue (dark=$dark)")
            assertTrue(contrast(warnFg, scheme.surface) >= 4.5, "WARN foreground ≥AA on the surface (dark=$dark)")

            // Container WARN = amber surface + AA onColor, not `tertiaryContainer`.
            val (container, onColor) = severityContainerFor(Severity.WARN, scheme, dark)
            assertNotEquals(scheme.tertiaryContainer, container, "WARN container must be amber, not tertiaryContainer (dark=$dark)")
            assertTrue(container.red > container.blue, "WARN container is amber: red > blue (dark=$dark)")
            assertTrue(contrast(onColor, container) >= 4.5, "WARN container onColor ≥AA (dark=$dark)")
        }
    }

    @Test
    fun nonWarnSeverities_keepTheirRoles_noDrift() {
        for (dark in listOf(false, true)) {
            val scheme = maritimeColorScheme(dark)
            assertEquals(scheme.error, severityColorFor(Severity.ERROR, scheme, dark), "ERROR fg role unchanged (dark=$dark)")
            assertEquals(scheme.secondary, severityColorFor(Severity.INFO, scheme, dark), "INFO fg role unchanged (dark=$dark)")
            assertEquals(scheme.outline, severityColorFor(Severity.DEBUG, scheme, dark), "DEBUG fg role unchanged (dark=$dark)")
            assertEquals(scheme.error to scheme.onError, severityContainerFor(Severity.ERROR, scheme, dark), "ERROR container role unchanged (dark=$dark)")
            assertEquals(scheme.secondary to scheme.onSecondary, severityContainerFor(Severity.INFO, scheme, dark), "INFO container role unchanged (dark=$dark)")
            assertEquals(scheme.outline to scheme.surface, severityContainerFor(Severity.DEBUG, scheme, dark), "DEBUG container role unchanged (dark=$dark)")
        }
    }
}
