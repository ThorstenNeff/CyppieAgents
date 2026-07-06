# Design-Forward — Per-Scheme-adaptive Severity-Rail-Palette

> **Status:** FORWARD-VORSCHLAG (nicht-ticketed) · Owner: UIUX-Designer · optionaler Design-Happen, PO-getriggert 2026-07-06.
> **Herkunft:** in **CYP-268 R2** (gerenderte Kontrast-Verifikation) als [Info]/Forward geflaggt — **kein CYP-268-Regress**, aber eine echte Verbesserung. Wartet **PO-Entscheid**, ob eigenes Ticket.
> **Grounding:** `app/shared/.../eventlog/EventVisuals.kt` (`Severity.railColor()` Z.34–40, aktuell **statische Dark-Palette**), `[[design-language-maritime-m3]]`, CYP-268-Tokens v1.2 (`e930802`).
> **Kein Bau** — Design-Vorschlag + Farbwerte + Naht.

---

## §1 — Problem (aus CYP-268 R2)

`Severity.railColor()` liefert **eine** statische Palette, ursprünglich für die **dunkle** CYP-12-State-Palette entworfen:

| Severity | heute (statisch) | auf DARK `#0A1922` | auf LIGHT `#FFFFFF` |
|---|---|---|---|
| ERROR | `#FF6B6B` | ~5.5:1 ✓ | **2.8:1** ✗ (<3:1 UI) |
| WARN | `#FFC857` | ~9:1 ✓ | **1.5:1** ✗ |
| INFO | `#A0A4AD` | ~6:1 ✓ | **2.5:1** ✗ |
| DEBUG | `#5A5E66` | 2.7:1 (dim, gewollt) | ~7:1 (zu prominent) |

**Kein AA-Fail** (Severity = Rail **+ Glyph ⚠/▲/ⓘ/· + Text-Label**, nie Farbe allein — WCAG 1.4.1 erfüllt). **Aber:** auf der neuen **hellen maritimen Surface** waschen die hellen Rails aus → der Rail als **Non-Text-UI-Indikator** trägt den Alarm nicht mehr sichtbar (WCAG 1.4.11 ≥ 3:1 wäre wünschenswert), und DEBUG kehrt die Salienz um (dunkel = prominent statt dim).

---

## §2 — Vorschlag: Rail wird scheme-adaptiv (E/W/I ≥ 3:1 je Surface, DEBUG bewusst dim)

Zwei Rail-Paletten statt einer; die Semantik (ERROR=rot, WARN=amber, INFO=neutral, DEBUG=leise) bleibt in **beiden** erhalten. **Dark-Palette = unverändert** (funktioniert auf Dunkel). **Light-Palette = neu**, dunkler/satter, damit ERROR/WARN/INFO ≥ 3:1 auf Weiß erreichen.

| Severity | DARK-Rail (unverändert) | LIGHT-Rail (NEU) | LIGHT-Kontrast auf `#FFFFFF` | Semantik |
|---|---|---|---|---|
| ERROR | `#FF6B6B` | **`#B3261E`** | **6.53:1** ✓ | rot (= `colorScheme.error`, App-konsistent) |
| WARN | `#FFC857` | **`#9A6400`** | **5.00:1** ✓ | dunkel-amber (unterscheidbar von ERROR-rot) |
| INFO | `#A0A4AD` | **`#567083`** | **5.19:1** ✓ | maritim-blaugrau (neutral, nie Marken-primary) |
| DEBUG | `#5A5E66` (dim) | **`#B8BCC4`** (dim) | ~1.98:1 (bewusst) | leise/hell-grau (Salienz konsistent = die stille Severity) |

**Warum DEBUG bewusst < 3:1 bleibt (Ehrlichkeit):** DEBUG ist die **absichtlich niedrig-saliente** Severity — ein ≥3:1-Rail würde sie visuell aufwerten, gegen ihre Bedeutung. Bedeutung wird über **Glyph `·` + Text `debug`** getragen (WCAG 1.4.1 erfüllt); der DEBUG-Rail ist reine dezente Verstärkung. In **beiden** Schemata bleibt DEBUG damit die leiseste Spur — symmetrische Intent, kein Bruch. Für ERROR/WARN/INFO (die ein Problem signalisieren) trägt der Rail den Alarm **auch als Non-Text** ≥ 3:1.

