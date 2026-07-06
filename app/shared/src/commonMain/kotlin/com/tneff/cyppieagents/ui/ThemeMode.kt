package com.tneff.cyppieagents.ui

/**
 * CYP-268 R3 — the user-selectable app theme mode. [SYSTEM] follows the OS (the R1 default), [LIGHT]/[DARK]
 * force a scheme explicitly. The `App.kt` theme seam maps this to the maritime [MaritimeLight]/[MaritimeDark]
 * scheme; the M3 roles stay the single colour source (this only *chooses which scheme is active* — it adds no
 * colours of its own).
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * The seam decision (pure → exhaustively unit-tested): does this mode render dark? [SYSTEM] defers to the OS
 * flag ([systemDark] = `isSystemInDarkTheme()`); an explicit [LIGHT]/[DARK] overrides it regardless of the OS.
 */
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
