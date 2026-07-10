package com.tneff.cyppieagents.eventlog

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.model.contrastRatio
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

    /**
     * CYP-358: the WCAG contrast ratio comes from `:core` — the single definition the severity-rail, name-accent
     * and metadata-contrast tests all measure with. This file used to carry a private twin of the same formula.
     * It agreed with `:core` on the day it was written, and would have kept agreeing with *itself* forever:
     * measured, before the fix, an inverted `:core.contrastRatio` turned `SeverityRailSchemeTest`,
     * `NameAccentReadabilityTest` and `Cyp337MetadataTextContrastTest` red — and left this file **green**.
     *
     * A test that draws its expected value from a second implementation of the formula does not check the
     * formula. It checks that the copy still equals the copy.
     */
    private fun contrast(a: Color, b: Color): Double = contrastRatio(a.toArgb(), b.toArgb())

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
