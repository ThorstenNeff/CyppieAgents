package com.tneff.cyppieagents.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.contrastRatio
import com.tneff.cyppieagents.model.readableAccentOn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-275 — every sender/agent NAME accent must be AA-readable as TEXT (≥ 4.5:1) on BOTH maritime surfaces, with
 * the CYP-14 identity HUE preserved. [readableAccentOn] adapts luminance to the surface: on the maritime DARK
 * (navy) surface the CYP-14 pastels already clear the target → returned unchanged (no regression); on the LIGHT
 * (white) surface the raw pastels wash out (the bug) and are darkened until they clear it.
 *
 * These teeth run over the REAL [SenderPalette] palette (8 swatches + PO). Mutation proof: neuter
 * [readableAccentOn] to return `accent` unchanged → [allAccents_readableOnLight] REDs (the raw dark-palette
 * pastels fail 4.5:1 on white — [rawPastels_failOnLight_thenFixed] pins that they truly fail, then are lifted).
 */
class NameAccentReadabilityTest {

    private val white = Color(0xFFFFFFFF).toArgb() // maritime light surface
    private val navy = Color(0xFF0A1922).toArgb()  // maritime dark surface (MaritimeDark.surface)
    private val aa = 4.5

    /** All identity name accents: the 8 palette swatches + the reserved PO slot. */
    private fun allAccents(): List<Int> =
        SenderPalette.swatches.map { it.nameAccent.toArgb() } +
            SenderPalette.forSender("po", Role.PO).nameAccent.toArgb()

    @Test
    fun allAccents_readableOnLight() {
        for (a in allAccents()) {
            val adapted = readableAccentOn(a, white)
            assertTrue(contrastRatio(adapted, white) >= aa, "accent ${hex(a)} → ${hex(adapted)} must be ≥AA on white")
        }
    }

    @Test
    fun allAccents_readableOnDark() {
        for (a in allAccents()) {
            assertTrue(contrastRatio(readableAccentOn(a, navy), navy) >= aa, "accent ${hex(a)} must be ≥AA on navy")
        }
    }

    @Test
    fun darkPastels_unchangedOnDark_noRegression() {
        // The CYP-14 pastels already clear AA on the navy surface → returned verbatim (the dark scheme is untouched).
        for (a in allAccents()) {
            assertEquals(a, readableAccentOn(a, navy), "accent ${hex(a)} must be unchanged on the dark surface")
        }
    }

    @Test
    fun rawPastels_failOnLight_thenFixed() {
        // Non-vacuous, tied to the sweep evidence: PO #A6A9F0 (2.2:1) + olive #BFC97E (1.77:1) FAIL raw on white,
        // and the adaptation lifts BOTH to ≥AA — so the fix is doing real work, not asserting a no-op.
        val po = SenderPalette.forSender("po", Role.PO).nameAccent.toArgb()
        val olive = SenderPalette.swatches[7].nameAccent.toArgb() // slot 7 = olive
        assertTrue(contrastRatio(po, white) < aa, "raw PO accent washes out on white")
        assertTrue(contrastRatio(olive, white) < aa, "raw olive accent washes out on white")
        assertTrue(contrastRatio(readableAccentOn(po, white), white) >= aa, "adapted PO accent is AA on white")
        assertTrue(contrastRatio(readableAccentOn(olive, white), white) >= aa, "adapted olive accent is AA on white")
    }

    private fun hex(argb: Int) = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0')
}