**Unterscheidbarkeit auf Light** (Alarm-Fehlfarbenblindheits-Robustheit): rot / amber-braun / blaugrau / hellgrau — vier klar getrennte Helligkeits- **und** Farbtonstufen; keine mit dem maritimen `primary` `#0A5AA0` verwechselbar (INFO ist entsättigtes Grau-Blau, nicht das satte Marken-Blau).

---

## §3 — Naht (Design-Empfehlung, nicht Bau)

`railColor()` wird von statisch auf **scheme-aware** gehoben — minimal, `commonMain`-rein, testbar:

```kotlin
// heute:   fun Severity.railColor(): Color = when (this) { ... }   // eine (dunkle) Palette
// forward: fun Severity.railColor(dark: Boolean): Color = when (this) {
//            Severity.ERROR -> if (dark) Color(0xFFFF6B6B) else Color(0xFFB3261E)
//            Severity.WARN  -> if (dark) Color(0xFFFFC857) else Color(0xFF9A6400)
//            Severity.INFO  -> if (dark) Color(0xFFA0A4AD) else Color(0xFF567083)
//            Severity.DEBUG -> if (dark) Color(0xFF5A5E66) else Color(0xFFB8BCC4)
//          }
```

- **`dark`-Flag** kommt vom Call-Site (Event-Log-Row), das ohnehin in Compose läuft → `isSystemInDarkTheme()` **oder** die maritime Theme-Naht (`App.kt`, CYP-268). Kein `MaterialTheme`-Zugriff **in** der Funktion (bleibt pur/unit-testbar).
- **Alternativ** (falls bevorzugt): Rails als semantische `colorScheme`-nahe Tokens im `:core`-`ColorDerivation`-Stil — aber die 4 Severity-Hues sind **bewusst nicht** `colorScheme`-Rollen (Bedeutung ≠ Marke, CYP-268-Audit-Surface). Die `dark`-Boolean-Variante hält diese Trennung sauber.
- **`glyph()` / `qualifier()` / Text-Label bleiben unverändert** — die Non-Farbträger sind schon da.

---

## §4 — Invarianten (= UX-QA-Abnahme, falls getickt, 5)

1. **Semantik erhalten** — ERROR rot / WARN amber / INFO neutral / DEBUG leise, in **beiden** Schemata.
2. **E/W/I ≥ 3:1** — ERROR/WARN/INFO-Rail ≥ 3:1 auf seiner Surface (light UND dark); am realen Row-Hintergrund re-verifizieren (Surface vs. surfaceVariant/Container).
3. **DEBUG bewusst dim** — < 3:1 erlaubt & gewollt; Bedeutung via Glyph + Text (WCAG 1.4.1); die leiseste Spur in beiden Schemata.
4. **Farbe nie alleiniger Träger** — Glyph + Text-Label unverändert vorhanden (WCAG 1.4.1).
5. **Nie Marken-Verwechslung** — kein Severity-Rail confusable mit maritimem `primary`; INFO bleibt entsättigt.

---

## §5 — Hand-off

- **Kein Bau, nicht-ticketed.** **PO-Entscheid:** eigenes Ticket (kleiner Dev-Happen: `railColor(dark)`-Signatur + 4 Light-Hues + Call-Site-Flag + Test) — oder als CYP-268-R3/R4-Politur mitnehmen.
- **Reihenfolge:** unabhängig von R3/R4; kann jederzeit danach. Kein Blocker für den maritimen Redesign (CYP-268 ist AA-clean auch ohne diesen Forward — er ist Verbesserung, kein Fix).
- **Abnahme:** §4 (5 Invarianten), sobald gebaut; gerenderte Kontrast-Verifikation am echten Row-Hintergrund (wie CYP-268 R2/R4).
