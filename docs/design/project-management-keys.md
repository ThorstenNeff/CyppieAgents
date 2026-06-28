# Projekt-Verwaltung & Switcher — i18n-Keys (CYP-91 / CYP-92)

> Owner: UIUX-Designer · Epic CYP-78 · Stand 2026-06-28 · Status: Vorschlag — wartet auf PO-/Dev-Gegenlesen
> Konvention (verifiziert gg. `app/shared/.../composeResources/values/strings.xml`, develop `c2055bd`):
> **Underscore-Realkeys** (keine Punkte), positionsbasierte Argumente `%1$s`. **DE = Default** (`values/`),
> **EN** (`values-en/`). Parität Pflicht.
> Modul: `:app:shared` compose.resources → **Shared-Key-Sync mit der Impl timen** (CYP-91/92).

## Neue Keys

### CYP-92 — Projekt-Switcher (Top-Level)
| Key | DE | EN |
|---|---|---|
| `project_switcher_active` | Aktives Projekt: %1$s | Active project: %1$s |
| `project_switcher_scope_hint` | Alle Fenster und Daten gehören zu diesem Projekt. | All windows and data belong to this project. |
| `project_switch_hint` | Wechsel lädt die Oberfläche für das gewählte Projekt neu – nichts wird gelöscht. | Switching reloads the UI for the selected project — nothing is deleted. |
| `project_manage` | Projekte verwalten | Manage projects |
| `a11y_project_switcher_menu` | Projekt-Menü öffnen, aktiv: %1$s | Open project menu, active: %1$s |
| `a11y_project_switch_to` | Zu Projekt %1$s wechseln | Switch to project %1$s |

### CYP-91 — Verwaltung (gemeinsam)
| Key | DE | EN |
|---|---|---|
| `project_mgmt_title` | Projekt-Verwaltung | Project management |
| `project_mgmt_operator_required` | Nur mit Operator-Token änderbar | Editable only with an operator token |
| `project_add` | Projekt anlegen | Add project |
| `project_rename` | Umbenennen | Rename |
| `project_delete` | Löschen | Delete |
| `project_cancel` | Abbrechen | Cancel |

> `project_add` dient als Button-Label **und** als Titel des Anlegen-Dialogs; `project_rename` dient als
> Zeilen-Aktion **und** als Bestätigen-Label im Umbenennen-Dialog (gleicher Wortlaut, surface-lokal).

### CYP-91 — Anlegen
| Key | DE | EN |
|---|---|---|
| `project_add_name_label` | Projektname | Project name |
| `project_add_confirm` | Anlegen | Create |
| `project_add_name_empty` | Bitte einen Namen eingeben | Please enter a name |
| `project_add_name_exists` | Ein Projekt mit diesem Namen existiert bereits | A project with this name already exists |
| `project_add_error` | Anlegen fehlgeschlagen | Create failed |
| `a11y_project_add_name` | Projektname eingeben | Enter project name |

### CYP-91 — Umbenennen
| Key | DE | EN |
|---|---|---|
| `project_rename_title` | Projekt „%1$s" umbenennen | Rename project “%1$s” |
| `project_rename_error` | Umbenennen fehlgeschlagen | Rename failed |

> Das Umbenennen-Feld reuse't `project_add_name_label` + die Validierung `project_add_name_empty` /
> `project_add_name_exists` (gleiche Eingabe, surface-lokal — kein neuer Schlüssel nötig).

### CYP-91 — Löschen (irreversibel + kaskadierend)
| Key | DE | EN |
|---|---|---|
| `project_delete_title` | Projekt „%1$s" löschen? | Delete project “%1$s”? |
| `project_delete_consequences` | Unwiderruflich gelöscht: laufende Sessions werden gestoppt, alle Kanäle und Zugriffsrechte, der Nachrichtenverlauf und die Ereignis-Historie dieses Projekts. | Permanently deleted: running sessions are stopped, and all channels, access rights, message history and event history of this project. |
| `project_delete_consequences_counts` | Betroffen: %1$s | Affected: %1$s |
| `project_delete_worktree_keep` | Worktrees behalten | Keep worktrees |
| `project_delete_worktree_delete` | Worktrees aller Agenten löschen | Delete all agents' worktrees |
| `project_delete_worktree_warning` | Nicht committete/nicht gepushte Arbeit in den lokalen Worktrees dieses Projekts geht verloren. Gepushte Branches (agent/…) bleiben erhalten. | Uncommitted/unpushed work in this project's local worktrees will be lost. Pushed branches (agent/…) are kept. |
| `project_delete_confirm` | Projekt endgültig löschen | Delete project permanently |
| `project_delete_active_blocked` | Aktives Projekt – erst zu einem anderen wechseln, dann löschbar. | Active project — switch to another first, then it can be deleted. |
| `project_delete_last_blocked` | Das letzte Projekt kann nicht gelöscht werden. | The last project cannot be deleted. |
| `project_delete_error` | Löschen fehlgeschlagen | Delete failed |

