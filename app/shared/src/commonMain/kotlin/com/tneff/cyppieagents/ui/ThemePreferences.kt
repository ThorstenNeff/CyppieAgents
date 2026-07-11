package com.tneff.cyppieagents.ui

/**
 * The personal, **ungated** app preferences (CYP-268 R3 theme mode; CYP-387 composer input-history size). An
 * interface + an in-memory default in commonMain, with durable cross-restart persistence a **platform `actual`**
 * concern ([defaultThemePreferences]). Mirrors the [com.tneff.cyppieagents.auth.AuthSessionStore] idiom.
 *
 * The reads are deliberately **synchronous**: the `App.kt` seam sits ABOVE `AuthGate` and resolves values at
 * composition, with no coroutine scope to await an async load.
 *
 * CYP-387 §3: the input-history size is **one global, personal, ungated** number (not operator/project config,
 * not per-agent) — hence its home here, beside the theme mode, rather than in the operator `SettingsPanel`.
 */
interface ThemePreferences {
    fun themeMode(): ThemeMode
    fun setThemeMode(mode: ThemeMode)

    /** CYP-387: N = messages kept in the composer input history (arrow-up/down recall). `0` = off; range 0..200. */
    fun composerHistorySize(): Int
    fun setComposerHistorySize(size: Int)
}

/** Process-local holder — the default until/unless a platform persists it (Android/iOS, and the hermetic tests). */
class InMemoryThemePreferences(
    initial: ThemeMode = ThemeMode.SYSTEM,
    initialHistorySize: Int = DEFAULT_COMPOSER_HISTORY_SIZE,
) : ThemePreferences {
    private var mode = initial
    private var historySize = clampComposerHistorySize(initialHistorySize)
    override fun themeMode(): ThemeMode = mode
    override fun setThemeMode(mode: ThemeMode) { this.mode = mode }
    override fun composerHistorySize(): Int = historySize
    override fun setComposerHistorySize(size: Int) { historySize = clampComposerHistorySize(size) }
}

/**
 * Durable [ThemePreferences] over a platform **keyed** key-value primitive. The value↔String mappings + the
 * fail-safe defaults live HERE (commonMain, unit-tested), so each platform `actual` only wires the raw keyed
 * get/set. An unknown/absent/garbled stored value **fails safe** (theme → [ThemeMode.SYSTEM]; history size →
 * [DEFAULT_COMPOSER_HISTORY_SIZE]) — a forward/garbled value never crashes the seam.
 */
class PersistentThemePreferences(
    private val load: (key: String) -> String?,
    private val store: (key: String, value: String) -> Unit,
) : ThemePreferences {
    override fun themeMode(): ThemeMode = parseThemeMode(load(THEME_MODE_KEY))
    override fun setThemeMode(mode: ThemeMode) = store(THEME_MODE_KEY, mode.name)
    override fun composerHistorySize(): Int = parseComposerHistorySize(load(HISTORY_SIZE_KEY))
    override fun setComposerHistorySize(size: Int) = store(HISTORY_SIZE_KEY, clampComposerHistorySize(size).toString())
}

/** Fail-safe parse: an unrecognised or absent raw value maps to [ThemeMode.SYSTEM] (the follow-system default). */
internal fun parseThemeMode(raw: String?): ThemeMode =
    ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.SYSTEM

/** Fail-safe parse: absent/garbled → the default 20; any value clamped to the valid range (0..200). */
internal fun parseComposerHistorySize(raw: String?): Int =
    clampComposerHistorySize(raw?.toIntOrNull() ?: DEFAULT_COMPOSER_HISTORY_SIZE)

/** CYP-387 §3.1: the number is clamped to 0..200 (`0` = off; 200 caps the session-in-memory store's growth). */
internal fun clampComposerHistorySize(n: Int): Int =
    n.coerceIn(MIN_COMPOSER_HISTORY_SIZE, MAX_COMPOSER_HISTORY_SIZE)

const val DEFAULT_COMPOSER_HISTORY_SIZE = 20
const val MIN_COMPOSER_HISTORY_SIZE = 0
const val MAX_COMPOSER_HISTORY_SIZE = 200

internal const val THEME_MODE_KEY = "themeMode"
internal const val HISTORY_SIZE_KEY = "composerHistorySize"

/** The production store for the current platform: durable where a synchronous KV exists, in-memory otherwise. */
expect fun defaultThemePreferences(): ThemePreferences
