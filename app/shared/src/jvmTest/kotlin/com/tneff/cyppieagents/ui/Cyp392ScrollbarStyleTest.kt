package com.tneff.cyppieagents.ui

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.contrastRatio
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-392 — the [ThinVerticalScrollbar] STYLE contract (spec §2/§3/§5). **Test 3 (contrast pair) is the core**:
 * the resting thumb is a graphical object, so it must clear WCAG 1.4.11 (3:1, not 4.5:1) against `surface` in
 * BOTH themes — and that is exactly why the role is `outline`, not the too-faint `outlineVariant`.
 */
class Cyp392ScrollbarStyleTest {

    /** §3 CORE — rest colour (`outline`) vs `surface` ≥ 3:1 in light AND dark; `outlineVariant` would fail.
     *  Mutation (in code): rest = `outlineVariant` ⇒ the source guard below reds; the numbers here prove WHY. */
    @Test
    fun restColour_clearsGraphicalContrast_inBothThemes() {
        val light = contrastRatio(MaritimeLight.outline.toArgb(), MaritimeLight.surface.toArgb())
        val dark = contrastRatio(MaritimeDark.outline.toArgb(), MaritimeDark.surface.toArgb())
        assertTrue(light >= 3.0, "outline↔surface (light) = $light must be ≥ 3:1 (graphical object, WCAG 1.4.11)")
        assertTrue(dark >= 3.0, "outline↔surface (dark) = $dark must be ≥ 3:1")

        // The forbidden shortcut, measured: outlineVariant is BELOW the graphical floor — the reason §2 picks outline.
        val ovLight = contrastRatio(MaritimeLight.outlineVariant.toArgb(), MaritimeLight.surface.toArgb())
        val ovDark = contrastRatio(MaritimeDark.outlineVariant.toArgb(), MaritimeDark.surface.toArgb())
        assertTrue(ovLight < 3.0 && ovDark < 3.0, "outlineVariant ($ovLight / $ovDark) is < 3:1 — never the thumb rest colour")
    }

    /** §3 — active (hover/drag) colour `onSurfaceVariant` is at least as strong as rest (stronger on grab). */
    @Test
    fun activeColour_isStrongerThanRest() {
        val restL = contrastRatio(MaritimeLight.outline.toArgb(), MaritimeLight.surface.toArgb())
        val activeL = contrastRatio(MaritimeLight.onSurfaceVariant.toArgb(), MaritimeLight.surface.toArgb())
        assertTrue(activeL >= restL, "hover ($activeL) must be ≥ rest ($restL) — the thumb gets more present on grab")
    }

    /** §5 — the shipped dimensions (thumb fits inside the 12dp transcript padding; grabbable min height; pill). */
    @Test
    fun dimensions_matchSpec() {
        assertEquals(8.dp, THIN_SCROLLBAR_THICKNESS, "thumb thickness 8dp (inside the 12dp contentPadding)")
        assertEquals(24.dp, THIN_SCROLLBAR_MIN_HEIGHT, "min thumb height 24dp (WCAG 2.5.8 grab target)")
        assertEquals(4.dp, THIN_SCROLLBAR_CORNER, "pill = thickness/2")
    }

    /** §4 — no new colour: the commonMain component uses ONLY the two design-system roles, no raw `Color(0x…)`.
     *  Mutation: rest = `outlineVariant` (or a raw Color) ⇒ this guard reds. */
    @Test
    fun usesOnlyDesignSystemRoles_noRawColour_norOutlineVariant() {
        val src = resolve("src/commonMain/kotlin/com/tneff/cyppieagents/ui/ThinVerticalScrollbar.kt").readText()
        val body = src.lineSequence().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
            .joinToString("\n") // ignore KDoc/comments (they may name outlineVariant/CYP-337 in prose)
        assertTrue(body.contains("colorScheme.outline"), "rest colour must be the `outline` role")
        assertTrue(body.contains("colorScheme.onSurfaceVariant"), "active colour must be the `onSurfaceVariant` role")
        assertTrue(!body.contains("outlineVariant"), "outlineVariant is < 3:1 — must not be the thumb colour")
        assertTrue(!Regex("""Color\(0x""").containsMatchIn(body), "no raw Color(0x…) literal — design-system roles only")
    }

    private fun resolve(relative: String): File {
        var dir = File(".").absoluteFile
        repeat(4) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, "app/shared/$relative").takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile ?: return@repeat
        }
        fail("source not found: $relative — has ThinVerticalScrollbar moved? A guard that can't find its file is worse than none.")
    }
}
