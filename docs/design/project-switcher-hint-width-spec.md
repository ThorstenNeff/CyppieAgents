# ProjectSwitcher Dropdown-Hinweis-Breite (CYP-159, Klasse C)

> Owner: UIUX · Stand 2026-06-30 · docs-only · Grounded @ develop `d57dccb`.
> Auslöser: Android-Tester, 411dp-Phone — „Projekt-Verwaltung": der Switch-Dropdown-Hinweis („Switching
> reloads the UI…") clippt über die rechte Bildschirmkante.
> Scope: **nur Klasse C**. Klasse A = CYP-156, Klasse B = CYP-158. Gesamt-Audit:
> `uiux/COMPACT-WIDTH-AUDIT-DESIGN.md`. **Koppelt an CYP-154** (Datei `ProjectSwitcherBar.kt`).

## 0. Wichtig: der Defekt sitzt im Switcher, nicht im Management-Panel
Das Symptom „Projekt-Verwaltung" liegt **nicht** in `ProjectManagementPanel.kt` (sauberes `Column` +
`verticalScroll`, single-column ok), sondern in **`project/ProjectSwitcherBar.kt`** — der Leiste über dem
WindowHost.

## 1. Problem (code-verifiziert)
`ProjectSwitcherBar.kt` Z. 84–86: im `DropdownMenu` rendert ein `TonedHint(project_switch_hint, INFO)` einen
**langen einzeiligen** Hinweis **ohne Breitenschranke**. Das Menü ist am ▾-Button verankert (rechtsbündig,
nach der `weight(1f)`-Aktiv-Zeile Z. 64) → der Text dehnt die Menü-Inhaltsbreite über die rechte
Bildschirmkante → clippt. Wortlaut (values-en): „Switching reloads the UI for the selected project — nothing
is deleted."

**Tester-Overlay (2026-06-30, Pixel_9a):** die `DropdownMenu`-**Surface ist edge-to-edge** `x[33..1080]px`
(= volle 411dp-Breite), weil der einzeilige Hinweis sie so weit aufzieht; „…is deleted." wird rechts
abgeschnitten. Der Trigger (▾) sitzt oben rechts (~894px).

- **(Primär) Hinweis-Breite beschränken → Surface schrumpft mit.** Ein `DropdownMenu` dimensioniert sich auf
  sein **breitestes Kind**; der einzeilige Hinweis ist dieses Kind. `Modifier.widthIn(max =
  SWITCHER_HINT_MAX_WIDTH)` am `TonedHint` lässt ihn **umbrechen (multi-line)** → die Menü-Surface ist nicht
  länger edge-to-edge, sondern ~`SWITCHER_HINT_MAX_WIDTH` breit. Das ist die Wurzel-Korrektur.
- **(Sekundär) Menü unter dem Trigger ausrichten.** Damit das (jetzt ~300dp schmale) Menü unter dem ▾ statt
  am Bildschirmrand klebt: `DropdownMenu(offset = …)` / Alignment unter dem Trigger-`Box`. Verhindert, dass
  das Menü trotz schmalerer Surface rechtsbündig „hängt". (Compose hält das Menü ohnehin im Fensterrahmen;
  die Ausrichtung ist Politur, die Breitenschranke ist die Substanz.)
- **Wortlaut bleibt** — disclosure-tragend: „nothing is deleted" trennt den (nicht-destruktiven) Wechsel vom
  Löschen. **Nicht kürzen** — stattdessen umbrechen lassen (multi-line, Tester-Vorschlag bestätigt).
- Auch die Menü-`DropdownMenuItem`-Labels (Projektnamen) mit `maxLines = 1` + `TextOverflow.Ellipsis`
  bändigen (wie die Aktiv-Zeile Z. 72–73), damit ein langer Name die Surface nicht erneut aufzieht.
- Kein Breakpoint nötig — reine Breitenschranke + Ausrichtung; gilt auf jedem Formfaktor.

## 3. Disclosure / a11y / RTL — unverändert
- Disclosure (Wechsel ≠ Löschen, Scope-Grenze) unverändert; INFO-Ton bleibt.
- a11y: Hinweis-Text + `contentDescription` der Menü-/Item-Knoten unverändert vorlesbar.
- RTL: `widthIn` ist richtungsneutral; das Menü verankert sich gespiegelt, der Text umbricht regelkonform.

## 4. Verifikation
- Code: `ProjectSwitcherBar.kt` Z. 84–86 (`DropdownMenu { TonedHint(project_switch_hint) }`, kein `widthIn`);
  Aktiv-Zeile Z. 63–75 (`weight(1f)` + `maxLines=1`/Ellipsis als Vorbild); ▾-Button Z. 79–82.
