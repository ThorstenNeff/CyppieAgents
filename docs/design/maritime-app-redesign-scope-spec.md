# Design-Scope — Maritimes App-Redesign (Compose `ColorScheme`-Umzug vom baren M3-Default)

> Owner: UIUX-Designer · Stand: 2026-07-06 · Status: **SCOPE-Entwurf — Design-first. Ratifikation durch PO + Auftraggeber-Sign-off aufs Scope VOR jedem Bau. KEIN Redesign-Bau ohne Freigabe.**
> **Motiv:** Die Compose-App läuft heute auf **barem `MaterialTheme {}`** (M3-Default-Violett). Die **maritime Design-Sprache** (blau/weiß, M3 — [[design-language-maritime-m3]]) wurde in CYP-234 erstmals als Doku-Theme kodifiziert (`dev-api-docs-experience-tokens.json`); dieser Scope überträgt sie auf die **App**.
> **Auftrag (PO):** wie die maritime Palette auf die Compose-`ColorScheme` mappt (Day/Night), **was sich ändert**, **Effort/Slices**. Kein Redesign-Bau in diesem Pass — nur Scope + Mapping + Plan.
> **Grounding (verifiziert @ develop `c7def58`):** `app/shared/.../App.kt` (Theme-Root), app-weite `colorScheme.*`-Nutzung, `ui/SenderPalette.kt`, `ui/TonedHint.kt`, `eventlog/EventVisuals.kt`, `core/.../model/ColorDerivation.kt`.

---

## §0 — Die zentrale Erkenntnis (macht das Redesign klein)

**Die App ist bereits „theme-ready".** Ein Audit der `commonMain`-UI zeigt: die Komponenten konsumieren **M3-`colorScheme`-Rollen**, **nicht** hartkodierte Farben — app-weit u. a. `onSurfaceVariant` (57×), `primary` (17×), `error` (14×), `onSurface` (13×), `tertiary`/`secondary` (je 8×), diverse Container/Outline-Rollen. **Alle** hängen an **einem** Seam: `MaterialTheme {}` in `App.kt` (Z. 39), heute **ohne** `colorScheme`-Argument → M3-Default.

**Konsequenz:** Ein maritimes `ColorScheme` (light + dark) an **dieser einen Stelle** injiziert **rekoloriert praktisch die ganze App** — ohne die Komponenten anzufassen. Das Redesign ist damit **primär eine Palette-Definition + ein Theme-Seam + ein Kontrast-Audit**, nicht ein Umschreiben von N Screens.

**Der Rest des Scopes = die wenigen Farben, die NICHT an `colorScheme` hängen** (§3 Audit-Surface) + Day/Night-Verdrahtung + WCAG-Re-Verifikation.

---

## §1 — Was sich ändert (und was nicht)

| Bereich | Änderung | Aufwand |
|---|---|---|
| **`App.kt` Theme-Root** | `MaterialTheme {}` → `MaterialTheme(colorScheme = if (dark) darkMaritime else lightMaritime) {}` + `isSystemInDarkTheme()`-Wahl (+ optionaler In-App-Toggle, §5-Ask) | **klein** (ein Seam) |
| **Maritime `ColorScheme` (light + dark)** | NEU: `MaritimeTheme.kt` mit zwei `ColorScheme`s, alle genutzten Rollen belegt (aus `-tokens.json`, §2) | **mittel** (Palette-Definition + Kontrast-Tuning) |
| **Rollen-konsumierende Komponenten** (TonedHint, ProjectSwitcherBar, WindowBadge, AuthGate, Panels …) | **KEINE Code-Änderung** — sie folgen dem neuen Scheme automatisch | **~0** (nur visuelle Re-Verifikation) |
| **Hartkodierte/abgeleitete Farben** (Audit-Surface §3) | Pro Fall: behalten-mit-Kontrast-Check **oder** maritim-angleichen | **klein–mittel** (wenige Stellen) |
| **Day/Night** | NEU als Konzept (App hat heute nur Light-Default) — beide Schemata + System-Folge | **mittel** (Dark erstmals durchgespielt) |
| **Copy / Layout / Flows / testTags / Strings** | **KEINE Änderung** — reines Re-Coloring, kein Struktur-/Text-Redesign | **0** |

> **Abgrenzung:** Dieses Redesign ist **Farbe/Theme**, **nicht** Layout/IA/Copy. Keine `strings.xml`-, keine `testTag`-, keine Flow-Änderung. Das hält es überschaubar und QA-bar.

---

## §2 — Palette-Mapping: maritime Tokens → M3 `ColorScheme`-Rollen

