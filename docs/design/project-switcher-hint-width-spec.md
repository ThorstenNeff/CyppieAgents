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

## 2. Soll
- Den Hinweis (und damit die Menü-Inhaltsbreite) **beschränken**, sodass er **umbricht** statt zu clippen:
  `Modifier.widthIn(max = SWITCHER_HINT_MAX_WIDTH)` am `TonedHint` im `DropdownMenu`.
- **Wortlaut bleibt** — er ist disclosure-tragend: „nothing is deleted" trennt den (nicht-destruktiven)
  Wechsel vom Löschen. **Nicht kürzen.**
- Auch die Menü-`DropdownMenuItem`-Labels (Projektnamen) sollten lange Namen mit `maxLines = 1` +
  `TextOverflow.Ellipsis` bändigen (wie die Aktiv-Zeile Z. 72–73 es schon tut), damit ein langer Projektname
  das Menü nicht ebenfalls über den Rand zieht. (Sekundär; primär ist der Hinweis.)
- Kein Breakpoint nötig — eine reine Breitenschranke; gilt auf jedem Formfaktor (Desktop-Menü ist ohnehin
  schmal genug).

## 3. Disclosure / a11y / RTL — unverändert
- Disclosure (Wechsel ≠ Löschen, Scope-Grenze) unverändert; INFO-Ton bleibt.
- a11y: Hinweis-Text + `contentDescription` der Menü-/Item-Knoten unverändert vorlesbar.
- RTL: `widthIn` ist richtungsneutral; das Menü verankert sich gespiegelt, der Text umbricht regelkonform.

## 4. Verifikation
- Code: `ProjectSwitcherBar.kt` Z. 84–86 (`DropdownMenu { TonedHint(project_switch_hint) }`, kein `widthIn`);
  Aktiv-Zeile Z. 63–75 (`weight(1f)` + `maxLines=1`/Ellipsis als Vorbild); ▾-Button Z. 79–82.
