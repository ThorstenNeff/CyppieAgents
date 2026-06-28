# Projekt-Verwaltung & Switcher — testTags (CYP-91 / CYP-92)

> Owner: UIUX-Designer · Epic CYP-78 · Stand 2026-06-28 · Status: Vorschlag — wartet auf PO-/Dev-Gegenlesen
> Schema (Test-Contract v0.5 §2, Dev/QA-Vertrag CYP-7): **prefixlos**
> `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`, Segmentwerte `[A-Za-z0-9-]+` (camelCase ok,
> **keine Punkte** im Wert). Areas hier = `projectSwitcher` (CYP-92) + `projectMgmt` (CYP-91).
> **Code = Source of Truth:** Reuse unten gegen die echten Quellen (develop `c2055bd`) verifiziert
> (Lehre aus CYP-26-Tag-Drift).

## Neue Tags — Area `projectSwitcher` (CYP-92, Top-Level)
| Tag | Element |
|---|---|
| `projectSwitcher.bar` | Wurzel der Top-Level-Switcher-Leiste (über dem `WindowHost`) |
| `projectSwitcher.active` | Aktiv-Anzeige des laufenden Projekts (Name + nicht-farblicher Marker) |
| `projectSwitcher.menu` | Trigger/Container des Wechsel-Menüs (Projektliste) |
| `projectSwitcher.item.<id>` | Projekt-Eintrag im Menü (scope = Projekt-id), klickbar = wechseln |
| `projectSwitcher.item.<id>.active` | Aktiv-Marker am Eintrag (Form, nicht nur Farbe) |
| `projectSwitcher.scopeHint` | Scope-Disclosure „alle Fenster/Daten gehören zu diesem Projekt" |
| `projectSwitcher.switchHint` | Wechsel-Hinweis „lädt neu – nichts wird gelöscht" (nicht-destruktiv) |
| `projectSwitcher.manage` | Einstieg „Projekte verwalten" (öffnet `projectMgmt`) |

