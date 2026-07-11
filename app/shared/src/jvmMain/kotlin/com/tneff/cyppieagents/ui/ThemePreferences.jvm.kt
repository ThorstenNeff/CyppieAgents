package com.tneff.cyppieagents.ui

import java.util.prefs.Preferences

/**
 * JVM/desktop (primary target): the preferences persist across restarts in `java.util.prefs` — a synchronous,
 * dependency-free user-scoped key-value store (no file paths, no coroutines). The value↔String mappings + the
 * fail-safe defaults live in commonMain ([PersistentThemePreferences]); this only wires the raw keyed get/put.
 */
private val prefs: Preferences = Preferences.userRoot().node("com/tneff/cyppieagents")

actual fun defaultThemePreferences(): ThemePreferences =
    PersistentThemePreferences(
        load = { key -> prefs.get(key, null) },
        store = { key, value -> prefs.put(key, value) },
    )