> **`project_delete_active_blocked` vs. `project_delete_last_blocked` — zwei getrennte Guardrails:**
> `…active_blocked` = das **aktive** Projekt (erst wechseln → dann löschbar). `…last_blocked` = das
> **einzige** verbleibende Projekt (nie löschbar). Beide sind als **Anleitung/Systemregel** INFO-getönt
> (TonedHint.INFO), bewusst abweichend vom ERROR-getönten `agent_remove_last_po` der Agenten-Verwaltung —
> es ist hier kein Fehlversuch, sondern eine erreichbare/erwartete Sperre. **Server lehnt beide ebenfalls
> ab** (advisory UI; Präzedenz CYP-49): die `:core`-Codes (PO-entschieden) sind **`active_project_protected`**
> (409) ⇒ `project_delete_active_blocked` und **`last_project`** (409) ⇒ `project_delete_last_blocked`.

> **`project_delete_consequences_counts` ist advisory:** Mengen kommen best-effort vom Backend; fehlen sie,
> wird die Zeile **weggelassen** (kein „0", kein Phantom). Die garantierte Aussage ist
> `project_delete_consequences` (Kategorien).

## Fehler-Code-Mapping (PO-entschieden, an Backends `:core` angeglichen)
Die UI mappt die Server-Codes auf die obigen Keys:
- **Create:** `invalid_project_id` (400) → `project_add_name_empty`; `project_exists` (409) →
  `project_add_name_exists`; sonstiger Fehler → `project_add_error`.
- **Rename:** `invalid_project_id` (400) → `project_add_name_empty`; `project_not_found` (404) /
  sonstiger Fehler → `project_rename_error`.
- **Delete (Prüf-Reihenfolge):** `project_not_found` (404) → `last_project` (409) →
  `active_project_protected` (409); `last_project` → `project_delete_last_blocked`,
  `active_project_protected` → `project_delete_active_blocked`; sonstiger Fehler → `project_delete_error`.
- **Gate (alle Mutationen):** `operator_required` (403/401) → `project_mgmt_operator_required` (Gate-Hint).

## Reuse (bewusst KEINE Cross-Surface-Übernahme)
Kein `agent_*`/`acl_*`-Key wird über die Surface-Grenze wiederverwendet (Cross-Surface-Drift-Risiko, vgl.
CYP-51-Befund + Konsolidierungs-Hinweis in `agent-management-keys.md`). `project_cancel` überlappt
semantisch mit `agent_cancel`/`acl_cancel`, bleibt aber **surface-lokal**.
Falls das Team einen generischen Shared-Satz (`generic_save/cancel`) will → eigenes
Konsolidierungs-Ticket. Tonal-Styling kommt aus der **geteilten `TonedHint`-Komponente** (CYP-99) — das
ist Code-Reuse, kein Key-Reuse.

## Self-Validation
- **30 neue Keys** (6 CYP-92 + 6 gemeinsam + 6 Anlegen + 2 Umbenennen + 10 Löschen), alle DE+EN. (Die
  toten `a11y_project_rename`/`a11y_project_delete` wurden gestrichen — Row-Buttons tragen sichtbaren Text,
  contentDescription redundant; Katalog dead-frei.) Die
  Verhaltens-Keys sind im Spec (`PROJECT-MANAGEMENT.md`) zitiert; a11y-Keys (`a11y_project_*`) und
  Fehler-Keys (`project_*_error`) sind an ihren Tags in `project-management-tags.md` verankert
  (a11y-Description bzw. `…error`-Slot).
- **Argument-Keys** (`%1$s`): `project_switcher_active`, `a11y_project_switcher_menu`, `a11y_project_switch_to`
  (=Projektname); `project_rename_title`, `project_delete_title` (=Projektname);
  `project_delete_consequences_counts` (=Mengen-Zusammenfassung). **Kein** sensibler/Content-Klartext wird
  interpoliert (nur Projektnamen + aggregierte Mengen).
- **Kollision:** `project_*` ist greenfield — `grep name="project…"` gegen `strings.xml` @ `c2055bd` = 0
  Treffer (nur ein Kommentar „Projekt-Settings" existiert, keine Realkeys).
- DE/EN-Parität: jede Zeile beidseitig befüllt; gleiche Argument-Anzahl je Sprache.
