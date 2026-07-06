# Design-Check — CYP-275 scheme-aware Sender-/Agent-Accent (de-risk Devs Bau)

> **Status:** DESIGN-CHECK (kein Bau) · Owner: UIUX-Designer · PO-getriggert 2026-07-06 (R4 hält bis Readability-Batch 275/274/276 gemergt).
> **Frage (PO):** Liefert Devs CYP-275-Ansatz — scheme-aware Accent via `:core deriveScheme` — **AA-Sender-Accents auf BEIDEN Surfaces bei erhaltener Sender-Distinktheit?**
> **Grounding:** `ui/SenderPalette.kt` (`nameAccent` = statische CYP-14-Pastelle Z.41–52), `core/model/ColorDerivation.kt` (`deriveScheme`, develop `d02c224`), CYP-268-Tokens v1.2.

---

## §1 — Verdikt: Ansatz TRAGFÄHIG — mit EINER kritischen Steuer-Vorgabe

**JA, `deriveScheme` kann AA auf beiden Surfaces bei erhaltener Distinktheit — ABER nur, wenn der Light-Accent `deriveScheme(base).background` nutzt, NICHT `.border`.**

### Warum `.border` das FALSCHE Feld ist (kritisch)
`deriveScheme` hat **eine hartcodierte DUNKLE Referenz-Surface**: `NEUTRAL_SURFACE = 0xFF1E1E1E` (Z.36). Der `border`-Output wird auf **≥3:1 gegen Dunkel** getrimmt und dabei **aufgehellt** (Z.98, `lighten` wenn onColor=WHITE). → Auf der **hellen** maritimen Surface wird `border` **heller = KONTRAST-ÄRMER**, nicht besser. `border` ist zudem ein **≥3:1-Groß-/UI-Ziel**, ein Namens-Accent ist **TEXT → ≥4.5:1**. `.border` als Light-Accent verfehlt Text-AA doppelt (falsche Surface + falsches Ziel).

### Warum `.background` das RICHTIGE Feld ist
`deriveScheme(base).background` = der **hue-erhaltend abgedunkelte** Basis-Ton, so dass **WHITE ≥4.5:1 darauf** liegt (Z.89–93). Kontrast ist symmetrisch → derselbe Ton liegt **≥4.5:1 auf WEISS**. Also ist `.background` für jeden Sender mit `onColor=WHITE` (alle 9) **per Konstruktion AA-Text auf der hellen Surface** — und hue-erhaltend (`darken` skaliert alle Kanäle proportional → Identitäts-Farbton bleibt).

---

## §2 — Verifikation: alle 9 Sender-Fills sind BEREITS ≥4.5:1 auf Weiß

Da `nameAccent` heute die **Pastell-Variante** (für die dunkle Panel-Default) nutzt, wäscht sie auf Hell aus (CYP-275: 2.2:1). Der **Fill** selbst (`avatarFill`, `onAvatar=WHITE`) trägt aber schon Text-AA auf Weiß — `deriveScheme(fill).background ≈ fill` (Fills sind bereits dunkel genug):

| Slot | Fill (base) | Hue | Kontrast Fill/Weiß | AA-Text |
|---|---|---|---|---|
| PO | `#3B3F8F` | deep indigo | **9.16:1** | ✓ |
| 0 | `#1F7A6E` | teal | **5.16:1** | ✓ |
| 1 | `#6E4BD0` | violet | **5.87:1** | ✓ |
| 2 | `#B5419A` | magenta | **5.01:1** | ✓ |
| 3 | `#3E63C0` | indigo | **5.60:1** | ✓ |
| 4 | `#9A6B2F` | bronze | **4.65:1** | ✓ |
| 5 | `#C24D6A` | pink | **4.60:1** | ✓ |
| 6 | `#4C6A8A` | slateblue | **5.62:1** | ✓ |
| 7 | `#6E7A2E` | olive | **4.68:1** | ✓ |

