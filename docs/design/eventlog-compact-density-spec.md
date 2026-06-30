# Event-Log Compact-Dichte — Browse + Live-Tail (CYP-158, Klasse B)

> Owner: UIUX · Stand 2026-06-30 · docs-only · Grounded @ develop `d57dccb`.
> Auslöser: Android-Tester, 411dp-Phone — Event-Log Browse **und** Live-Tail rendern tabellarisch/multi-column
> (Filter-Bar 6 Spalten horizontal; Zeilen 5–6+ Spalten; Korrelations-ID quetscht vertikal `r-u-n--1`).
> Scope: **nur Klasse B** (Horizontal-Dichte). Klasse A (Pane-Collapse) = CYP-156, Klasse C
> (ProjectSwitcher) = CYP-159. Gesamt-Audit: `uiux/COMPACT-WIDTH-AUDIT-DESIGN.md`.
> **Koppelt an CYP-154** (gemeinsame Dateien EventBrowsePanel/EventTailPanel/EventRowUi) → Merge-Sequenzierung
> durch den PO.

## 0. Wichtig: Pane-Collapse ≠ Single-Column
EventBrowse **kollabiert seinen Two-Pane korrekt** (`<560dp` → Single-Pane, `EventBrowsePanel.kt` Z. 72).
Der Defekt liegt **innerhalb** des Single-Pane: Filter-Bar und Event-Zeile sind horizontal multi-column und
überlaufen/quetschen. Das ist eine **andere** Maßnahme als der CYP-156-Breakpoint — der Breakpoint-Token
fixt Klasse B **nicht**.

## 1. Problem (code-verifiziert)
- **`eventlog/EventBrowsePanel.kt` `FilterBar`** Z. 185–208: `Row(horizontalArrangement spacedBy 10.dp)` aus
  **6 Chips** (Projekt/Agent/Typ/Severity/Zeitfenster/Korrelation) — **kein Wrap** → rechts abgeschnitten.
- **`eventlog/EventTailPanel.kt` `TailHeader`** Z. 115: `Row(spacedBy 8.dp)` = Pause-Button + Live/Paused-
  Indikator + **4 Chips** (Agent/Typ/Severity/Projekt) — **kein Wrap**.
- **`eventlog/EventRowUi.kt` `EventRow`** Z. 81–127: ein `Row(fillMaxWidth, spacedBy 8.dp)` aus **7–8 fixen
  Spalten** (Severity-Rail+Glyph, Zeitstempel, Typ-Glyph+typeText, Avatar, Name, Korrelations-Chip, opt.
  Projekt) — **keine Weights, kein Wrap** → der Korrelations-Chip (Z. 112) quetscht Char-für-Char vertikal.
  **Geteilt von Browse UND Tail** → ein Fix deckt beide Surfaces.

## 2. Soll

### 2.1 Chip-Leisten → `FlowRow` (PO-freigegeben)
- **`FilterBar`** (Browse): den 6-Chip-`Row` (Z. 186) auf
  `FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp))`
  umstellen. Chips umbrechen in 2–3 Reihen statt zu clippen. Der „subset"-Cue + Cross-Project-View-Indikator
  (Z. 210–226) bleiben darunter unverändert.
- **`TailHeader`** (Tail): **Pause-Button + Live/Paused-Indikator bleiben prominent** (erste Zeile); die
  Filter-Chips (Agent/Typ/Severity/Projekt) fließen in einer `FlowRow` darunter. Pause/Live-Disclosure (§7.2:
  „pausiert ≠ live", Z. 124–137) **unverändert** — nur Umbruch.
- `FlowRow` (`androidx.compose.foundation.layout.FlowRow`) ist Compose-Foundation-Standard (CMP 1.11.1),
  **heute in keinem Panel genutzt** → bewusst als geteiltes Dichte-Muster eingeführt (geringes Risiko,
  gleiche Kind-Knoten/Tags). Kein Breakpoint nötig — FlowRow umbricht automatisch (Desktop: eine Zeile;
  Phone: mehrere).

### 2.2 `EventRow` → deterministische ≤2-Zeilen-Gruppierung (PO: **NICHT** FlowRow)
Strukturierte Datenzeilen brauchen ein **stabiles** Layout (raggedes FlowRow-Wrappen auf 7–8 Spalten bricht
unvorhersehbar). Daher **deterministische** Primär/Sekundär-Gruppierung, an der Zeilen-Innenbreite gemessen:
```
Breite ≥ EVENT_ROW_REFLOW_WIDTH → 1 Zeile (heutiges Layout)                                [unverändert]
Breite <  EVENT_ROW_REFLOW_WIDTH → 2 Zeilen (deterministisch):
    Zeile 1 (Triage, immer):  [Severity-Rail][Glyph]  [Zeitstempel]  [Typ-Glyph + typeText]
    Zeile 2 (Identität/Meta): [Avatar][Name]  [· Korrelation]  [Projekt?]
```
- Messung via `BoxWithConstraints` um den Zeilen-Inhalt (Zeilen-eigene Innenbreite, nicht Window).
- **Keine Spalte darf clippen oder Char-für-Char quetschen** — das ist die Kern-Invariante; die 2-Zeilen-
  Gruppierung ist der deterministische Weg dorthin.
- **GapRow** (`log.dropped`, EventRowUi.kt Z. 131) ist kurz (Rail + ⚠ + Text) → kein Multi-Column-Risiko,
  bleibt einzeilig.

### 2.3 Pane-Threshold-Konsistenz (Browse, gehört in diese Datei)
`EventBrowsePanel.kt` `TWO_PANE_MIN_WIDTH = 560.dp` (Z. 120) → auf **600** ziehen, deckungsgleich mit dem
geteilten `PANE_COLLAPSE_WIDTH` (CYP-156). Sonst bleiben bei 560–600dp ACL=Single, aber EventBrowse=Two-Pane
(sichtbar inkonsistent). (Diese Zeile liegt in der CYP-154-gekoppelten Datei → bewusst hier statt in CYP-156,
hält CYP-156 konfliktfrei.)

## 3. Disclosure-Invarianten — UNVERÄNDERT
Severity-Rail = einzige Farb-Achse (nie allein, WCAG); Typ = monospace Wire (aggregierbar); Identität =
Avatar+Name (CYP-14); Korrelation `take(8)` bzw. „—" (nie geraten); Projekt nur in Cross-View, als Text;
„pausiert ≠ live"; „subset"-Cue; `log.dropped` = Gap-Zeile. Nur das **Layout** ändert sich.

## 4. a11y / RTL
- **`EventRow.contentDescription`** (EventRowUi.kt Z. 79/86) bleibt auf dem **äußeren** Container (Row →
  Column/2-Zeilen) — der Screenreader-Text (Severity, Typ, Agent, ts) ist **unverändert**, egal ob 1 oder 2
  visuelle Zeilen.
- RTL: `FlowRow` + 2-Zeilen-Gruppierung folgen der Layout-Direction; spacedBy + Glyphen richtungsneutral.

## 5. Verifikation
- Code: §1 (FilterBar Z. 186; TailHeader Z. 115; EventRow Z. 81; Pane-Gate Z. 72/120).
- `FlowRow` geprüft: in CMP 1.11.1 verfügbar, in keinem `commonMain`-Panel bisher genutzt.
