# Agenten-Verwaltung — Hinzufügen / Entfernen / Konfig ändern (Desktop) (v0.1)

> Owner: UIUX-Designer · Epic: **CYP-76** (S14) · Stories: **CYP-86** (hinzufügen) + **CYP-87** (entfernen) + **CYP-88** (Konfig ändern) · Status: **Entwurf — wartet auf Dev-Gegenlesen** · Stand: 2026-06-28
> **Kanonischer Ort:** geteiltes Repo `KMPCyppieAgents` unter `docs/AGENT-MANAGEMENT.md`.
> Begleit-Artefakte (Muster CYP-17/S15): `docs/design/agent-management-tokens.json`, `agent-management-keys.md`, `agent-management-tags.md`.
> **Brand:** CyppieAgents (Anti-Hype). Desktop/`commonMain`-tauglich.

> **Bezug (im Code verifiziert, develop `cb2e9b5`):**
> - **Rolle:** `core/.../model/CommModel.kt` → `enum class Role { PO, WORKER }`. **„Product-Lead" existiert NICHT** (greenfield `:core`-Enum-Erweiterung).
> - **AgentConfig:** `server/.../boot/PlatformConfig.kt` → `AgentConfig(id, name, role, worktree="", launch="claude")` + `worktreeName = worktree.ifBlank{id}`. **Kein `claudeMd`/`persona`-Feld** (greenfield). Validierung: `agents.isNotEmpty()`, **genau ein PO**, **eindeutige ids**.
> - **Connector (CYP-1):** `connector/ConnectorSession.kt` → `interface Connector { fun open(agentId): ConnectorSession }`; `ClaudeCodeConnector.open(agentId, worktreeName)` baut env (`ANTHROPIC_API_KEY`, `HUB_AGENT_ID`) + `command = launch + streamJsonArgs`, spawnt via `ProcessSpawner`. **Agenten-Set ist beim Boot fix** (`BootOrchestrator` iteriert `config.agents` einmal). `ConnectorSessions.remove(agentId)` schließt die Session, **löscht aber keinen Worktree**.
> - **Worktree:** `boot/WorktreeManager.kt` → `ensureClone`, `ensureWorktree(name, baseBranch)` (Branch `agent/<name>`, idempotent). **Kein `deleteWorktree`/`git worktree remove`** (greenfield).
> - **Endpunkte:** nur `GET /api/agents` (→ `Agent(id,name,role,worktree)`) + **CYP-73-Lifecycle** `POST /api/agents/{id}/{start|stop|restart}` (operator-gated). **Kein** POST/PUT/DELETE `/api/agents` (greenfield).
> - **Lifecycle-Reuse (CYP-73):** `AgentLifecycleState{RUNNING,STOPPED,ERROR,UNKNOWN}`, `AgentViewTags.{startBtn,stopBtn,restartBtn}` = `agent.<id>.{…}Btn`, Keys `agent_ctl_{start,stop,restart}` (restart DE „Neustart"/EN „Restart"), Operator-403 `operator_required`.
> - **Confirm-Dialog-Muster (Reuse):** `acl/AclMatrixTags.kt` (`…lockoutDialog.confirm/.cancel`, `…presetPreview.confirm/.cancel`) + Keys `acl_continue`/`acl_cancel`.
> - **Mount:** `AgentShell.kt` windows-Liste (heute **statisch** `remember{listOf(...)}`, **nicht** aus `GET /api/agents`) + `windowContent`-`when`.
> - **projektId (S12.1/CYP-81, FYI PO):** `Channel/AclEntry/Message` tragen additiv `projectId` (default „default", MVP=1). Ein Agent gehört **genau einem** Projekt; im MVP=1 ist „Projekt" fester Kontext (**kein** Switcher — das ist S13/[MP]).

Spezifiziert die **Agenten-Verwaltung** als ein Desktop-Fenster: Liste der Agenten + **Hinzufügen** (CYP-86), **Entfernen** (CYP-87) und **Konfig ändern** (CYP-88). Keine Implementierungsvorgabe — Verhalten, Disclosure-Leitplanken, Tokens/Keys/Tags + der **zu bauende Datenpfad** (greenfield).

---

## 0. Geltungsbereich & Nicht-Ziele

| | |
|---|---|
| **In Scope** | Ein **Agenten-Verwaltungs-Fenster** (`window.<id>` = `agentMgmt`): Agentenliste, Hinzufügen-Dialog (CYP-86), Entfernen mit Bestätigung + Folgenanzeige + Worktree-Schicksal (CYP-87), Konfig-Ändern-Dialog mit Restart-Transparenz (CYP-88). Operator-Gating, Disclosure. testTags + a11y de/en. |
| **Nicht-Ziele** | **Kein** Backend-/Connector-Bau (nur der **Vertrag** + UI **über** dem Connector — §2). **Keine** Config-Logik-Duplizierung (Connector besitzt Spawn + Agent-Config). **Lifecycle (start/stop/restart) NICHT re-slicen** — nur **Reuse CYP-73**. Kein Projekt-Switcher (S13/[MP]). Kein ACL-/Kanal-Editor (CYP-19). |

**Leitprinzip (PO-Auflage):** Die UI **ruft** connector-gestützte Endpunkte; sie **verwaltet nicht selbst** Spawn/Config. Persona/CLAUDE.md, Worktree-Anlage/-Löschung und Spawn bleiben **Connector-Eigentum**.

---

## 1. Disclosure-Prinzipien (für alle drei Stories)

1. **„Gespeichert" ≠ „Aktiv" (CYP-88).** Eine Konfig-Änderung (Rolle/CLAUDE.md/`launch`) wirkt **erst beim nächsten Spawn** (Connector liest Config beim `open()`). Amber-Hinweis, **nicht** Erfolg-grün; Aktivierung = **Reuse CYP-73-Restart**. Niemals impliziert ein Save, der laufende Agent nutze die neue Config schon.
2. **Irreversibel = Bestätigung + klare Folgenanzeige (CYP-87).** Entfernen stoppt die Session **und** (bei Wahl) löscht den Worktree. Die Bestätigung listet **explizit, was passiert** — und der **Worktree-Löschpfad** warnt vor **nicht committeter/nicht gepushter Arbeit** (unwiderbringlich).
3. **Guardrails ehrlich zeigen (CYP-86/88).** „Genau ein PO" + „eindeutige id": die UI **verhindert** Regelbruch sichtbar (PO-Rolle gesperrt, wenn schon ein PO existiert; id-Kollision = Feldfehler), statt eine Server-Ablehnung erst nachträglich zu erklären. Den **einzigen PO** kann man nicht entfernen/umrollen (Hub bricht sonst).
4. **Operator-gated, fail-closed, Gate beobachtbar.** Verwalten nur mit Operator-Token (Server 403 `operator_required`); Controls sichtbar, aber disabled/read-only ohne Token (Muster CYP-73/ACL/S15).
5. **Farbe nie alleiniger Träger.** Jeder Zustand = Farbe + Icon/Form + Text + a11y-Label. **Identitäts-/Rollenfarbe ≠ Berechtigung.**

---

## 2. Datenpfad — zu bauender Vertrag (Connector-Naht, **flag an PO/Backend**)

> **Wichtig:** S14 ist **fast vollständig greenfield am Backend**. Heute: Agenten-Set fix beim Boot, kein Agent-CRUD, kein `deleteWorktree`, kein `claudeMd`-Feld, Rolle nur PO/WORKER. Die Spec definiert den **Vertrag über dem CYP-1-Connector**; das Backend/Connector muss ihn liefern, **bevor** die UI echte Mutationen ausführt. Bis dahin baut Dev gegen Vertrag/Stub.

**Vom Backend/Connector zu liefern (Vorschlag, finale Form = PO/Backend):**

| Endpunkt | Gate | Body / Wirkung | Fehler |
|---|---|---|---|
| `GET /api/agents` *(existiert)* | auth | → `[{id,name,role,worktree}]` | 401 |
| `POST /api/agents` *(neu)* | **Operator** | `{id,name,role,worktree?,launch?,persona?}` → Connector legt Worktree an + registriert Agent; **Spawn erst auf Lifecycle-Start** | 401 / **403 `operator_required`** / 400 `invalid_agent` / 409 `agent_exists` / 409 `po_already_exists` |
| `PUT /api/agents/{id}` *(neu)* | **Operator** | `{name?,role?,launch?,persona?}` → schreibt Agent-Config; **wirkt erst beim nächsten Spawn** | 401 / 403 / 404 `agent_not_found` / 409 `po_already_exists` |
| `DELETE /api/agents/{id}?worktree=keep\|delete` *(neu)* | **Operator** | stoppt Session, entfernt Agent; `worktree=delete` → `git worktree remove` (+ Branch-Schicksal) | 401 / 403 / 404 / 409 `last_po` |

**Vier Connector-/Backend-Auflagen, die die UI voraussetzt:**
1. **Connector besitzt Spawn + Config + Persona.** Die UI sendet nur die Felder; **Worktree-Anlage, CLAUDE.md-Platzierung (05-Doc §5 Auto-Discovery), Spawn** macht der Connector. Kein UI-seitiges Schreiben von Dateien/Prozessen.
2. **Persona/CLAUDE.md ist greenfield.** `AgentConfig` hat kein `claudeMd`. Pfad A: Modell bekommt `claudeMd`/`persona`-Feld; Pfad B: die UI liefert Persona-Text, den der Connector in die `CLAUDE.md` des Worktree-cwd schreibt (Auto-Discovery). → **Connector-Seam, PO/Backend entscheidet Pfad.**
3. **Worktree-Löschung ist greenfield.** Kein `deleteWorktree` heute. Der `worktree=delete`-Pfad braucht `git worktree remove` + Entscheid über den Agent-Branch (`agent/<name>`). **Default = behalten** (sicher); Löschen ist die bewusste, gewarnte Aktion.
4. **„Product-Lead" ist greenfield.** Der Rollen-Picker zeigt heute **PO/WORKER**. Eine dritte Rolle „Product-Lead" erfordert eine **`:core`-`Role`-Enum-Erweiterung** (Vertrag, beidseitig kompiliert) + ACL-Default-Klärung. → **flag**: bis dahin nur PO/WORKER anbieten.

> **Shared-Key-Sync-Hinweis (stehende Disziplin):** neue i18n-Keys als `:app:shared` compose.resources (DE+EN) — mit der Impl timen, sonst bricht ein Shared-Check. **Vertrags-DTOs** (POST/PUT/DELETE-Bodies) gehören in `:core`, beidseitig kompiliert.

---

## 3. Gemeinsames Fenster & Liste

- **Fenster** (`window.agentMgmt`) wie Comm/ACL/Settings montiert (`AgentShell.kt` windows + `windowContent`-`when`). **Operator-Aktion** → operator-gated.
- **Agentenliste** (`agentMgmt.list`): pro Agent eine Zeile `agentMgmt.item.<id>` mit **Name + Rolle (Text+Form, nicht nur Farbe)** + **Lifecycle-Status (Reuse CYP-73** `agent.<id>.status`) + Aktionen **Bearbeiten** (`…edit`) / **Entfernen** (`…remove`). Quelle = `GET /api/agents` (heute liefert die Liste, künftig auch der dynamische Fenster-Aufbau).
- **Hinzufügen-Button** (`agentMgmt.addButton`), operator-gated.
- **Kein Operator-Token:** Liste **read-only** sichtbar (Gate beobachtbar), alle Mutations-Controls disabled + Gate-Hinweis `agentMgmt.gateHint`.

---

## 4. CYP-86 — Agent hinzufügen

**Dialog `agentMgmt.add.dialog`** (Reuse AlertDialog-Muster der ACL-Lockout-Bestätigung):
| Feld | Element | Tag | Regel |
|---|---|---|---|
| id | TextField | `agentMgmt.add.id.input` | eindeutig; Kollision → Feldfehler (`409 agent_exists`) |
| Name | TextField | `agentMgmt.add.name.input` | Pflicht |
| Rolle | Picker (PO/WORKER) | `agentMgmt.add.role.picker` | **PO gesperrt, wenn schon ein PO existiert** (Guardrail, `409 po_already_exists`) |
| Persona/CLAUDE.md | mehrzeilig | `agentMgmt.add.persona.input` | optional; Connector schreibt sie in den Worktree (greenfield, §2) |
| `launch` | TextField (Default „claude") | `agentMgmt.add.launch.input` | optional |
| Worktree | TextField (Default = id) | `agentMgmt.add.worktree.input` | optional |
| Anlegen/Abbrechen | Buttons | `agentMgmt.add.confirm` / `agentMgmt.add.cancel` | confirm enabled nur Operator + valide Felder |

- **Disclosure:** Anlegen **spawnt nicht sofort** — der Agent erscheint gestoppt; **Start = CYP-73-Lifecycle** (kein zweiter Start-Mechanismus). Hinweis `agentMgmt.add.spawnHint`.
- **Fehler** `agentMgmt.add.error` (id-Kollision, PO-Konflikt, invalid).

## 5. CYP-87 — Agent entfernen (irreversibel)

**Bestätigungs-Dialog `agentMgmt.remove.dialog`** mit **expliziter Folgenanzeige** `agentMgmt.remove.consequences`:
- listet **was passiert:** „Session wird gestoppt" + (je Wahl) Worktree-Schicksal.
- **Worktree-Schicksal** `agentMgmt.remove.worktreeChoice` (Radio/Toggle): **Behalten** (Default, sicher) | **Löschen**.
- **Löschen → Warnung** `agentMgmt.remove.worktreeWarning`: „Nicht committete/nicht gepushte Arbeit in `<worktree>` geht **unwiderbringlich** verloren." (Destructive-Ton: error + Warn-Icon + Text).
- **Den einzigen PO** kann man nicht entfernen → `agentMgmt.remove.confirm` **disabled** + Hinweis (`409 last_po`); Regel sichtbar, nicht erst Server-Ablehnung.
- Buttons `agentMgmt.remove.confirm` (destruktiv, eindeutiges Verb „Agent entfernen" / bei Worktree-Löschung „Endgültig löschen") / `agentMgmt.remove.cancel`.
- **Fehler** `agentMgmt.remove.error`.

> **Disclosure (Kern):** Die Bestätigung darf **nicht** unterspezifizieren. „Behalten" ist der sichere Default; „Löschen" benennt **konkret** den Pfad + den Datenverlust. Stoppen der Session = **Reuse CYP-73-Stop** (kein Doppelmechanismus).

## 6. CYP-88 — Agent-Konfig ändern

**Dialog `agentMgmt.edit.dialog`** (Felder vorbefüllt aus `GET /api/agents` + Config):
| Feld | Tag | Regel |
|---|---|---|
| Rolle | `agentMgmt.edit.role.picker` | **PO gesperrt, wenn schon ein anderer PO existiert**; den **einzigen PO** nicht von PO wegrollen (`409 po_already_exists`/`last_po`) |
| Persona/CLAUDE.md | `agentMgmt.edit.persona.input` | greenfield-Pfad (§2) |
| `launch` | `agentMgmt.edit.launch.input` | — |
| Speichern/Abbrechen | `agentMgmt.edit.save` / `agentMgmt.edit.cancel` | save enabled nur Operator + geändert |

- **Restart-Transparenz** `agentMgmt.edit.effectHint`: „Gespeichert. Wirkt erst beim nächsten Start des Agenten – jetzt neu starten, damit die neue Konfiguration zieht." (amber/tertiary) **+ Reuse CYP-73-Restart** (`agent.<id>.restartBtn` / `agent_ctl_restart`).
- **id/Worktree** werden hier **nicht** geändert (identitäts-/pfadstiftend) — bewusst außen vor (sonst Worktree-Migration nötig). Hinweis im Dialog.
- **Fehler** `agentMgmt.edit.error`.

---

## 7. Reuse (statt Eigenbau)

| Element | Reuse-Quelle | Verifiziert |
|---|---|---|
| Fenster-Chrome / Mount | `WindowTestTags.window/content/titleBar`, `AgentShell.kt` | ✅ Code |
| Lifecycle Start/Stop/Restart + Status | **CYP-73** `agent.<id>.{status,startBtn,stopBtn,restartBtn}`, `agent_ctl_*`, `agent_status_*` | ✅ Code |
| Confirm-Dialog-Muster | ACL `AclMatrixTags` (`…confirm/.cancel`) + AlertDialog | ✅ Code |
| Operator-Gate-Muster | `editable = operatorToken != null` (ACL), `canControl` (CYP-73) | ✅ Code |
| Effekt-deferred-/Gate-Töne | S15 (`settings.effectDeferred`=tertiary/amber, `settings.gated`); info=secondary, Fehler=error | ✅ S15 |
| Rolle-Label | `agent_role_po` (vorhanden); **`agent_role_worker` neu** | ✅ Code |
| Restart-Aktivierung | **CYP-73** (kein zweiter Restart) | ✅ Code |

**Keine Dubletten:** kein eigener Start/Stop/Restart (Reuse CYP-73), kein eigener Spawn/Config-Pfad (Connector-Eigentum), kein eigener Operator-Banner-Stil.

---

## 8. Zustände & a11y (Querschnitt)

| Zustand | Verhalten | Disclosure |
|---|---|---|
| **Kein Operator-Token** | Liste read-only, Mutationen disabled + Gate-Hinweis | Gate beobachtbar, nicht versteckt |
| **Konfig gespeichert (CYP-88)** | amber „wirkt erst nach Neustart" + Restart-Reuse | gespeichert ≠ aktiv |
| **Entfernen bestätigt (CYP-87)** | Folgen explizit; Worktree-Default=behalten | irreversibler Pfad benannt + gewarnt |
| **Guardrail (PO/id)** | Control disabled + Erklärung | Regel sichtbar vor der Aktion |
| **Server-Ablehnung** | Fehlerzeile mit Code-Mapping | ehrlicher Fehler, kein stilles Schlucken |

- a11y: Picker/Buttons mit Rollen + Labels; destruktive Bestätigung mit klarer, nicht nur farblicher Kennzeichnung; RTL spiegelt automatisch (Felder im Lesefluss).

---

## 9. Offene Punkte (PO / Backend / Connector)

1. **Endpunkt-Vertrag bestätigen** (§2): Pfade/Codes (`agent_exists`, `po_already_exists`, `last_po`, `invalid_agent`); `DELETE …?worktree=keep|delete` als Form ok?
2. **Persona/CLAUDE.md-Pfad** (§2.2): Feld im Modell (`claudeMd`) **vs.** UI-Text → Connector schreibt `CLAUDE.md`? → bestimmt das Persona-Feld-Verhalten.
3. **Worktree-Löschung + Branch** (§2.3): `git worktree remove` + Schicksal von `agent/<name>` (löschen/behalten). Default=behalten bestätigen.
4. **„Product-Lead"** (§2.4): jetzt `:core`-`Role` erweitern (+ACL-Default) oder MVP nur PO/WORKER? (Empfehlung: MVP PO/WORKER, Product-Lead als Folge-Ticket mit ACL-Klärung.)
5. **Dynamischer Fenster-Aufbau:** Soll die `AgentShell`-windows-Liste künftig aus `GET /api/agents` kommen (statt statisch), damit ein hinzugefügter Agent ein Fenster bekommt? (S14 setzt das voraus; heute statisch.)
6. **Ein vs. drei Fenster:** ein Verwaltungs-Fenster mit drei Aktionen (so spezifiziert) — bestätigen.

**Gate (später):** Reviewer + Desktop-`runComposeUiTest` (Tags in `agent-management-tags.md`). Diese Spec ist **docs-only**; sie definiert den Vertrag **über** dem Connector, baut ihn nicht.
