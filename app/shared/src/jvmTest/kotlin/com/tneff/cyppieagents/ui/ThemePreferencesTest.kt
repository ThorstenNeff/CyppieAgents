package com.tneff.cyppieagents.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-268 R3 / CYP-387 — the [ThemePreferences] logic. [PersistentThemePreferences] is tested over an in-lambda
 * KEYED backing (hermetic; the same shape the JVM `java.util.prefs` and Web `localStorage` actuals wrap), so the
 * durable round-trip + the fail-safe parses are proven without touching a real platform keystore. The thin
 * platform `actual`s (durable keyed KV get/set) draw the same honesty boundary as AuthSessionStore — covered by
 * the compile gate, not a machine-polluting unit test.
 *
 * Mutation proof: make a setter store a constant / a getter ignore the load → the round-trip REDs.
 */
class ThemePreferencesTest {

    @Test
    fun persistent_storesTheModeName_andReloadsAcrossAFreshInstance() {
        val backing = HashMap<String, String>()
        val prefs = PersistentThemePreferences(load = { k -> backing[k] }, store = { k, v -> backing[k] = v })

        assertEquals(ThemeMode.SYSTEM, prefs.themeMode(), "nothing stored → follow-system default")
        prefs.setThemeMode(ThemeMode.DARK)
        assertEquals("DARK", backing[THEME_MODE_KEY], "persists the enum NAME (the stable durable token)")

        // A FRESH store over the SAME backing reads it back — the "app restart" path.
        val reloaded = PersistentThemePreferences(load = { k -> backing[k] }, store = { k, v -> backing[k] = v })
        assertEquals(ThemeMode.DARK, reloaded.themeMode(), "the chosen mode survives a restart")
    }

    @Test
    fun persistent_garbledStoredValue_failsSafeToSystem() {
        val prefs = PersistentThemePreferences(load = { "GARBAGE" }, store = { _, _ -> })
        assertEquals(ThemeMode.SYSTEM, prefs.themeMode(), "a forward/garbled stored value never crashes the seam")
    }

    @Test
    fun inMemory_holdsForTheSession() {
        val prefs = InMemoryThemePreferences()
        assertEquals(ThemeMode.SYSTEM, prefs.themeMode(), "default follow-system")
        prefs.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, prefs.themeMode())
    }

    // --- CYP-387: the composer input-history size N (durable, personal, one global number) ---

    /** Round-trips N durably across a fresh instance (the "app restart" path — a size setting that resets to 20
     *  on restart is a papercut). Mutation: `setComposerHistorySize` store a constant ⇒ reload wrong ⇒ red. */
    @Test
    fun persistent_historySize_roundTripsAndReloads() {
        val backing = HashMap<String, String>()
        val prefs = PersistentThemePreferences(load = { k -> backing[k] }, store = { k, v -> backing[k] = v })

        assertEquals(DEFAULT_COMPOSER_HISTORY_SIZE, prefs.composerHistorySize(), "nothing stored → default 20")
        prefs.setComposerHistorySize(50)
        assertEquals("50", backing[HISTORY_SIZE_KEY], "persists N as its decimal string, under its own key")

        val reloaded = PersistentThemePreferences(load = { k -> backing[k] }, store = { k, v -> backing[k] = v })
        assertEquals(50, reloaded.composerHistorySize(), "the chosen N survives a restart")
    }

    /** N and the theme mode use SEPARATE keys — neither setter clobbers the other's slot.
     *  Mutation: reuse THEME_MODE_KEY for N ⇒ setting N corrupts the theme ⇒ red. */
    @Test
    fun persistent_historySizeAndThemeMode_useSeparateKeys() {
        val backing = HashMap<String, String>()
        val prefs = PersistentThemePreferences(load = { k -> backing[k] }, store = { k, v -> backing[k] = v })
        prefs.setThemeMode(ThemeMode.DARK)
        prefs.setComposerHistorySize(7)
        assertEquals(ThemeMode.DARK, prefs.themeMode(), "setting N did not disturb the theme")
        assertEquals(7, prefs.composerHistorySize(), "setting the theme did not disturb N")
    }

    /** Fail-safe + clamp: absent/garbled → 20; out-of-range is clamped to 0..200 (never crashes, never unbounded).
     *  Mutation: drop the clamp/parse fallback ⇒ a garbled value throws or a 9999 store grows unbounded ⇒ red. */
    @Test
    fun historySize_failsSafeAndClampsToRange() {
        assertEquals(20, parseComposerHistorySize(null), "absent → default 20")
        assertEquals(20, parseComposerHistorySize("not-a-number"), "garbled → default 20")
        assertEquals(200, parseComposerHistorySize("9999"), "over-range stored value clamps to 200")
        assertEquals(0, parseComposerHistorySize("-5"), "negative clamps to 0 (off)")
        assertEquals(0, clampComposerHistorySize(-1))
        assertEquals(200, clampComposerHistorySize(500))
        val prefs = InMemoryThemePreferences(initialHistorySize = 1000)
        assertEquals(200, prefs.composerHistorySize(), "the in-memory store clamps on construction too")
    }
}
