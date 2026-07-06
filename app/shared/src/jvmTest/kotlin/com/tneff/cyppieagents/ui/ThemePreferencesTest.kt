package com.tneff.cyppieagents.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-268 R3 — the [ThemePreferences] logic. [PersistentThemePreferences] is tested over an in-lambda backing
 * (hermetic; the same shape the JVM `java.util.prefs` and Web `localStorage` actuals wrap), so the durable
 * round-trip + the fail-safe parse are proven without touching a real platform keystore. The thin platform
 * `actual`s (durable KV get/set) draw the same honesty boundary as AuthSessionStore — covered by the compile
 * gate, not a machine-polluting unit test.
 *
 * Mutation proof: make `setThemeMode` store a constant / `themeMode` ignore the load → the round-trip REDs.
 */
class ThemePreferencesTest {

    @Test
    fun persistent_storesTheModeName_andReloadsAcrossAFreshInstance() {
        val backing = HashMap<String, String>()
        val prefs = PersistentThemePreferences(load = { backing["themeMode"] }, store = { backing["themeMode"] = it })

        assertEquals(ThemeMode.SYSTEM, prefs.themeMode(), "nothing stored → follow-system default")
        prefs.setThemeMode(ThemeMode.DARK)
        assertEquals("DARK", backing["themeMode"], "persists the enum NAME (the stable durable token)")

        // A FRESH store over the SAME backing reads it back — the "app restart" path.
        val reloaded = PersistentThemePreferences(load = { backing["themeMode"] }, store = { backing["themeMode"] = it })
        assertEquals(ThemeMode.DARK, reloaded.themeMode(), "the chosen mode survives a restart")
    }

    @Test
    fun persistent_garbledStoredValue_failsSafeToSystem() {
        val prefs = PersistentThemePreferences(load = { "GARBAGE" }, store = {})
        assertEquals(ThemeMode.SYSTEM, prefs.themeMode(), "a forward/garbled stored value never crashes the seam")
    }

    @Test
    fun inMemory_holdsForTheSession() {
        val prefs = InMemoryThemePreferences()
        assertEquals(ThemeMode.SYSTEM, prefs.themeMode(), "default follow-system")
        prefs.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, prefs.themeMode())
    }
}