Basis-Hues aus `dev-api-docs-experience-tokens.json` (CYP-234), erweitert auf die **volle** M3-Rollen-Menge (die App nutzt tertiary/error/Container-Rollen, die das Doku-Theme nicht brauchte). Konkrete Werte (light + dark) in `maritime-app-redesign-scope-tokens.json`. Mapping-Logik:

| M3-Rolle | Maritime-Quelle | Anmerkung |
|---|---|---|
| `primary` / `onPrimary` | deep ocean blue `#14567A` / weiß | Haupt-Akzent (Buttons, aktive Marker) |
| `primaryContainer` / `onPrimaryContainer` | sky `#CDE7F5` / `#062033` | |
| `secondary` / `onSecondary` | mid-sea `#3E7CA6` / weiß | **TonedHint INFO** hängt hier |
| `tertiary` / `tertiaryContainer` / `on…` | shoal `#2E8FA6` / helles Foam | **TonedHint EFFECT_DEFERRED + Chip-bg** hängt hier |
| `error` / `onError` / `errorContainer` / `on…` | **semantisches Rot behalten** (M3-Rot, leicht maritim-harmonisiert) | Fehler bleibt als Fehler erkennbar — **keine** maritime Umfärbung der Fehler-Semantik |
| `surface` / `background` / `onSurface` | weiß/foam `#FFFFFF`/`#EEF4F8` / navy-ink `#0F2A38` | Dark: deep navy `#0D1B23` / `#DCE7ED` |
| `surfaceVariant` / `onSurfaceVariant` | foam `#EEF4F8` / `#41535C` | **57× genutzt** — der häufigste Träger, Kontrast kritisch |
| `outline` / `outlineVariant` | `#B4C6D0` | Ränder/Divider |

> **Ehrlichkeits-/Semantik-Anker:** **`error` bleibt rot** (Fehler-Bedeutung ist nicht verhandelbar fürs Branding); maritime Färbung betrifft **Marke/Neutrale**, nicht die **semantischen** Rollen. Gilt auch für die Severity-/Status-Farben in §3.

---

## §3 — Audit-Surface: Farben, die NICHT an `colorScheme` hängen

Diese folgen dem Theme-Seam **nicht** automatisch → je eine bewusste Entscheidung:

1. **`EventVisuals.kt` — Severity-Farben** (ERROR `#FF6B6B`, WARN `#FFC857`, INFO `#A0A4AD`, DEBUG `#5A5E66`): **semantisch** (Schweregrad = Bedeutung, nicht Marke). **Empfehlung: behalten**, aber **Kontrast auf maritimer Surface (bes. Dark) re-verifizieren** + ggf. leicht nachjustieren. Severity nie in „maritim-blau" auflösen (verlöre die Ampel-Semantik).
2. **`SenderPalette.kt` — 9 Agenten-Farb-Slots** (Sender-/Agenten-Identität): **behalten** (Identitäts-Palette, WCAG-getunt, CYP-14). **Re-verifizieren:** Slots auf maritimer Light- **und** Dark-Surface (Kontrast + genug Unterscheidbarkeit unter dem neuen Neutral-Hintergrund).
3. **`ColorDerivation.kt` (:core) — Agenten-Custom-Farb-Derivation** (base→{bg,onColor,border}, WCAG ≥4.5:1/≥3:1, CYP-208/209): **compose-frei, scheme-unabhängig** → keine Code-Änderung, aber **Kontrast-Annahmen gegen die maritimen Surfaces re-prüfen** (die Derivation garantiert Kontrast gegen ihre eigene bg; ihre Einbettung in maritime Panels muss stimmen).
4. **`TonedHint.kt`**: nutzt **nur `colorScheme`-Rollen** (tertiaryContainer/onTertiaryContainer/error/secondary/onSurfaceVariant) → **folgt automatisch**; nur visuelle Re-Verifikation der 4 Töne auf beiden Schemata.

> **Kein `Color(0x…)` sonst** in der `commonMain`-UI außer den obigen (verifiziert). Die Audit-Surface ist **klein und benannt**.

---

## §4 — Effort / Slices (Vorschlag, nach Ratifikation)

| Slice | Inhalt | Größe |
|---|---|---|
| **R1 — Maritime `ColorScheme` + Theme-Seam** | `MaritimeTheme.kt` (light + dark `ColorScheme` aus §2/Tokens); `App.kt`-Seam + `isSystemInDarkTheme()`; **kein** Komponenten-Touch | **S–M** |
| **R2 — Kontrast-Audit + Fixes** | WCAG-Durchlauf aller Rollen-Paare (bes. `onSurfaceVariant`-57×) light+dark; Audit-Surface §3 (Severity/SenderPalette/ColorDerivation) verifizieren + nötige Nachjustierung | **M** |
| **R3 — Day/Night-Verdrahtung + optionaler Toggle** | System-Folge default; optionaler In-App-Theme-Toggle (§5-Ask); Persistenz falls Toggle | **S** (ohne Toggle) / **M** (mit) |
| **R4 — Visuelle Abnahme + UX-QA** | Screen-für-Screen visuelle Re-Verifikation light+dark; meine UX-QA gegen §6 | **M** |