## Neue Tags — Area `projectMgmt` (CYP-91, Verwaltungs-Fläche)
| Tag | Element |
|---|---|
| `projectMgmt.panel` | Wurzel-Container der Verwaltungs-Fläche |
| `projectMgmt.gateHint` | Operator-Gate-Hinweis (fail-closed, read-only ohne Token) |
| `projectMgmt.add` | „Projekt anlegen"-Button |
| `projectMgmt.list` | Projektliste |
| `projectMgmt.row.<id>` | Listenzeile je Projekt (scope = Projekt-id) |
| `projectMgmt.row.<id>.active` | Aktiv-Marker der Zeile (Form, nicht nur Farbe) |
| `projectMgmt.row.<id>.rename` | Umbenennen-Aktion der Zeile |
| `projectMgmt.row.<id>.delete` | Löschen-Aktion der Zeile (deaktiviert bei aktiv/letztes) |
| `projectMgmt.row.<id>.deleteBlocked` | Inline-Begründung **vor** der Aktion (aktiv→„erst wechseln" / letztes) |
| `projectMgmt.error` | Fehlerzeile (Liste-Ebene) |

### Anlegen-Dialog
| Tag | Element |
|---|---|
| `projectMgmt.addDialog` | Wurzel des Anlegen-Dialogs |
| `projectMgmt.addDialog.name` | Projektname-Feld |
| `projectMgmt.addDialog.confirm` | Bestätigen („Anlegen") |
| `projectMgmt.addDialog.cancel` | Abbrechen |
| `projectMgmt.addDialog.error` | Validierungs-/Server-Fehler (leer/dupliziert/fehlgeschlagen) |

### Umbenennen-Dialog
| Tag | Element |
|---|---|
| `projectMgmt.renameDialog` | Wurzel des Umbenennen-Dialogs |
| `projectMgmt.renameDialog.name` | Projektname-Feld |
| `projectMgmt.renameDialog.confirm` | Bestätigen („Umbenennen") |
| `projectMgmt.renameDialog.cancel` | Abbrechen |
| `projectMgmt.renameDialog.error` | Validierungs-/Server-Fehler |

### Löschen-Dialog (irreversibel + kaskadierend — Reuse RemoveDialog-Muster)
| Tag | Element |
|---|---|
| `projectMgmt.deleteDialog` | Wurzel des Löschen-Dialogs |
| `projectMgmt.deleteDialog.consequences` | Folgenanzeige (Kategorien — garantiert; INFO) |
| `projectMgmt.deleteDialog.counts` | Optionale Mengen-Zusammenfassung (advisory; entfällt wenn nicht geliefert) |
| `projectMgmt.deleteDialog.worktreeChoice` | Worktree-Schicksal-Radiogruppe |
| `projectMgmt.deleteDialog.worktreeKeep` | „Worktrees behalten" (Default) |
| `projectMgmt.deleteDialog.worktreeDelete` | „Worktrees aller Agenten löschen" |
| `projectMgmt.deleteDialog.worktreeWarning` | Datenverlust-Warnung (ERROR; nennt den Lösch-Pfad) |
| `projectMgmt.deleteDialog.confirm` | Destruktiver benannter Button „Projekt endgültig löschen" (error-gefärbt) |
| `projectMgmt.deleteDialog.cancel` | Abbrechen |
| `projectMgmt.deleteDialog.error` | Lösch-Fehler (Server) |

## Reuse (gegen Code verifiziert — NICHT neu definieren)
| Reuse | Quelle (develop `c2055bd`) | Zweck hier |
|---|---|---|
| `ui/TonedHint.kt` + `HintTone{EFFECT_DEFERRED,GATED,INFO,ERROR}` | **Komponente** (CYP-99) | Jede Hinweiszeile (scopeHint/switchHint/gateHint/consequences/counts/worktreeWarning/deleteBlocked/error) rendert über `TonedHint(text, tone, tag)`. Der `tag`-Parameter trägt jeweils einen der **oben definierten** Tags — die Komponente ist Code-Reuse, **kein** Tag-Reuse. |
| `AgentManagementPanel`-`RemoveDialog`-Muster | **Muster** (CYP-87) | `projectMgmt.deleteDialog` folgt 1:1: error-`Button` (`containerColor=error/onError`) + INFO-Folgen + Worktree-Fate-`RadioRow`-Gruppe + ERROR-Datenverlust. Eigene Tags (projekt-weit), gleiche Struktur. |

> **Kein `window.<id>`-Reuse (bewusst):** Weder der Switcher noch die Projekt-Verwaltung sind
> Canvas-Fenster im `WindowHost`. Der Switcher ist **Top-Level über** dem Host (`projectSwitcher.bar`);
> die Verwaltung ist ein **Overlay/Dialog** aus dem Switcher (`projectMgmt.panel`) — sie steht **über**
> dem Projekt-Scope, den sie verwaltet, und darf darum nicht selbst eine projekt-gescopte Fenster-id
> tragen. Daher kein `WindowTestTags.window(...)` hier.

## Self-Validation
- **38 neue Tags** (8 `projectSwitcher` + 10 `projectMgmt`-Liste + 5 Anlegen + 5 Umbenennen + 10 Löschen);
  Schema-konform (camelCase-Werte wie `scopeHint`/`deleteBlocked`/`worktreeWarning`; keine Punkte in
  Segmentwerten).
- **Verschachtelung** folgt Präzedenz: Per-Projekt-Scope `…item.<id>[.active]` / `…row.<id>[.active|
  .rename|.delete|.deleteBlocked]` analog `comm.channel.<id>`, `agent.<id>.…`, `eventBrowse.row.<index>`.
- **Reuse gegen Code verifiziert** (develop `c2055bd`): `TonedHint`/`HintTone` exakt (Mapping
  EFFECT_DEFERRED/ERROR/INFO/GATED); `AgentManagementPanel.RemoveDialog` als Struktur-Muster. **Kein
  Tag-Drift** — alle 38 Tags sind neu, keine kollidieren mit bestehenden Areas.
- Fenster-id-Namen (`PRODUCT_LEAD_WINDOW_ID`-artig) sind hier nicht nötig (kein Canvas-Fenster); finale
  Konstantennamen für `projectSwitcher`/`projectMgmt`-Tags = Dev, Tags folgen den oben fixierten Werten.
