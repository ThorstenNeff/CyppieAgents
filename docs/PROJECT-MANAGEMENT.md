# Projekt-Verwaltung & Projekt-Switcher (S13 · Epic CYP-78)

> Owner: UIUX-Designer · Epic **CYP-78** [MP] · Stand 2026-06-28 · Status: Vorschlag — wartet auf PO-Gegenlesen/Routing
> Begleit-Design-Files: `docs/design/project-management-{tokens.json,keys.md,tags.md}`
> Gegroundet gegen develop **`c2055bd`** (Reuse-Tags/-Keys/-Komponenten direkt am Code verifiziert).
> Stories: **CYP-91** Projekt anlegen / umbenennen / löschen · **CYP-92** Projekt-Switcher (neue Top-Level-Navigation).

---

## 0. Auftrag & Abgrenzung

Multi-Projekt ist vom Auftraggeber freigegeben. Diese Spec deckt die **UI** für zwei Stories:

- **CYP-91 — Projekt-Verwaltung:** ein Projekt **anlegen**, **umbenennen**, **löschen**. Löschen ist
  **irreversibel** und **kaskadiert** (Worktrees · Hub-Kanäle + ACL · Sessions · Event-Partition) →
  **Bestätigung + klare Folgenanzeige**, was genau gelöscht wird.
- **CYP-92 — Projekt-Switcher:** das aktive Projekt sichtbar machen und wechseln; **neue
  Top-Level-Navigation** über dem Fenster-Manager. Wechsel ist ein **Kontextwechsel (nicht
  destruktiv)**.

**Außerhalb dieser Spec (bewusst):**
- **Repo-Config (URL/Branch)** gehört zu **S15/CYP-84** (`docs/PROJECT-SETTINGS.md`) — pro Projekt,
  aber dort spezifiziert, nicht hier.
- **Agenten-Verwaltung** (Agenten innerhalb eines Projekts) = **S14/CYP-86/87/88**
  (`docs/AGENT-MANAGEMENT.md`).
- **Cross-Projekt-Sicht** (mehrere Projekte gleichzeitig / Vergleich) = **S17 [MP]**, separater Auftrag.

**Querschnitt-Leitplanken (als AC, vom PO bestätigt):** irreversibel → Bestätigung + Folgenanzeige ·
operator-gated wo Mutation · „Nachricht statt Shell" · Restart-Effekt-Transparenz ·
**Delete-Safety: aktives Projekt nicht löschbar (erst wechseln), letztes Projekt nicht löschbar.**

---

## 1. Mentales Modell

**Ein Projekt ist der Kontext, auf den die gesamte Oberfläche scoped ist.** Fenster (Agenten + System),
Kommunikation, ACL und Event-Log zeigen **ausschließlich** Daten des **aktiven** Projekts. Das ist keine
Kosmetik, sondern eine **Sicht-Grenze**: das Fundament (CYP-81, develop `c2055bd`) setzt sie fail-closed
durch — `ProjectScope.permits(entityPid, activePid)` lässt nur passende `projectId`s durch (blank/mismatch
→ deny), und `AclMatrix` dropt out-of-scope-Einträge.

Daraus folgen zwei Disclosure-Pflichten:

1. **Das aktive Projekt ist jederzeit unmissverständlich sichtbar.** Der Nutzer darf nie im Zweifel sein,
   welches Projekt er gerade bedient — sonst wirkt eine Aktion (Nachricht senden, ACL ändern, Agent
   löschen) womöglich im falschen Kontext.
2. **Wechseln ist Kontextwechsel, nicht Datenverlust.** Der Switcher lädt die Oberfläche für das gewählte
   Projekt neu (scoped Re-Fetch); das vorige Projekt bleibt unangetastet.

Die einzige **destruktive** Aktion ist das **Löschen eines Projekts** (CYP-91) — und genau dafür gilt das
volle Bestätigungs-/Folgenanzeige-/Guardrail-Regime aus §4.

---

## 2. CYP-92 — Projekt-Switcher (Top-Level-Navigation)

### 2.1 Platzierung

