package com.tneff.cyppieagents.ui

import java.util.prefs.Preferences

/**
 * JVM/desktop (primary target): the theme mode persists across restarts in `java.util.prefs` — a synchronous,
 * dependency-free user-scoped key-value store (no file paths, no coroutines). The [ThemeMode]↔String mapping +
 * the fail-safe default live in commonMain ([PersistentThemePreferences]); this only wires the raw get/put.
 */
private val prefs: Preferences = Preferences.userRoot().node("com/tneff/cyppieagents")
private const val THEME_MODE_KEY = "themeMode"

actual fun defaultThemePreferences(): ThemePreferences =
    PersistentThemePreferences(
        load = { prefs.get(THEME_MODE_KEY, null) },
        store = { prefs.put(THEME_MODE_KEY, it) },
    )
