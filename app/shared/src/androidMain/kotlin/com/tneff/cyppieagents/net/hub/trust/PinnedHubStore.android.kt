package com.tneff.cyppieagents.net.hub.trust

/**
 * Android (secondary target): durable pin persistence is **deferred** — mirroring
 * [com.tneff.cyppieagents.ui.ThemePreferences]'s in-memory Android default. Real `SharedPreferences`/Keystore
 * needs an application `Context`, not plumbed into these commonMain factories; tracked under the Android-parity
 * epic (CYP-66). The in-memory store is correct for a running session and the hermetic tests. (Note: without
 * durable persistence, TOFU cannot detect a key change across app restarts on Android until CYP-66 — flagged.)
 */
actual fun defaultPinnedHubStore(): PinnedHubStore = InMemoryPinnedHubStore()
