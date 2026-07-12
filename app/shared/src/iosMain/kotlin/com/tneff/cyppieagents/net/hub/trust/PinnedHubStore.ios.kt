package com.tneff.cyppieagents.net.hub.trust

/**
 * iOS (stubbed target): durable pin persistence is **deferred**, mirroring
 * [com.tneff.cyppieagents.ui.ThemePreferences]'s in-memory iOS default. A real Keychain-backed store lands with
 * the iOS build-out; the in-memory store keeps the target compiling and is correct for a running session.
 */
actual fun defaultPinnedHubStore(): PinnedHubStore = InMemoryPinnedHubStore()
