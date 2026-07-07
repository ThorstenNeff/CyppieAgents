package com.tneff.cyppieagents.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * CYP-268 R1 — the maritime M3 [ColorScheme] (blue/white, calm/anti-hype), from UIUX's ratified tokens
 * (`docs/design/maritime-app-redesign-scope-tokens.json` v1.2 @ e930802 — R2 palette contrast audit folded in:
 * all pairs WCAG AA in both schemes, incl. the tuned `secondary` (the TonedHint(INFO) content on tertiaryContainer).
 *
 * Injected at the SINGLE `App.kt` `MaterialTheme(colorScheme = …)` seam → recolours nearly the whole app without
 * touching any screen (M3 theme-ready: every surface already reads `MaterialTheme.colorScheme.*`). Pure re-colouring:
 * 0 strings, 0 testTags, 0 layout/flow change. 24 roles per scheme.
 *
 * Scope boundaries (semantics preserved, WCAG 1.4.1 — colour is never the sole carrier):
 *  - `error` stays a red hue in both schemes (data-loss meaning, not brand).
 *  - Severity ampel ([com.tneff.cyppieagents.eventlog.EventVisuals]) + the 9-slot [SenderPalette] agent identities do
 *    NOT hang on the scheme and stay semantic (R2 re-verifies their contrast on the maritime surfaces).
 *  - [TonedHint] uses only scheme roles → it follows automatically.
 *
 * R2 (contrast audit) verifies/tunes every text-on-background pair to WCAG AA (body 4.5:1 / UI 3:1) in BOTH schemes —
 * especially `onSurfaceVariant` and the `TonedHint(INFO)` combo (secondary on tertiaryContainer). R3 adds the explicit
 * day/night toggle; R1 wires both schemes with follow-system as the default (via `isSystemInDarkTheme()` at the seam).
 */
val MaritimeLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF0A5AA0), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBEE0FA), onPrimaryContainer = Color(0xFF04263F),
    secondary = Color(0xFF0F5B88), onSecondary = Color(0xFFFFFFFF), // v1.2: darkened for AA on tertiaryContainer (INFO)
    secondaryContainer = Color(0xFFC4E2F5), onSecondaryContainer = Color(0xFF06283D),
    tertiary = Color(0xFF0B7E9C), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFB7E7F2), onTertiaryContainer = Color(0xFF022E3B),
    error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFFFFFF), onBackground = Color(0xFF0C2635),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF0C2635),
    surfaceVariant = Color(0xFFE4EFF8), onSurfaceVariant = Color(0xFF3A4E5A),
    outline = Color(0xFF6E8C9E), outlineVariant = Color(0xFFCBDCE7),
)

// CYP-304 (design-system pkg `a`): the maritime NIGHT scheme — Dunkelblau/Schwarz/Grün — from UIUX's ratified
// `maritime-design-system-tokens.json` §3 Night. Replaces the accepted CYP-268 Dark (Auftraggeber-ratified E2).
// Deltas vs CYP-268-Dark: surfaces blacker (`#0A1922`→`#06121A`); `tertiary` re-hued from harbour-teal to the
// SIGNAL-GREEN maritime night accent `#40D6A0` (starboard lantern, hue ~158° — separated from primary-blue/error-
// red/warn-amber; 10.2:1 AAA on surface). `primary` stays blue, `error` stays red. This green is a BRAND accent
// ONLY, NEVER status (§9-Inv.1) — valid only because pkg a0 (CYP-300) de-overloaded every semantic `tertiary`
// use (WARN→amber, in-progress→neutral, deferred→secondary); the CYP-303 source-guard keeps it that way.
val MaritimeDark: ColorScheme = darkColorScheme(
    primary = Color(0xFF6FBEEA), onPrimary = Color(0xFF02324E),
    primaryContainer = Color(0xFF0A4E7C), onPrimaryContainer = Color(0xFFC7E6FB),
    secondary = Color(0xFF93CCEA), onSecondary = Color(0xFF052A3F),
    secondaryContainer = Color(0xFF154F70), onSecondaryContainer = Color(0xFFC7E6FB),
    tertiary = Color(0xFF40D6A0), onTertiary = Color(0xFF04322A), // CYP-304: signal-green night accent (brand only)
    tertiaryContainer = Color(0xFF0C3D30), onTertiaryContainer = Color(0xFF9EEAD0),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF06121A), onBackground = Color(0xFFDCE7ED),
    surface = Color(0xFF06121A), onSurface = Color(0xFFDCE7ED),
    surfaceVariant = Color(0xFF12242F), onSurfaceVariant = Color(0xFFA6BECD),
    outline = Color(0xFF57707F), outlineVariant = Color(0xFF243642),
)

/** The maritime scheme for the current mode — the one decision the `App.kt` seam makes (follow-system in R1). */
fun maritimeColorScheme(dark: Boolean): ColorScheme = if (dark) MaritimeDark else MaritimeLight
