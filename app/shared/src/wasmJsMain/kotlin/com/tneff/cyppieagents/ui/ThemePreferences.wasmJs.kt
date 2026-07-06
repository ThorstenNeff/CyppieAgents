package com.tneff.cyppieagents.ui

/**
 * Web (Kotlin/Wasm): the theme mode persists in `window.localStorage`, a synchronous browser KV. Mirrors the
 * `ShellConfig.wasmJs` `js("… .toString()")` interop idiom (a `js()` function references its parameters by
 * name). The mapping + fail-safe default live in commonMain ([PersistentThemePreferences]).
 *
 * [rawThemeMode] returns `""` when the key is unset (undefined-safe via `|| ''`), which the commonMain parse
 * treats as "unknown" → [ThemeMode.SYSTEM] (the follow-system default) — never a crash.
 */
private fun rawThemeMode(): String = js("(window.localStorage.getItem('cyppie.themeMode') || '').toString()")

private fun storeThemeMode(value: String): String =
    js("(window.localStorage.setItem('cyppie.themeMode', value), '')")

actual fun defaultThemePreferences(): ThemePreferences =
    PersistentThemePreferences(
        load = { rawThemeMode() },
        store = { storeThemeMode(it) },
    )