Der Switcher ist eine **eigene oberste Ebene ÜBER** dem Fenster-Host. Konkret im `AgentShell` (develop
`c2055bd`): heute ist `BoxWithConstraints { … WindowHost(…) }` die Wurzel. CYP-92 setzt **eine
Switcher-Leiste darüber** (Column: `[ProjectBar] / [BoxWithConstraints+WindowHost]`). Der gesamte
darunterliegende Shell-Aufbau — `managedAgents` (`GET /api/agents`), System-Fenster, Comm/ACL/Event-Log —
wird gegen die **aktive `projectId`** aufgelöst. Ein Wechsel re-resolved diesen Scope (siehe §2.3).

> **Warum top-level, nicht ein Fenster:** Ein Switcher als bloßes Fenster im Canvas wäre selbst Teil des
> Projekt-Scopes, den er steuert (Henne/Ei) und im Phone-Pager (CYP-54) eine von vielen Seiten. Als
> Leiste über dem Host bleibt er **immer sichtbar**, kontextunabhängig, und rahmt den gesamten Shell.

### 2.2 Inhalt der Leiste

- **Aktiv-Anzeige** (`projectSwitcher.active`): der Name des aktiven Projekts, **prominent** und mit
  einem **nicht-farblichen** Aktiv-Marker (Form/Glyph + Label `project_switcher_active` = „Aktives
  Projekt: %1$s"), nicht allein über Farbe (WCAG 1.4.1).
- **Wechsel-Menü** (`projectSwitcher.menu`): öffnet die Projektliste. Jeder Eintrag
  (`projectSwitcher.item.<id>`) ist klickbar = wechseln; der aktive Eintrag trägt zusätzlich
  `projectSwitcher.item.<id>.active` (Marker per Form, nicht nur Farbe). a11y:
  `a11y_project_switch_to` = „Zu Projekt %1$s wechseln".
- **Scope-Hinweis** (`projectSwitcher.scopeHint`, `project_switcher_scope_hint` = „Alle Fenster und Daten
  gehören zu diesem Projekt."): macht die Sicht-Grenze (§1) explizit — Disclosure gegen den
  „falscher-Kontext"-Fehler. Tonal neutral (HintTone.INFO).
- **Verwaltungs-Einstieg** (`projectSwitcher.manage`, `project_manage` = „Projekte verwalten"): öffnet die
  CYP-91-Verwaltung (§3). Der Switcher **hostet** den CRUD-Einstieg, statt ihn zu duplizieren.

### 2.3 Wechsel = Kontextwechsel (nicht destruktiv)

Ein Klick auf einen anderen Projekt-Eintrag setzt die aktive `projectId` und **rebaut den Scope**:
Fensterset (Agenten + System), Comm-Timeline, ACL-Matrix und Event-Log re-fetchen für die neue
`projectId`. **Nichts wird gelöscht.** Disclosure: ein kurzer, **neutraler** Hinweis
(`project_switch_hint` = „Wechsel lädt die Oberfläche für das gewählte Projekt neu – nichts wird
gelöscht.") trennt den Wechsel klar von der Lösch-Aktion.

- **Restart-Transparenz:** Ein Wechsel braucht **keinen** Neustart — er ist ein **Live-Re-Fetch**. (Der
  Restart-Effekt-Begriff aus S15/S14 betrifft Spawn-Zeit-Config je Agent, nicht den Projekt-Wechsel.) Das
  ist die ehrliche Botschaft: sofort wirksam, kein „erst nach Neustart".
- **Single-Projekt-Zustand:** Existiert nur das `default`-Projekt, zeigt die Leiste den Aktiv-Namen und
  bietet im Menü nur „Projekte verwalten" (kein Wechselziel). Kein Phantom-Eintrag.

---

## 3. CYP-91 — Projekt-Verwaltung (Verwaltungs-Fläche)

Aus dem Switcher-Einstieg (`projectSwitcher.manage`) öffnet die **Projekt-Verwaltung** — strukturell ein
**Geschwister** der Agenten-Verwaltung (`AGENT-MANAGEMENT.md` §3): eine Liste der Projekte plus
operator-gated **Anlegen / Umbenennen / Löschen**. **Operator-gated/fail-closed:** ohne Operator-Token ist
die Fläche read-only und ein sichtbarer Gate-Hinweis (`projectMgmt.gateHint`,
`project_mgmt_operator_required`) erklärt, warum.

> **Reuse:** Wie `AgentManagementPanel` (develop `c2055bd`) — Liste + `AlertDialog`-Dialoge, alle
> Hinweis-Zeilen über die **geteilte `TonedHint`-Komponente** (`HintTone{EFFECT_DEFERRED,GATED,INFO,
> ERROR}`, CYP-99-Konsolidierung). Kein eigenes Hint-Styling.

### 3.1 Projektliste

Eine Zeile je Projekt (`projectMgmt.row.<id>`): Projektname + Aktiv-Marker (`…row.<id>.active`, Form nicht
Farbe) + zwei Aktionen:

- **Umbenennen** (`…row.<id>.rename`) → Rename-Dialog (§3.3).
- **Löschen** (`…row.<id>.delete`) → Delete-Dialog (§4) — **deaktiviert** für das **aktive** und das
  **letzte** Projekt; der Grund ist **vor der Aktion sichtbar** (inline, §4.3).

Es gibt immer ≥ 1 Projekt (`default`), darum keinen Empty-State.

### 3.2 Anlegen (CYP-91)

Button `projectMgmt.add` (`project_add` = „Projekt anlegen") → Dialog `projectMgmt.addDialog`:
Namensfeld (`…addDialog.name`, Label `project_add_name_label`) + Bestätigen (`…addDialog.confirm`,
`project_add_confirm` = „Anlegen"). Validierung **vor** dem Senden:
- leer → `project_add_name_empty` (HintTone.ERROR);
- Name existiert → `project_add_name_exists` (HintTone.ERROR);
- Server-Fehler → `project_add_error`.

Ein neu angelegtes Projekt erscheint im Switcher-Menü; es ist **nicht** automatisch aktiv (kein
unangekündigter Kontextwechsel) — der Nutzer wechselt bewusst über den Switcher.

### 3.3 Umbenennen (CYP-91)

Aus der Zeile → Dialog `projectMgmt.renameDialog`: Titel `project_rename_title` („Projekt „%1$s"
umbenennen"), dasselbe Namensfeld + dieselbe Validierung (`project_add_name_label/_empty/_exists`
wiederverwendet), Bestätigen `project_rename_confirm`. **Nicht destruktiv, sofort wirksam** (kein
Restart-Effekt) — nur das Anzeige-Label ändert sich, die `projectId` bleibt stabil.

---

## 4. CYP-91 — Projekt löschen (irreversibel + kaskadierend)

Die **einzige destruktive Aktion**. Reuse des `RemoveDialog`-Musters der Agenten-Verwaltung
(`AgentManagementPanel` develop `c2055bd`), aber **projekt-WEIT**: error-gefärbter benannter Button,
explizite Folgenanzeige (INFO), Worktree-Schicksal-Radio, Datenverlust-Warnung (ERROR).

### 4.1 Folgenanzeige (was genau gelöscht wird)

Dialog `projectMgmt.deleteDialog`, Titel `project_delete_title` („Projekt „%1$s" löschen?"). Zeile
`…deleteDialog.consequences` (HintTone.INFO, `project_delete_consequences`) **benennt die Kategorien
explizit**:

> Unwiderruflich gelöscht: laufende Sessions werden gestoppt, alle Kanäle und Zugriffsrechte, der
> Nachrichtenverlauf und die Ereignis-Historie dieses Projekts.

**Mengenangaben (optional, advisory):** Liefert das Backend eine Zusammenfassung (z. B. „3 Agenten · 5
Kanäle · 1240 Ereignisse"), zeigt `…deleteDialog.counts` (`project_delete_consequences_counts` =
„Betroffen: %1$s") sie **zusätzlich**. **Honesty-Regel:** Sind Zahlen nicht verfügbar, werden **keine
erfunden** (kein „0", kein Phantom) — die Kategorienzeile steht für sich. Die Zahl ist **beobachtend/
best-effort**, die Kategorien sind die **garantierte** Aussage.

### 4.2 Worktree-Schicksal (Datenverlust-Warnung)

Wie bei der Agenten-Entfernung, aber für **alle Worktrees des Projekts** auf einmal. Radio
`…deleteDialog.worktreeChoice`:
- **Behalten** (`…worktreeKeep`, `project_delete_worktree_keep`) = **Default** (Arbeit auf Platte bleibt);
- **Worktrees aller Agenten löschen** (`…worktreeDelete`, `project_delete_worktree_delete`).

Das Radio mappt 1:1 auf das Backend-Flag **`deleteWorktrees`** (Default **`false`**) am DELETE — Reuse
des bereits gebauten **`WorktreeManager.deleteProject`** (strict-child-Guard). **Gepushte Branches
`agent/<…>` bleiben IMMER erhalten** (Remote-Arbeit überlebt jeden Lösch-Pfad); nur **lokale, nicht
committete/nicht gepushte** Arbeit ist betroffen, und nur im „alle löschen"-Pfad.

Im Lösch-Pfad erscheint `…deleteDialog.worktreeWarning` (HintTone.ERROR,
`project_delete_worktree_warning`): „Nicht committete/nicht gepushte Arbeit in den lokalen Worktrees
dieses Projekts geht verloren. Gepushte Branches (`agent/…`) bleiben erhalten." Die Warnung trennt damit
**garantiert-sicher** (gepusht) von **gefährdet** (lokal uncommitted) — Honesty statt pauschaler Drohung;
Datenverlust wird über **Ton + Text** getragen, nie nur über Farbe.

Der Bestätigungs-Button `…deleteDialog.confirm` ist **error-gefärbt** und **benennt die Aktion**:
`project_delete_confirm` = „Projekt endgültig löschen". Abbrechen `…deleteDialog.cancel`
(`project_cancel`).

### 4.3 Delete-Safety-Guardrails (PO-bestätigt, sichtbar VOR der Aktion)

Zwei harte Sperren, **vor** der Aktion sichtbar (Lösch-Button in der Zeile **deaktiviert** + inline
Begründung — nicht erst als Post-hoc-Ablehnung):

- **Aktives Projekt nicht löschbar** → `project_delete_active_blocked` = „Aktives Projekt – erst zu einem
  anderen wechseln, dann löschbar." (HintTone.INFO — eine **Anleitung**, kein Fehler.) Tag
  `projectMgmt.row.<id>.deleteBlocked`. Mappt auf den Backend-Code **`active_project_protected`** (409).
- **Letztes Projekt nicht löschbar** → `project_delete_last_blocked` = „Das letzte Projekt kann nicht
  gelöscht werden." (HintTone.INFO.) Gleicher Tag-Slot. Mappt auf **`last_project`** (409).

> **Tonaler Hinweis (bewusste Divergenz):** Die Agenten-Verwaltung tönt den „letzter PO"-Block ERROR;
> hier sind die Sperren **INFO-getönt**, weil sie eine erreichbare **Anleitung** sind („erst wechseln")
> bzw. eine neutrale Systemregel, kein Fehlversuch. Beide bleiben ohne Farbe lesbar (Glyph + Text).

> **Server bleibt Source of Truth (PO-entschieden; Reuse-Präzedenz CYP-49 ACL-PO-Lockout):** Die
> UI-Sperren sind **advisory** — das Backend lehnt aktives/letztes Projekt **ebenfalls** ab (fail-closed),
> damit ein direkter API-Aufruf nicht am UI-Guard vorbei löscht. Die Server-Codes sind bereits in `:core`
> gebaut (siehe §6/§7) — die UI mappt diese §4.3-Hinweise darauf.

### 4.4 Nach dem Löschen

Ein gelöschtes (nicht-aktives) Projekt verschwindet aus Liste und Switcher-Menü. Das aktive Projekt
bleibt unverändert (es war ja nicht löschbar). Kein automatischer Kontextwechsel.

---

## 5. Disclosure-Disziplin (Zusammenfassung)

| Prinzip | Umsetzung hier |
|---|---|
| **Irreversibel → Bestätigung + Folgen** | Delete-Dialog (§4): explizite Kategorien, Worktree-Warnung, benannter destruktiver Button. |
| **Garantiert ≠ advisory** | Kategorienzeile = garantierte Folgen; Mengen-Zahlen = best-effort/beobachtend, nie erfunden (§4.1). |
| **operator-gated / fail-closed** | Alle Mutationen (Create/Rename/Delete) operator-gated; ohne Token read-only + Gate-Hinweis. UI-Guards advisory, Server autoritativ (§6). |
| **Aktiver Kontext nie mehrdeutig** | Switcher-Aktiv-Anzeige immer sichtbar (§2.2); Scope-Hinweis macht die Sicht-Grenze explizit. |
| **Wechsel ≠ Löschen** | Neutraler Switch-Hinweis trennt den nicht-destruktiven Kontextwechsel vom Löschen (§2.3). |
| **Restart-Transparenz ehrlich** | Wechsel/Create/Rename sind **sofort** wirksam → kein „erst nach Neustart"-Versprechen; nichts wird grün/gelb fehl-tönt. |
| **Farbe nie alleiniger Träger (WCAG 1.4.1)** | Aktiv-Marker = Form/Glyph; alle Hinweise = TonedHint (Glyph + Text). |
| **„Nachricht statt Shell"** | Projekt-Verwaltung nutzt Formular-/Dialog-Eingaben; kein Shell-Prompt, keine freie Befehlsausführung. |

---

## 6. Datenpfad & Backend-Naht (Vertrag — PO-entschieden, gegen Backend abgeglichen)

Verifiziert gegen develop `c2055bd`: das **Fundament** steht (`ProjectScope`, `projectId` additiv auf
`Channel`/`AclEntry`/`Message`, `EventDraft.teamId`). Die folgende Naht ist vom PO **entschieden** und
teils im Backend **bereits gebaut**; diese Spec ist damit die akkurate Dev-Referenz.

1. **`Project`-DTO + `/api/projects`-CRUD:** `Project(id, name, createdAt?)`;
   `POST /api/projects` (create), `GET /api/projects` (list), `PUT /api/projects/{id}` (rename),
   `DELETE /api/projects/{id}` (cascade). **Alle Mutationen operator-gated** — Reuse `requireOperator()`
   (→ 403 `operator_required`, 401 ohne Token), exakt wie Settings/Agent-Mgmt/ACL.
2. **Aktiv-Projekt-Auflösung = server-seitiger Active-Pointer (PO-entschieden):** das aktive Projekt lebt
   im Backend-**`ProjectRegistry`** (Active-Pointer); Wechsel läuft über den **`SwitchActiveRequest`**-
   Endpunkt. **Alle scoped Endpunkte** (`/api/agents`, `/api/channels`, `/api/inbox`, `/api/events`, ACL)
   lösen die aktive `projectId` **serverseitig** auf — **kein `X-Project-Id`-Header** (würde die
   Scope-Grenze unterlaufen). **Folge:** das aktive Projekt ist Server-State und **überlebt UI-Reload/
   -Neustart** (§7.4).
3. **Cascade-Delete-Backend:** ein Projekt-Delete kaskadiert über
   - **Sessions** (`ConnectorSessions` agentId-keyed → stoppen),
   - **Kanäle + ACL** (`projectId`-filterbar → entfernen),
   - **Event-Partition** (teamId-gestempelt, query-gefiltert → Projekt-Events löschen),
   - **Worktrees** via bereits gebautem **`WorktreeManager.deleteProject`** (strict-child-Guard),
     gesteuert durch das Flag **`deleteWorktrees`** (Default **`false`** = Behalten). **Branches
     `agent/<…>` bleiben IMMER** — nur lokale, nicht gepushte Arbeit entfällt bei `deleteWorktrees=true`
     (§4.2).
4. **Fehler-Codes (PO-entschieden, an Backends `:core`-Codes angeglichen):** die UI mappt ihre Hinweise
   auf diese:
   - **Create:** `invalid_project_id` (400) / `project_exists` (409).
   - **Rename:** `invalid_project_id` (400) / `project_not_found` (404).
   - **Delete (Prüf-Reihenfolge):** `project_not_found` (404) → **`last_project`** (409) →
     **`active_project_protected`** (409). → UI: `last_project` ⇒ `project_delete_last_blocked`,
     `active_project_protected` ⇒ `project_delete_active_blocked` (§4.3); generischer Rest ⇒
     `project_*_error`.
   - **Gate** (alle Mutationen): `operator_required` (403 mit Token-Mismatch, 401 ohne).
5. **Folgen-Mengen = Fast-Follow (PO-entschieden):** **S13 zeigt nur die Kategorienzeile.** Der
   advisory-Counts-Slot (`project_delete_consequences_counts`) bleibt wie spezifiziert stehen (Honesty-
   Regel: nie erfunden), die Counts liefert ein kleiner Fast-Follow nach (§4.1).
6. **Delete-Safety server-seitig (erfüllt):** aktives + letztes Projekt werden serverseitig abgelehnt
   (`active_project_protected` / `last_project`, fail-closed) — UI-Sperren sind advisory (Präzedenz CYP-49).

---

## 7. Entscheidungen (PO-entschieden — §7.1–§7.5 geschlossen)

1. **Aktiv-Transport:** server-seitiger Active-Pointer (`ProjectRegistry`) + `SwitchActiveRequest`;
   scoped Endpunkte lösen serverseitig auf; **kein `X-Project-Id`-Header**. (→ §6.2)
2. **Folgen-Mengen:** S13 = nur Kategorienzeile; advisory-Counts-Slot bleibt, Counts als Fast-Follow.
   (→ §6.5)
3. **Fehler-Codes:** an `:core` angeglichen — Create `invalid_project_id`/`project_exists`; Rename
   `invalid_project_id`/`project_not_found`; Delete `project_not_found`→`last_project`→
   `active_project_protected`. (→ §6.4, §4.3)
4. **Wechsel-Persistenz:** Server-State → aktives Projekt überlebt Reload (Folge von §7.1). (→ §2.3)
5. **Default-Projekt:** **keine** Sonderrolle — `default` ist umbenennbar (id bleibt `default`) und
   löschbar, **sofern nicht aktiv/letztes**; nur die universellen Guards greifen. (→ §3, §4)

---

## 8. Self-Validation

- **Artefakte:** diese Spec + `project-management-{tokens.json,keys.md,tags.md}` (Muster CYP-17).
- **Reuse gegen Code (develop `c2055bd`) verifiziert:** `WindowTestTags.window/content/titleBar`,
  `ui/TonedHint.kt` + `HintTone{EFFECT_DEFERRED,GATED,INFO,ERROR}`, `AgentManagementPanel`-RemoveDialog-
  Muster (error-Button + INFO-Folgen + Worktree-Fate-Radio + ERROR-Datenverlust), `AgentShell`-Wurzel
  (`BoxWithConstraints`/`WindowHost`) für die Switcher-Platzierung, `requireOperator` operator-gate.
- **Keys:** `project_*` greenfield (0 Kollision gegen `strings.xml` @ `c2055bd`); DE+EN-Parität Pflicht;
  surface-lokal (kein Cross-Surface-Reuse von `agent_`/`acl_` — bewusst, Drift-Vermeidung).
- **Tags:** Areas `projectSwitcher` (CYP-92) + `projectMgmt` (CYP-91), Test-Contract v0.5 §2-konform.
- **Backend-Naht §6 = PO-entschiedener Vertrag** (Active-Pointer `ProjectRegistry`/`SwitchActiveRequest`,
  kein `X-Project-Id`; `WorktreeManager.deleteProject` + `deleteWorktrees`-Flag Default false, Branches
  `agent/<…>` bleiben; `:core`-Codes `invalid_project_id`/`project_exists`/`project_not_found`/
  `last_project`/`active_project_protected`; Counts = Fast-Follow; `default` ohne Sonderrolle). §7.1–§7.5
  geschlossen.
