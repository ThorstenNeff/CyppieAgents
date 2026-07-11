package com.tneff.cyppieagents.ui

/**
 * Web (Kotlin/JS) — the deploy target (`:app:webApp`): the preferences persist in `window.localStorage`, a
 * synchronous browser KV. Mirrors the `ShellConfig.js` `js("… .toString()")` interop idiom. The mappings +
 * fail-safe defaults live in commonMain ([PersistentThemePreferences]); this only wires the raw keyed get/set.
 *
 * [rawGet] returns `""` when the key is unset (undefined-safe via `|| ''`), which the commonMain parses treat
 * as "unknown" → the follow-system theme / the default history size — never a crash.
 */
private fun rawGet(key: String): String = js("(window.localStorage.getItem('cyppie.' + key) || '').toString()")

private fun rawSet(key: String, value: String): String =
    js("(window.localStorage.setItem('cyppie.' + key, value), '')")

actual fun defaultThemePreferences(): ThemePreferences =
    PersistentThemePreferences(
        load = { key -> rawGet(key) },
        store = { key, value -> rawSet(key, value) },
    )
