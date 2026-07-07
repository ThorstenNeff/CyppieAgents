package com.tneff.cyppieagents.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CYP-268 R1 — the maritime [MaritimeLight]/[MaritimeDark] schemes match UIUX's ratified tokens (v1.1) and preserve
 * the semantic invariants (spec §3): brand accents are maritime BLUE (not the M3 default purple), `error` stays RED
 * in both schemes, light surfaces are bright / dark surfaces deep navy, and the seam's mode selection is correct.
 * R2 does the full WCAG-AA contrast audit; this pins the token transcription + the semantic guardrails.
 */
class MaritimeThemeTest {

    @Test
    fun light_hasRatifiedMaritimeRoleValues() {
        assertEquals(Color(0xFF0A5AA0), MaritimeLight.primary)
        assertEquals(Color(0xFFFFFFFF), MaritimeLight.onPrimary)
        assertEquals(Color(0xFFFFFFFF), MaritimeLight.surface)
        assertEquals(Color(0xFF0C2635), MaritimeLight.onSurface)
        assertEquals(Color(0xFFB3261E), MaritimeLight.error)
        assertEquals(Color(0xFFB7E7F2), MaritimeLight.tertiaryContainer) // the TonedHint(INFO) surface
        assertEquals(Color(0xFF3A4E5A), MaritimeLight.onSurfaceVariant) // the ~57x-used muted text
        assertEquals(Color(0xFF0F5B88), MaritimeLight.secondary) // v1.2 AA-tuned: INFO content on tertiaryContainer
    }

    @Test
    fun dark_hasRatifiedMaritimeRoleValues() {
        assertEquals(Color(0xFF6FBEEA), MaritimeDark.primary)
        assertEquals(Color(0xFF02324E), MaritimeDark.onPrimary)
        assertEquals(Color(0xFF06121A), MaritimeDark.surface) // CYP-304 Night: blacker than CYP-268-Dark (#0A1922)
        assertEquals(Color(0xFFDCE7ED), MaritimeDark.onSurface)
        assertEquals(Color(0xFFF2B8B5), MaritimeDark.error)
        assertEquals(Color(0xFF0C3D30), MaritimeDark.tertiaryContainer) // CYP-304 Night: signal-green container
        assertEquals(Color(0xFF93CCEA), MaritimeDark.secondary) // v1.2 AA-tuned
    }

    @Test
    fun night_tertiaryIsSignalGreen_surfacesBlacker_cyp304() {
        // CYP-304 — the Night accent is signal-GREEN (green-dominant), a BRAND-only identity hue-separated from
        // primary-blue and error-red. §9-Inv.1 is now LOAD-BEARING: this green must never mean status — a0
        // (CYP-300) de-overloaded every semantic `tertiary`, and the CYP-303 source-guard keeps it that way.
        assertEquals(Color(0xFF40D6A0), MaritimeDark.tertiary)
        val t = MaritimeDark.tertiary
        assertTrue(t.green > t.red && t.green > t.blue, "night tertiary is green-dominant (signal green), not the old teal")
        assertNotEquals(MaritimeDark.primary, MaritimeDark.tertiary, "the green accent is distinct from the blue primary")
        assertTrue(MaritimeDark.error.red > MaritimeDark.error.green, "error stays red-dominant, hue-distinct from the green accent")
        // Surfaces are BLACKER than the accepted CYP-268-Dark (#0A1922 → #06121A) — the deeper maritime night.
        assertEquals(Color(0xFF06121A), MaritimeDark.surface)
        assertEquals(Color(0xFF06121A), MaritimeDark.background)
    }

    @Test
    fun brandIsMaritimeBlue_errorStaysRed_inBothSchemes() {
        // CYP-268 R3 sharpen: assert GREEN > RED (not blue > red). The M3 baseline purple (#6750A4) is ALSO
        // blue-dominant (B 164 > R 103), so `blue > red` would let purple pass; but purple's green (80) < red
        // (103), whereas the maritime primary is a true blue with green > red (light #0A5AA0 G90>R10, dark
        // #6FBEEA G190>R111). So `green > red` genuinely EXCLUDES purple — belt-and-suspenders with the value-pin.
        assertTrue(MaritimeLight.primary.green > MaritimeLight.primary.red, "light primary is maritime blue, not purple")
        assertTrue(MaritimeDark.primary.green > MaritimeDark.primary.red, "dark primary is maritime blue, not purple")
        // §3 semantics preserved: error stays a red hue (red channel dominant) — data-loss meaning, never re-branded.
        assertTrue(MaritimeLight.error.red > MaritimeLight.error.blue, "light error stays red")
        assertTrue(MaritimeDark.error.red > MaritimeDark.error.blue, "dark error stays red")
    }

    @Test
    fun surfaces_lightIsBright_darkIsDeepNavy() {
        fun luminance(c: Color) = c.red * 0.299f + c.green * 0.587f + c.blue * 0.114f
        assertTrue(luminance(MaritimeLight.surface) > 0.9f, "light surface is bright")
        assertTrue(luminance(MaritimeDark.surface) < 0.2f, "dark surface is a deep navy")
        assertTrue(luminance(MaritimeLight.onSurface) < 0.3f, "light on-surface text is dark (readable on white)")
        assertTrue(luminance(MaritimeDark.onSurface) > 0.7f, "dark on-surface text is light (readable on navy)")
    }

    @Test
    fun maritimeColorScheme_selectsByMode() {
        assertSame(MaritimeLight, maritimeColorScheme(dark = false))
        assertSame(MaritimeDark, maritimeColorScheme(dark = true))
    }

    @Test
    fun lightAndDark_areDistinctSchemes() {
        assertNotEquals(MaritimeLight.primary, MaritimeDark.primary)
        assertNotEquals(MaritimeLight.surface, MaritimeDark.surface)
        assertNotEquals(MaritimeLight.onSurface, MaritimeDark.onSurface)
    }
}
