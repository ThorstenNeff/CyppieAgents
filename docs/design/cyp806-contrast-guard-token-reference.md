# CYP-806 — Contrast-Guard: Token-Werte + Ziel-Ratios (token-level assertbar)

> DS-Owner-Input für Dev5s CYP-806-Contrast-Guard. **Ziel:** die Glyph-auf-Pill- und Text-auf-Container-Paare
> (N3-Trust-Badge CYP-803 + IssuerNotTrusted CYP-805) **token-level** asserten (AA-Deckung) statt guided-human-Pixel.
> **Ergebnis: ALLE Paare sind token-level bestimmbar (solides Hex, keine Alpha/Gradient) und bestehen in BEIDEN Themes
> → KEIN Paar braucht guided-live.**

## 0. Reuse — kein neuer Util
Der WCAG-Rechner **existiert schon**: `src/ui/maritimeTokens.ts` exportiert `contrastRatio(a, b)` (1..21),
`relativeLuminance(hex)`, `MARITIME_TOKENS[theme]` und `WCAG_NON_TEXT_MIN = 3`. Der Guard = pro Theme × Paar ein
`expect(contrastRatio(fg, bg)).toBeGreaterThanOrEqual(target)`. Token-Quelle = `src/ui/maritimeTokens.data.mjs` (single
source; die CSS ist daraus generiert). **Beide Themes iterieren** — ein Token-Flip kann genau ein Theme brechen.

## 1. Token-Werte (aus `maritimeTokens.data.mjs`, beide Themes)
| Rolle | light | dark |
|---|---|---|
| `surfaceVariant` (N3-Pill-BG) | `#E4EFF8` | `#12242F` |
| `onSurface` (trusted-Glyph + Label-Text) | `#0C2635` | `#DCE7ED` |
| `onSurfaceVariant` (unknown/pending-Glyph) | `#3A4E5A` | `#A6BECD` |
| `error` (rejected-Glyph) | `#B3261E` | `#F2B8B5` |
| `tertiary` (stale-Glyph) | `#0B7E9C` | `#40D6A0` |
| `warnContainer` (Issuer-Block-BG) | `#FFE7B0` | `#4A3A10` |
| `onWarnContainer` (Issuer ▲-Glyph + Text) | `#5A3D00` | `#FFC857` |

## 2. Ziel-Ratio je ROLLE (nicht je Token) — [[token-contrast-is-role-dependent]]
- **Glyph = Graphik** (das ●◯◔⊘◑ / ▲, `aria-hidden`, Form-Träger): **≥ 3:1** (WCAG 1.4.11 non-text).
- **Text** (das N3-Label-Wort; die Issuer Titel/Detail/OOB-Zeilen): **≥ 4.5:1** (WCAG 1.4.3 AA).
- Dasselbe Token kann je Rolle ein anderes Ziel haben — **am ELEMENT prüfen**, nicht am Token global.

## 3. Berechnete Ratios (WCAG, exakt aus Hex — measured, nicht geschätzt)
### N3-Trust-Badge — Glyph auf Pill-BG `surfaceVariant` (Rolle Graphik, Ziel ≥3:1)
| Zustand / Glyph-Token | light | dark |
|---|---|---|
| trusted `onSurface` | **13.39:1** ✓ | **12.66:1** ✓ |
| unknown/pending `onSurfaceVariant` | **7.45:1** ✓ | **8.24:1** ✓ |
| rejected `error` | **5.60:1** ✓ | **9.32:1** ✓ |
| stale `tertiary` | **4.02:1** ✓ | **8.60:1** ✓ |

### N3-Trust-Badge — Label-Text `onSurface` auf `surfaceVariant` (Rolle Text, Ziel ≥4.5:1)
| | light | dark |
|---|---|---|
| Label-Wort | **13.39:1** ✓ | **12.66:1** ✓ |

### IssuerNotTrusted — `onWarnContainer` auf `warnContainer`
Dasselbe Token trägt **Glyph UND Text** → es muss den **strengeren** Text-Wert ≥4.5:1 klären (nicht nur 3:1):
| Rolle | Ziel | light | dark |
|---|---|---|---|
| ▲-Glyph | ≥3:1 | **8.23:1** ✓ | **7.17:1** ✓ |
| Titel/Detail/OOB-Text | ≥4.5:1 | **8.23:1** ✓ | **7.17:1** ✓ |

## 4. Auflagen für den Guard (load-bearing)
1. **Glyph-Tokens auf ≥3:1 asserten, Text-Tokens auf ≥4.5:1 — NICHT vertauschen.** Insb. **`tertiary` (stale) und
   `error` (rejected) sind GLYPH-only** → ≥3:1. `tertiary`-light ist **4.02:1**: besteht ≥3:1 (Graphik), **würde aber
   4.5:1 REISSEN** — also **nie** als Text-Token asserten/verwenden (das ist der tightest-margin-Wächter; ein künftiger
   Token-Shift, der `tertiary`-light unter 3.0 drückt, oder eine Fehlnutzung als Text, muss RED geben).
2. **`onWarnContainer` MUSS ≥4.5:1** (es trägt Text) — nicht nur ≥3:1 asserten, sonst schlüpft ein text-untauglicher
   Shift durch.
3. **`onSurface`-Label ≥4.5:1** (Text).
4. **Beide Themes** (`light` + `dark`) — je Paar; ein Theme-Flip darf nicht unbemerkt eines brechen.
5. **Alle Paare token-level bestimmbar → guided-live NUR für das, was NICHT token-level ist:** hier **nichts** an diesen
   Paaren. Guided-live bleibt für CYP-806s a11y-Teil (verschachtelte Live-Region / `role=alert`-on-mount-Ansage, §CYP-803
   L1) — das ist Screenreader-Verhalten, kein Kontrast.

## 5. Nicht-token-level (ehrlich benannt)
- **Kontrast:** kein Paar — alle solides Hex, kein Alpha/Gradient/Bild dahinter → **komplett token-level**.
- **AT-Ansage (nicht Kontrast):** die CYP-803-L1-a11y (nested live-region, alert-on-mount) bleibt **guided-human**
  (Screenreader), separat getrackt.
