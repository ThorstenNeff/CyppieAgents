package com.tneff.cyppieagents.ui

/**
 * iOS (stubbed target): durable persistence is **deferred**, mirroring
 * [com.tneff.cyppieagents.auth.AuthSessionStore]'s in-memory default. A real `NSUserDefaults`-backed store lands
 * with the iOS build-out; the in-memory store keeps the target compiling and is correct for a running session.
 */
actual fun defaultThemePreferences(): ThemePreferences = InMemoryThemePreferences()
