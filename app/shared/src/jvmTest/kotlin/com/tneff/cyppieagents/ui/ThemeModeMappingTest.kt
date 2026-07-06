package com.tneff.cyppieagents.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-268 R3 — the pure seam decision ([ThemeMode.isDark]) + the fail-safe raw-value parse ([parseThemeMode]).
 * These are the whole logic of the toggle; the App.kt seam is just `maritimeColorScheme(mode.isDark(system))`,
 * where `maritimeColorScheme` is separately pinned by MaritimeThemeTest.maritimeColorScheme_selectsByMode.
 *
 * Mutation proof: flip any [isDark] branch (e.g. LIGHT -> systemDark) → the matching assertion REDs; drop the
 * `?: SYSTEM` fallback in [parseThemeMode] → the unknown/absent cases throw/RED.
 */
class ThemeModeMappingTest {

    @Test
    fun system_followsTheOsFlag() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true), "SYSTEM + OS dark → dark")
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false), "SYSTEM + OS light → light")
    }

    @Test
    fun light_forcesLight_regardlessOfOs() {
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = true), "LIGHT overrides an OS-dark → still light")
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = false))
    }

    @Test
    fun dark_forcesDark_regardlessOfOs() {
        assertTrue(ThemeMode.DARK.isDark(systemDark = false), "DARK overrides an OS-light → still dark")
        assertTrue(ThemeMode.DARK.isDark(systemDark = true))
    }

    @Test
    fun parse_knownEnumNames_roundTrip() {
        assertEquals(ThemeMode.SYSTEM, parseThemeMode("SYSTEM"))
        assertEquals(ThemeMode.LIGHT, parseThemeMode("LIGHT"))
        assertEquals(ThemeMode.DARK, parseThemeMode("DARK"))
    }

    @Test
    fun parse_unknownOrAbsent_failsSafeToSystem() {
        assertEquals(ThemeMode.SYSTEM, parseThemeMode(null), "absent → SYSTEM")
        assertEquals(ThemeMode.SYSTEM, parseThemeMode(""), "blank → SYSTEM")
        assertEquals(ThemeMode.SYSTEM, parseThemeMode("MIDNIGHT"), "forward/garbled value → SYSTEM (never crash)")
        assertEquals(ThemeMode.SYSTEM, parseThemeMode("dark"), "case-sensitive: lowercase ≠ enum name → SYSTEM")
    }
}
