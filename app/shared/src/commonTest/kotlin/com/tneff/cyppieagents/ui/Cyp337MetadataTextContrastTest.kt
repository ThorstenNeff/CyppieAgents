package com.tneff.cyppieagents.ui

import androidx.compose.ui.graphics.toArgb
import com.tneff.cyppieagents.model.contrastRatio
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-337 — **the measurement, made executable.** Three shipped `Text`s coloured `colorScheme.outline`
 * (`NoticeRow`, the comm panel's "· ausstehend", the event-log correlation chip) failed WCAG 1.4.3. This pins
 * the number the fix rests on, in **both** themes, so "looks better" can never stand in for it.
 *
 * The rule is the consequence of one number, not a style preference:
 *
 * | pair                             | light   | dark    | 1.4.11 (3:1) | 1.4.3 (4.5:1) |
 * |----------------------------------|---------|---------|--------------|----------------|
 * | `outline` on `surface`           | 3.55:1  | 3.63:1  | PASS         | **FAIL**       |
 * | `onSurfaceVariant` on `surface`  | 8.69:1  | 9.80:1  | PASS         | PASS (AAA)     |
 * | `onSurface` on `surface`         | 15.62:1 | 15.05:1 | PASS         | PASS (AAA)     |
 *
 * `outline` sits **above** the graphical-object threshold and **below** the text threshold. The same colour is
 * correct as a border and wrong as text — which is why this ticket recolours text and leaves every border,
 * divider and status dot untouched.
 *
 * Reuses `:core`'s [contrastRatio] (the same function the severity-rail tests measure with) rather than adding
 * a third way to compute a ratio. Lives in `commonTest`, so it runs on jvm, wasmJs and js.
 *
 * **Not the usage guard.** Detecting `outline` *used as a text colour* anywhere is CYP-345; this test only pins
 * the thresholds those three sites were moved across.
 */
class Cyp337MetadataTextContrastTest {

    private val textMin = 4.5 // WCAG 1.4.3 Contrast (Minimum), < 18 pt / 14 pt bold
    private val graphicMin = 3.0 // WCAG 1.4.11 Non-text Contrast
    private val aaa = 7.0 // WCAG 1.4.6 Contrast (Enhanced)

    private fun ratioOnSurface(pick: (androidx.compose.material3.ColorScheme) -> androidx.compose.ui.graphics.Color, dark: Boolean): Double {
        val scheme = maritimeColorScheme(dark)
        return contrastRatio(pick(scheme).toArgb(), scheme.surface.toArgb())
    }

    @Test
    fun outline_failsTheTextThreshold_inBothThemes() {
        // The reason CYP-337 exists. If this ever starts passing, the theme changed and the three recolourings
        // (and CYP-345's guard) should be re-argued from the new numbers — not silently kept.
        for (dark in listOf(false, true)) {
            val r = ratioOnSurface({ it.outline }, dark)
            assertTrue(r < textMin, "outline on surface (${theme(dark)}) is $r:1 — expected BELOW $textMin:1")
        }
    }

    @Test
    fun outline_stillPassesTheGraphicalObjectThreshold_soBordersAndDotsStay() {
        // The other half of the rule, and the reason this ticket must NOT sweep `outline` out of borders,
        // dividers and the STOPPED status dot: as a graphical object it is compliant.
        for (dark in listOf(false, true)) {
            val r = ratioOnSurface({ it.outline }, dark)
            assertTrue(r >= graphicMin, "outline on surface (${theme(dark)}) is $r:1 — expected at least $graphicMin:1")
        }
    }

    @Test
    fun onSurfaceVariant_theReplacement_passesTextContrast_inBothThemes() {
        for (dark in listOf(false, true)) {
            val r = ratioOnSurface({ it.onSurfaceVariant }, dark)
            assertTrue(r >= textMin, "onSurfaceVariant on surface (${theme(dark)}) is $r:1 — expected ≥ $textMin:1")
            assertTrue(r >= aaa, "onSurfaceVariant on surface (${theme(dark)}) is $r:1 — expected AAA (≥ $aaa:1)")
        }
    }

    @Test
    fun onSurfaceVariant_staysQuieterThanContentColour() {
        // The metadata must stay visibly secondary — the fix buys contrast, not loudness. `outline` was never
        // needed for that: `onSurfaceVariant` is already far below `onSurface`.
        for (dark in listOf(false, true)) {
            val metadata = ratioOnSurface({ it.onSurfaceVariant }, dark)
            val content = ratioOnSurface({ it.onSurface }, dark)
            assertTrue(
                metadata < content,
                "metadata (${metadata}:1) must read quieter than content (${content}:1) in ${theme(dark)}",
            )
        }
    }

    private fun theme(dark: Boolean) = if (dark) "dark" else "light"
}
