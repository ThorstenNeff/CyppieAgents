package com.tneff.cyppieagents.ui

/**
 * CYP-268 R3 — the single persisted app preference: the chosen [ThemeMode]. Mirrors the
 * [com.tneff.cyppieagents.auth.AuthSessionStore] idiom — an interface + an in-memory default in commonMain,
 * with durable cross-restart persistence a **platform `actual`** concern ([defaultThemePreferences]).
 *
 * The read is deliberately **synchronous**: the `App.kt` theme seam sits ABOVE `AuthGate` and resolves the
 * scheme at composition, with no coroutine scope to await an async load.
 */
interface ThemePreferences {
    fun themeMode(): ThemeMode
    fun setThemeMode(mode: ThemeMode)
}

/** Process-local holder — the default until/unless a platform persists it (Android/iOS, and the hermetic tests). */
class InMemoryThemePreferences(initial: ThemeMode = ThemeMode.SYSTEM) : ThemePreferences {
    private var mode = initial
    override fun themeMode(): ThemeMode = mode
    override fun setThemeMode(mode: ThemeMode) { this.mode = mode }
}

/**
 * Durable [ThemePreferences] over a platform key-value primitive. The [ThemeMode]↔String mapping lives HERE
 * (commonMain, unit-tested), so each platform `actual` only wires the raw get/set — and an unknown or absent
 * stored value **fails safe to [ThemeMode.SYSTEM]** (a forward/garbled value never crashes the theme seam).
 */
class PersistentThemePreferences(
    private val load: () -> String?,
    private val store: (String) -> Unit,
) : ThemePreferences {
    override fun themeMode(): ThemeMode = parseThemeMode(load())
    override fun setThemeMode(mode: ThemeMode) = store(mode.name)
}

/** Fail-safe parse: an unrecognised or absent raw value maps to [ThemeMode.SYSTEM] (the follow-system default). */
internal fun parseThemeMode(raw: String?): ThemeMode =
    ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.SYSTEM

/** The production store for the current platform: durable where a synchronous KV exists, in-memory otherwise. */
expect fun defaultThemePreferences(): ThemePreferences
