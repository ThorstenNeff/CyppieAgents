# CYP-542 U1–U4 Render-Oracle — AUTORITATIVE Render-Werte (Tester assertet DIESE, erfindet keine)

> Owner: UIUX-Designer · Story CYP-542 (Phase A/B1) · Stand 2026-07-14 · **Dev+Tester-kritischer Pfad** (PO `1526562815…`).
> **Die eine autoritative Quelle** für die exakten Render-Werte je State — Dev rendert sie, **Tester assertet sie 1:1**
> (erfindet keine). Werte gegroundet READ-ONLY gg. `MaritimeTheme.kt` (CYP-268/CYP-304) + `EventVisuals.kt` @ `eb705236`.
> Begleit: `remote-uv-flow-visual-spec.md` (Design-Rationale), `-enroll-strings-manifest.md` (Copy), `-tokens.json`.
>
> **Glyph-Grammatik (verbindlich):** `▲` = WARN/advisory · `●` = neutral-affirmativ · **BLOCKLISTED trägt KEIN Glyph**
> (error-Ton + selbstbeschreibende Copy = die zwei Nicht-Farbe-Träger, WCAG 1.4.1) · rein-informative Zeilen (Coverage,
> Clipboard) tragen **kein** Glyph (neutraler Text). **Nie** `⚠`/`errorContainer` im Enroll (nur terminaler Hub-Reject).

