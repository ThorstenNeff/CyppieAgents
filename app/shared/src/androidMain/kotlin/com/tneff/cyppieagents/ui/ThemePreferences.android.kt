package com.tneff.cyppieagents.ui

/**
 * Android (secondary target): durable persistence is **deferred** — exactly like
 * [com.tneff.cyppieagents.auth.AuthSessionStore]'s in-memory default. Real `SharedPreferences` needs an
 * application `Context`, which is not plumbed into these commonMain factories (the `ShellConfig.android`
 * actual reads no persisted state either). Real Android theme persistence is tracked under the Android-parity
 * epic (CYP-66); the in-memory store is correct for a running session and the hermetic tests.
 */
actual fun defaultThemePreferences(): ThemePreferences = InMemoryThemePreferences()