> **Reihenfolge:** R1 → R2 → (R3 ∥ R4-Vorlauf). **Größter Aufwand = R2** (Kontrast, nicht Struktur). **Risiko niedrig**, weil kein Layout/Flow-Umbau.

---

## §5 — Offene Punkte / §-Asks (für PO + Auftraggeber-Scope-Sign-off)

1. **Scope-Freigabe (Auftraggeber):** Ist ein maritimes Re-Coloring der App jetzt gewollt (vs. „interim M3-Default lassen")? **Kein Bau ohne dieses Sign-off.**
2. **Dark-Mode:** beide Schemata (light+dark, empfohlen — modern, augenschonend) **oder** nur Light-maritim für v1? Beeinflusst R1/R2/R3-Aufwand.
3. **Theme-Toggle:** System-Folge only (schlank) **oder** zusätzlicher In-App-Umschalter (Komfort, +R3-Aufwand)?
4. **Umfang der Marken-Sättigung:** dezent-maritim (nur primary/secondary/surface maritim, Rest neutral — empfohlen, ruhig/Anti-Hype) **vs.** voll-durchgefärbt (mehr Charakter, mehr Kontrast-Arbeit).
5. **Severity-Farben (§3-1):** semantisch behalten (empfohlen) vs. maritim-angleichen (Risiko Ampel-Semantik-Verlust).
6. **Ticket-Key:** dieser Scope ist design-ahead unter [[design-language-maritime-m3]]; **PO vergibt den Jira-Key** (eigenes Redesign-Ticket/Epic) bei Ratifikation. *(Branch heißt bis dahin `feature/maritime-app-redesign-scope` — bei Key-Vergabe umbenennbar.)*

---

## §6 — Invarianten (= meine spätere UX-QA-Abnahme des Baus, 8)

1. **Ein Theme-Seam:** die Rekolorierung kommt aus `App.kt` + `MaritimeTheme.kt`; **keine** Komponente hardcodet eine Marken-Farbe neu.
2. **Rollen-Treue:** jede Komponente nutzt weiter M3-`colorScheme`-Rollen (keine `Color(0x…)`-Regressionen außer der benannten Audit-Surface).
3. **WCAG light + dark:** jedes Text-auf-Hintergrund-Paar ≥ AA (Body 4.5:1 / UI 3:1) in **beiden** Schemata — bes. `onSurfaceVariant` (57×).
4. **Semantik erhalten:** `error` bleibt rot; Severity-Ampel (§3-1) bleibt lesbar als Schweregrad; INFO/GATED/EFFECT_DEFERRED-Töne bleiben unterscheidbar.
5. **Agenten-Identität erhalten:** SenderPalette + ColorDerivation bleiben unterscheidbar + kontrastreich auf maritimer Surface (Agenten-Farbe ≠ Marken-Farbe verwechselbar).
6. **Day/Night sauber:** System-Folge korrekt; kein „halber" Zustand (Light-Widget auf Dark-Surface).
7. **Kein Struktur-/Copy-Drift:** 0 `strings.xml`-, 0 `testTag`-, 0 Layout-/Flow-Änderung — reines Re-Coloring.
8. **Ehrlichkeit unberührt:** die Disclosure-Anker bestehender Features (Status-/Tier-/Restart-/Session-Indikatoren) bleiben in Bedeutung & Kontrast intakt; Farbe nie alleiniger Träger (WCAG 1.4.1).

---

## §7 — Hand-off

- **Deliverables (dieser Pass):** `-spec.md` (Scope + Mapping + Slices) + `-tokens.json` (volles maritimes `ColorScheme` light + dark, alle genutzten Rollen). **Kein** Bau.
- **Grounding:** App-Theme-Audit @ develop `c7def58`; maritime Basis-Hues aus CYP-234 `dev-api-docs-experience-tokens.json`; [[design-language-maritime-m3]].
- **Danach:** **PO ratifiziert + Auftraggeber-Sign-off aufs Scope** (§5-1) → dann Bau (R1–R4) → **UX-QA durch mich gegen §6 (8 Invarianten)**.
- **Anti-Divergenz:** die maritime App-Palette und das CYP-234-Doku-Theme teilen dieselben Basis-Hues (eine Sprache, zwei Surfaces) — Doku und App bleiben visuell konsistent.
