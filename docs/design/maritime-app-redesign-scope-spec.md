# Design-Scope — Maritimes App-Redesign (CYP-268) (Compose `ColorScheme`-Umzug vom baren M3-Default)

> Owner: UIUX-Designer · Ticket **CYP-268** · Stand: 2026-07-06 · Status: **v1.1 — AUFTRAGGEBER-GO + Design-Ansatz PO-ratifiziert. Finalisiertes ColorScheme (kräftig, Day+Night) → wartet PO-GO für Dev-Bau R1.**
> **⚠ Auftraggeber-Entscheid (2026-07-06):** voll **Day + Night**; **KRÄFTIGERE Sättigung** (Override meiner dezent-Empfehlung — präsentere Marken-Signatur → sattere Blaus/Akzente in `-tokens.json` v1.1, Kontrast hält via R2); Severity/SenderPalette **semantisch behalten**; Theme-Toggle = **follow-system**. Design-Ansatz (R1–R4) PO-ratifiziert. **Bau erst auf PO-GO.**
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

Basis-Hues aus `dev-api-docs-experience-tokens.json` (CYP-234), **kräftiger gesättigt** (Auftraggeber-Override) und erweitert auf die **volle** M3-Rollen-Menge. **Konkrete, maßgebliche Werte (light + dark, kräftig, 24 Rollen) = `maritime-app-redesign-scope-tokens.json` v1.1** (Source of Truth; unten nur die Kern-Anker). Mapping-Logik:

| M3-Rolle | Maritime-Quelle (kräftig, v1.1) | Anmerkung |
|---|---|---|
| `primary` / `onPrimary` | vivid ocean blue `#0A5AA0` / weiß | Haupt-Akzent (Buttons, aktive Marker) |
| `secondary` / `onSecondary` | saturated mid-sea `#0F5B88` / weiß | **TonedHint INFO** hängt hier — für AA auf `tertiaryContainer` getunt (R2-Audit, s. u.) |
| `tertiary` / `tertiaryContainer` / `on…` | vivid shoal-teal `#0B7E9C` / helles Foam `#B7E7F2` | **TonedHint EFFECT_DEFERRED + Chip-bg** hängt hier |
| `error` / `onError` / `errorContainer` / `on…` | **semantisches Rot behalten** (`#B3261E`, unverändert) | Fehler bleibt als Fehler erkennbar — **keine** maritime Umfärbung der Fehler-Semantik |
| `surface` / `background` / `onSurface` | weiß / navy-ink `#0C2635` | Dark: deep navy `#0A1922` / `#DCE7ED` |
| `surfaceVariant` / `onSurfaceVariant` | leicht-blau Foam `#E4EFF8` / `#3A4E5A` | **57× genutzt** — häufigster Träger, Kontrast kritisch (R2) |
| `outline` / `outlineVariant` | `#6E8C9E` / `#CBDCE7` | Ränder/Divider |

> **Kräftig, aber lesbar — R2-Palette-Kontrast-Audit DURCH (WCAG AA, light + dark):** die Sättigung steigt bei **primary/secondary/tertiary** (Marken-Signatur); **Surfaces bleiben überwiegend hell** (Anti-Hype). **Audit-Ergebnis:** alle Text-Paare ≥ AA (worst **4.69:1 light / 4.87:1 dark**), UI-Fill ≥ 3:1. **Eine** Fail-Paarung gefunden — TonedHint-INFO (`secondary`-auf-`tertiaryContainer`, 4.48 light / 4.26 dark) — durch **Ton-Anpassung von `secondary`** gefixt (light `#12689F`→`#0F5B88`, dark `#7FC0E4`→`#93CCEA`; **AA gewinnt, Sättigung/Charakter erhalten**, keine Regression der anderen `secondary`-Paare). *AA-hart = PO-Gate erfüllt auf Palette-Ebene; die App-gerenderte Verifikation (bes. `onSurfaceVariant` 57×) folgt in R4.*
> **Ehrlichkeits-/Semantik-Anker:** **`error` bleibt rot** (Fehler-Bedeutung nicht verhandelbar); maritime Färbung betrifft **Marke/Neutrale**, nicht die **semantischen** Rollen. Gilt auch für Severity-/Status-Farben in §3.

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
| **R2 — Kontrast-Audit + Fixes** | **Palette-Ebene: DURCH** (WCAG AA light+dark verifiziert, `secondary` für INFO-Paarung getunt — s. §2). **Rest in R4:** die App-**gerenderte** Verifikation (bes. `onSurfaceVariant`-57× in echten Komponenten) + Audit-Surface §3 (Severity/SenderPalette/ColorDerivation auf maritimer Surface) | **S** (Rest, da Palette schon AA-clean) |
| **R3 — Day/Night-Verdrahtung + optionaler Toggle** | System-Folge default; optionaler In-App-Theme-Toggle (§5-Ask); Persistenz falls Toggle | **S** (ohne Toggle) / **M** (mit) |
| **R4 — Visuelle Abnahme + UX-QA** | Screen-für-Screen visuelle Re-Verifikation light+dark; meine UX-QA gegen §6 | **M** |

> **Reihenfolge:** R1 → R2 → (R3 ∥ R4-Vorlauf). **Größter Aufwand = R2** (Kontrast, nicht Struktur). **Risiko niedrig**, weil kein Layout/Flow-Umbau.

---

## §5 — §-Asks — ALLE ENTSCHIEDEN (2026-07-06, Auftraggeber + PO)

1. ✅ **Scope-Freigabe (Auftraggeber): GO** — maritimes Re-Coloring jetzt, parallel zu CYP-234.
2. ✅ **Dark-Mode: JA, voll Day + Night** (beide Schemata, 24 Rollen je).
3. ✅ **Theme-Toggle: follow-system** (PO-Entscheid; kein In-App-Umschalter für v1).
4. ✅ **Marken-Sättigung: KRÄFTIG** (Auftraggeber-Override meiner dezent-Empfehlung → präsentere Marken-Signatur; sattere Blaus/Akzente, `-tokens.json` v1.1; Kontrast hält via R2).
5. ✅ **Severity-Farben: semantisch behalten** (PO-bestätigt; Ampel-Semantik bleibt).
6. ✅ **Ticket-Key: CYP-268** (Branch `feature/CYP-268-maritime-redesign`).

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

- **Deliverables:** `-spec.md` (Scope + Mapping + Slices) + `-tokens.json` v1.1 (volles maritimes `ColorScheme` **kräftig**, light + dark, 24 Rollen). Branch `feature/CYP-268-maritime-redesign`.
- **Grounding:** App-Theme-Audit @ develop `c7def58`; maritime Basis-Hues aus CYP-234 `dev-api-docs-experience-tokens.json` (kräftiger gesättigt); [[design-language-maritime-m3]].
- **Status/Danach:** Auftraggeber-GO + Design-Ansatz PO-ratifiziert. **Auf PO-GO** baut Dev **R1** (`App.kt`-Seam-Injektion) → R2 (Kontrast-Audit, tunt die grenzwertigen kräftig-Paare) → R3 (Day/Night follow-system) → **R4 UX-QA durch mich gegen §6 (8 Invarianten)**.
- **Anti-Divergenz:** die maritime App-Palette und das CYP-234-Doku-Theme teilen dieselben Basis-Hues (eine Sprache, zwei Surfaces) — Doku und App bleiben visuell konsistent.
