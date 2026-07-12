package com.tneff.cyppieagents.net.hub.trust

/**
 * Web (Kotlin/JS) — the deploy target (`:app:webApp`): TOFU pins persist in `window.localStorage`, a
 * synchronous browser KV (the same store [com.tneff.cyppieagents.ui.ThemePreferences] uses, `cyppie.`-prefixed).
 * The Base64 mapping + fail-safe decode live in commonMain ([PersistentPinnedHubStore]); this only wires the raw
 * keyed get/set/remove. [rawGet] returns `""` for an unset key (undefined-safe `|| ''`), which the commonMain
 * decode treats as "not pinned" — never a crash.
 */
private fun rawGet(key: String): String = js("(window.localStorage.getItem('cyppie.' + key) || '').toString()")

private fun rawSet(key: String, value: String): String =
    js("(window.localStorage.setItem('cyppie.' + key, value), '')")

private fun rawRemove(key: String): String = js("(window.localStorage.removeItem('cyppie.' + key), '')")

actual fun defaultPinnedHubStore(): PinnedHubStore =
    PersistentPinnedHubStore(
        load = { key -> rawGet(key) },
        store = { key, value -> rawSet(key, value) },
        remove = { key -> rawRemove(key) },
    )