**Alle ≥4.5:1** (Band 4.60–9.16). → Der Light-Accent = `deriveScheme(base).background` ist **AA-clean auf Weiß, ohne Sonderfälle**. (Am realen Row-Hintergrund re-verifizieren, falls Rows auf `surfaceVariant` `#E4EFF8` statt reinem Weiß sitzen — dunkler-auf-leicht-getönt hebt den Kontrast minimal, kein Risiko.)

---

## §3 — Distinktheit bleibt erhalten

`darken` ist **hue-erhaltend** (proportionale Kanal-Skalierung) → die 9 Töne bleiben auf Hell **farbton-getrennt** (teal/violet/magenta/indigo/bronze/pink/slate/olive/PO-indigo), genau wie die Fills heute im Avatar. **Keine Konvergenz** (die Fills sind bereits die distinkte Identitäts-Palette; wir wechseln nur, WELCHE Ableitung der Name-Text nutzt, nicht die Basis). Auf **Dunkel** bleibt der bestehende Pastell-Accent (funktioniert dort) → beide Schemata je hue-getrennt.

**Anti-Status-Regel erhalten:** die Fills meiden bewusst die CYP-12-Status-Hues (SenderPalette-Kommentar Z.24–26); die abgedunkelten Light-Accents erben das (Hue unverändert) — kein Sender liest als Status oder als Marken-`primary` `#0A5AA0` (die Fills sind eigene Identitäts-Hues).

---

## §4 — Empfohlene Naht (Design-Steuer für Dev)

```kotlin
// nameAccent wird scheme-aware:
//   DARK  → bestehender CYP-14-Pastell-Accent (unverändert, trägt auf dunkler Panel-Surface)
//   LIGHT → deriveScheme(baseFill).background   // hue-erhaltend, ≥4.5:1 auf Weiß per Konstruktion
// KEIN  .border  als Light-Accent (≥3:1-Ziel gegen HARTCODIERT-dunkle NEUTRAL_SURFACE, hellt auf → failt auf Weiß).
```

`dark`-Flag vom Call-Site (Row/Panel, läuft in Compose → `isSystemInDarkTheme()`/CYP-268-Theme-Naht). `deriveScheme` selbst **braucht für den Name-Accent KEINE Änderung** — `.background` genügt.

---

## §5 — Sekundär-Flag (separater Scope, NICHT der 2.2:1-Text-Fail)

`deriveScheme.NEUTRAL_SURFACE` ist **hartcodiert dunkel** (`#1E1E1E`). Jeder **Rand/Border**, der gegen die **helle** Surface ≥3:1 stehen muss (z. B. `SenderColor.borderColor` = Avatar-/Fenster-Kante auf Maritime-Hell), ist von `deriveScheme` heute **NICHT** abgedeckt (Border zielt nur auf Dunkel). Das ist die **SenderPalette-borderColor-auf-Hell**-Sorge, die ich in **CYP-268 R2** als Forward flaggte — **getrennt** vom CYP-275-Name-Accent-Fix. Falls CYP-275/276 auch die Avatar-**Kante** auf Hell nachzieht, braucht `deriveScheme` einen **Surface-Parameter** (statt der hartcodierten Konstante). Für den **gemeldeten Text-Fail** (Name-Accent 2.2:1) ist das **nicht** nötig — `.background` löst ihn.

---

## §6 — Hand-off

- **Kein Bau, kein CYP-268-Blocker.** De-riskt Devs CYP-275-Bau: Ansatz ✓, Steuer = `.background` (nicht `.border`), Distinktheit ✓, alle 9 verifiziert.
- **Feeds R4:** die Name-Accents sind Teil des holistischen R4-Passes — dort gerenderte Verifikation am echten Row-Hintergrund (light+dark), zusammen mit CYP-274-Rails/CYP-276.
- **Sekundär (§5):** falls Border-auf-Hell mit angefasst wird → `deriveScheme`-Surface-Parameter als eigener kleiner Schritt (die R2-borderColor-Forward-Sorge), nicht mit dem Text-Fix vermischen.
