package com.tneff.cyppieagents.net.hub.trust

import java.util.prefs.Preferences

/**
 * JVM/desktop (primary target): TOFU pins persist across restarts in `java.util.prefs` — the same synchronous,
 * dependency-free user-scoped KV that backs [com.tneff.cyppieagents.ui.ThemePreferences] (a shared node; pin
 * keys are `hubpin.*`-namespaced so they never collide). The Base64 mapping + fail-safe decode live in
 * commonMain ([PersistentPinnedHubStore]); this only wires the raw keyed get/put/remove.
 */
private val prefs: Preferences = Preferences.userRoot().node("com/tneff/cyppieagents")

actual fun defaultPinnedHubStore(): PinnedHubStore =
    PersistentPinnedHubStore(
        load = { key -> prefs.get(key, null) },
        store = { key, value -> prefs.put(key, value) },
        remove = { key -> prefs.remove(key) },
    )