## Farb-Tokens (Rolle → ARGB light / dark) — die Palette-Anker
| Rolle | light | dark | Verwendung |
|---|---|---|---|
| WARN-Amber (`severityColor(WARN)`) | `0xFF9A6400` | `0xFFFFC857` | TOO_WEAK, Session-only (advisory) |
| error (`colorScheme.error`) | `0xFFB3261E` | `0xFFF2B8B5` | BLOCKLISTED, mismatch, tooShort (retryable error-Ton) |
| primary | `0xFF0A5AA0` | `0xFF6FBEEA` | OK-Fill, `●`-Marker, Default-Button, Spinner |
| onSurfaceVariant | `0xFF3A4E5A` | `0xFFA6BECD` | neutrale Fakten (Coverage, Clipboard, Save-Hint, Level-Labels) |
| outline (**gedämpfter Fill**) | `0xFF6E8C9E` | `0xFF57707F` | BLOCKLISTED-Fill (NICHT primary/„voll") |
| surfaceVariant (Track) | `0xFFE4EFF8` | `0xFF12242F` | Meter-Track, Default-Karten-Fill |
| **VERBOTEN** errorContainer | `0xFFF9DEDC` | `0xFF8C1D18` | **NUR** terminal Hub-Reject — **nie** im Enroll |
| **VERBOTEN** tertiary (Grün) | `0xFF0B7E9C`* | `0xFF40D6A0` | Brand-only, **NIE Status** (kein Erfolgs-Grün) |

\* tertiary-light ist Teal, dark ist Signal-Grün; **beide nie als Status**. Der WARN-Amber kommt aus `EventVisuals`
(scheme-adaptiv), **nicht** aus dem ColorScheme.

## Strength-Meter — Level-Fill (proportional, `primary` auf `surfaceVariant`-Track)
| Level | Fill | Label-Key → DE / EN | Tag |
|---|---|---|---|
| WEAK | klein, `primary` | `remote_pop_strength_weak` → „Schwach" / „Weak" | `remote.authStep.enrollStrength` |
| FAIR | mittel, `primary` | `remote_pop_strength_fair` → „Mittel" / „Fair" | `remote.authStep.enrollStrength` |
| STRONG | voll, `primary` | `remote_pop_strength_strong` → „Stark" / „Strong" | `remote.authStep.enrollStrength` |

## Strength-Meter — 3 Verdict-States (die Ton-Trennung, Tester-Oracle)
| Verdict | Glyph | FG-Farbe (Verdict-Zeile) | Fill-Behandlung | Label-Key → DE / EN | Tag |
|---|---|---|---|---|---|
| **OK** | **`●`** | `primary` (`0xFF0A5AA0`/`0xFF6FBEEA`) — **nie Grün** | **voll, `primary`** | `remote_pop_strength_strong` → „Stark"/„Strong" | (Pass; kein `error`-Tag) |
| **TOO_WEAK** | **`▲`** | **WARN-Amber** (`0xFF9A6400`/`0xFFFFC857`) | proportional, `primary` | `remote_pop_enroll_too_weak` → „Passphrase zu schwach — 6 zufällige Wörter oder 12+ Zeichen." / „Passphrase too weak — 6 random words or 12+ characters." | `remote.authStep.error.tooWeak` |
| **BLOCKLISTED** | **(keins)** | **error** (`0xFFB3261E`/`0xFFF2B8B5`) | **★ gedämpft `outline` (`0xFF6E8C9E`/`0xFF57707F`) — NICHT voll/`primary`** | `remote_pop_enroll_blocklisted` → „Diese Passphrase ist zu verbreitet — sie steht auf einer Liste bekannt-schwacher Passphrasen. Bitte eine andere." / „This passphrase is too common — it's on a list of known-weak passphrases. Please choose another." | `remote.authStep.error.blocklisted` |

**★ BLOCKLISTED-Fill-Assertion (Kern-Ehrlichkeit):** Tester assertet, dass der Fill bei BLOCKLISTED **`outline`-gedämpft** ist
und **nicht** `primary`/„voll" — auch wenn die Passphrase strukturell lang/„stark" wäre. Die Optik darf nicht „stark" lügen.

## Weitere U1–U4 Render-Werte (je State)
| Element / State | Glyph | Farbe | Label-Key | Tag |
|---|---|---|---|---|
| Enroll `mismatch` | keins | error-Ton `0xFFB3261E`/`0xFFF2B8B5` | `remote_pop_enroll_mismatch` | `remote.authStep.error.mismatch` |
| Enroll `tooShort` (Kurz-PIN) | keins | error-Ton | `remote_pop_enroll_too_short` (%1$s) | `remote.authStep.error.tooShort` |
| Session-only-Downgrade (gebaut) | **`▲`** | WARN-Amber | `remote_pop_enroll_session_only` | `remote.authStep.enroll` |
| Diceware-Default-Button | keins | **gefüllt `primary`** (`onPrimary`-Text) | `remote_pop_enroll_suggest_use` | `remote.authStep.enrollSuggested` |
| Regenerate | keins | `primary` (Text-/Outlined-Button) | `remote_pop_enroll_suggest` | `remote.authStep.enrollSuggest` |
| Save-Hint | keins | neutral `onSurfaceVariant` | `remote_pop_enroll_suggested_save` | (im `enrollSuggested`) |
| Clipboard-Notice | **keins** | neutral `onSurfaceVariant` (**kein** WARN) | `remote_pop_enroll_clipboard_notice` | `remote.authStep.enrollClipboardNotice` |
| 1-UV-Coverage | **keins** | neutral `onSurfaceVariant` (**kein** Grün) | `remote_pop_uv_coverage` | `remote.authStep.uvCoverage` |
| Auto-Weiterlauf (U4) | — | `CircularProgressIndicator` `primary`; **kein** Erfolgs-Grün | `remote_connect_authenticating` (reuse) | `remote.connect.authenticating` |
| Biometrie-Angebot | keins | neutral | `remote_pop_biometric_offer` (%1$s) / `_fallback` | `remote.authStep.biometricOffer` |

## Assertions-Checkliste (für die Render-QA)
1. **OK** → `●` + `primary`-Fill voll + „Stark"/„Strong"; **kein** `▲`, **kein** Grün/`tertiary`.
2. **TOO_WEAK** → `▲` + WARN-Amber (`0xFF9A6400`/`0xFFFFC857`) + `_too_weak`-String; **nicht** error-rot.
3. **BLOCKLISTED** → error-Ton (`0xFFB3261E`/`0xFFF2B8B5`) + `_blocklisted`-String + **Fill `outline`-gedämpft** (nicht voll);
   **kein** `▲`/`●`, **kein** `errorContainer`.
4. **Ton-Leiter:** WARN-Amber (tooWeak/session-only) ≠ error-Ton (blocklisted/mismatch) ≠ `errorContainer` (nur terminal,
   **im Enroll absent**). Tester assertet `errorContainer` **nirgends** im Enroll.
5. **Farbe nie alleiniger Träger:** jeder State trägt Glyph **oder** selbstbeschreibende Copy zusätzlich zur Farbe (1.4.1).
6. **`tertiary`/Grün (`0xFF40D6A0`) nie als Status** — auch nicht bei OK/granted.
7. Werte gelten in **beiden Schemes** (light/dark-Spalten oben).

## Self-Validation
- Werte 1:1 aus `MaritimeTheme.kt` (24 Rollen) + `EventVisuals.kt` (WARN railColor) @ `eb705236` — **keine erfundene Farbe**.
- Konsistent mit `remote-uv-flow-visual-spec.md` (Rationale) + `-tokens.json` (Rollen) + `-enroll-strings-manifest.md` (Copy).
- **Bei Abweichung gilt DIESES File** für Render-Werte (Farbe/Glyph/Fill) + das Strings-Manifest für Copy. Kein Bau, docs-only.
