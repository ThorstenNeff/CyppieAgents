# Agenten-Verwaltung — testTags (CYP-86 / CYP-87 / CYP-88)

> Owner: UIUX-Designer · Epic CYP-76 · Stand 2026-06-28 · Status: Vorschlag — wartet auf Dev-Gegenlesen
> Schema (Test-Contract v0.5 §2, Dev/QA-Vertrag CYP-7): **prefixlos** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`, Segmentwerte `[A-Za-z0-9-]+` (camelCase ok, **keine Punkte** im Wert). Area hier = `agentMgmt`.
> **Code = Source of Truth:** Reuse-Tags unten gegen die echten `*Tags.kt` (develop `cb2e9b5`) verifiziert (Lehre aus CYP-26-Tag-Drift).

## Neue Tags (Area `agentMgmt`)
| Tag | Element |
|---|---|
| `agentMgmt.panel` | Wurzel-Container im Fensterinhalt |
| `agentMgmt.list` | Agentenliste |
| `agentMgmt.item.<id>` | Listenzeile je Agent (scope = Agent-id) |
| `agentMgmt.item.<id>.edit` | Bearbeiten-Aktion der Zeile |
| `agentMgmt.item.<id>.remove` | Entfernen-Aktion der Zeile |
| `agentMgmt.addButton` | Agent-hinzufügen-Button |
| `agentMgmt.gateHint` | Operator-Gate-Hinweis (read-only) |
| **CYP-86 (hinzufügen)** | |
| `agentMgmt.add.dialog` | Hinzufügen-Dialog |
| `agentMgmt.add.id.input` | Agent-ID-Eingabe |
| `agentMgmt.add.name.input` | Name-Eingabe |
| `agentMgmt.add.role.picker` | Rollen-Picker (PO/WORKER) |
| `agentMgmt.add.persona.input` | Persona/CLAUDE.md-Eingabe |
| `agentMgmt.add.launch.input` | Startkommando-Eingabe |
| `agentMgmt.add.worktree.input` | Worktree-Ordner-Eingabe |
| `agentMgmt.add.confirm` | Anlegen |
| `agentMgmt.add.cancel` | Abbrechen |
| `agentMgmt.add.spawnHint` | „angelegt, noch nicht gestartet"-Hinweis |
| `agentMgmt.add.error` | Fehler (id-Kollision/PO-Konflikt/invalid) |
| **CYP-87 (entfernen)** | |
| `agentMgmt.remove.dialog` | Bestätigungs-Dialog |
| `agentMgmt.remove.consequences` | Folgenanzeige (was passiert) |
| `agentMgmt.remove.worktreeChoice` | Behalten/Löschen-Wahl |
| `agentMgmt.remove.worktreeWarning` | Datenverlust-Warnung (Löschen) |
| `agentMgmt.remove.confirm` | Bestätigen (destruktiv) |
| `agentMgmt.remove.cancel` | Abbrechen |
| `agentMgmt.remove.error` | Fehler (z. B. last_po) |
| **CYP-88 (Konfig ändern)** | |
| `agentMgmt.edit.dialog` | Bearbeiten-Dialog |
| `agentMgmt.edit.role.picker` | Rollen-Picker |
| `agentMgmt.edit.persona.input` | Persona/CLAUDE.md-Eingabe |
| `agentMgmt.edit.launch.input` | Startkommando-Eingabe |
| `agentMgmt.edit.effectHint` | „wirkt erst nach Neustart"-Hinweis |
| `agentMgmt.edit.save` | Speichern |
| `agentMgmt.edit.cancel` | Abbrechen |
| `agentMgmt.edit.error` | Fehler |

## Reuse (bestehende Tags — gegen Code verifiziert, NICHT neu definieren)
| Tag | Quelle (`*Tags.kt`) | Zweck hier |
|---|---|---|
| `window.<id>` (= `window.agentMgmt`) | `WindowTestTags.window(id)` | Verwaltungs-Fenster im Manager |
| `window.<id>.content` | `WindowTestTags.content(id)` | Fensterinhalt (agentMgmt.panel darunter) |
| `window.<id>.titlebar` | `WindowTestTags.titleBar(id)` | Fenster-Titelleiste |
| `agent.<id>.status` | `AgentViewTags.status(id)` | Lifecycle-Status in der Listenzeile (Reuse CYP-73) |
| `agent.<id>.startBtn` / `.stopBtn` / `.restartBtn` | `AgentViewTags.{start,stop,restart}Btn(id)` | Lifecycle-Aktionen / Restart-Aktivierung (Reuse CYP-73, kein Doppel) |

## Self-Validation
- 32 neue Tags; Schema-konform (camelCase-Werte wie `addButton`/`gateHint`/`worktreeChoice`/`effectHint` analog zu bestehendem `composerInput`/`resizeHandle`; keine Punkte in Segmentwerten).
- Per-Agent-Scope `agentMgmt.item.<id>[.edit|.remove]` folgt dem Präzedenzfall tieferer Verschachtelung (`comm.channel.<id>`, `agent.<id>.event.<index>.<kind>`).
- Reuse-Tags **gegen Code verifiziert** (`cb2e9b5`): `WindowTestTags.window/content/titleBar`, `AgentViewTags.status/startBtn/stopBtn/restartBtn` — exakte Strings, kein Drift.
- Fenster-id `agentMgmt` ist Vorschlag; finaler Konstantenname (`AGENT_MGMT_WINDOW_ID`) = Dev, Tag folgt der id.
