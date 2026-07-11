# CYP-450 (P2-b) — Agenten-Verwaltung im DOM: hinzufügen · entfernen · Konfig ändern

> Owner: UIUX-Designer · Ticket **CYP-450** (Epic **CYP-430** Voller-Ersatz-Cutover, P2-b) · Stand 2026-07-11
> Basis `origin/develop` (aktuell) · `09-UI-Funktionskatalog` §2 · Docs-only → **Dev5-Referenz**.
> **Port der Compose-Quelle, kein Neuentwurf.** **0 neue Keys/Tags** (CYP-86/87/88 existieren). **Nichts gebaut.**
>
> **Quelle:** `agentmgmt/AgentManagementPanel.kt` · `AgentManagementViewModel.kt` · `AgentMgmtTags.kt`; Keys/Tags
> `docs/design/agent-management-keys.md` + `agent-management-tags.md` (bestehender Vertrag, CYP-86/87/88).
> **Verlinkt P2-a (CYP-431 Lifecycle):** *anlegen ≠ starten* (Start über die Lifecycle-Controls); *ändern* wirkt
> erst beim **Neustart** (reuse `restartBtn`); *entfernen* **stoppt** die Session.

---

## 0. Die zwei Leitachsen dieser Fläche

1. **Irreversibel = Bestätigung + klare Folgenanzeige** (09 Querschnitt). *Entfernen* ist der destruktive Fall:
   **was genau passiert** wird gezeigt, bevor bestätigt wird, und **Datenverlust** (Worktree löschen) explizit gewarnt.
2. **Der Server ist autoritativ, die UI ist Anzeige (nicht-optimistisch).** Eindeutigkeits-/Guardrail-Regeln
   (ID-Kollision, **nur ein PO**, **letzter PO nicht entfernbar**) sind **Server**-Ablehnungen; die UI **rät** sie
   nicht vorweg, sie **zeigt** die ehrliche Ablehnung. Die Liste spiegelt den Server-Zustand.

---

## 1. Anatomie (Mirror `AgentManagementPanel`) — Area `agentMgmt`

```
┌ agentMgmt.panel ───────────────────────────────────────────────┐
│  «Agenten-Verwaltung»                       [ agentMgmt.addButton ] │
│  agentMgmt.gateHint  (Nicht-Operator: „Nur mit Operator-Token…")   │
│  agentMgmt.list                                                    │
│   ├ agentMgmt.item.<id> : status(reuse agent.<id>.status) · Name · Rolle · [edit] [remove] │
│   └ …                                                              │
└─────────────────────────────────────────────────────────────────┘
```

- **Liste** = je Agent eine Zeile (`agentMgmt.item.<id>`) mit **Lifecycle-Status wiederverwendet** (`agent.<id>.status`
  — der P2-a-Statuspunkt, kein Zweit-Widget) + Name + Rolle + Zeilen-Aktionen `…​.edit` / `…​.remove`.
- **Operator-Gate, fail-closed:** ohne Operator sind `addButton`/`edit`/`remove` **present-but-disabled**
  (`aria-disabled`, **kein** Fake, CYP-317) + `agentMgmt.gateHint` (`agent_mgmt_operator_required` /
  `workspace_operator_only`). Die Liste bleibt **sichtbar** (Anzeige ist ungated; ändern ist gated).

---

## 2. Hinzufügen (CYP-86) — *anlegen ≠ starten*

Dialog `agentMgmt.add.dialog`: Eingaben `id` · `name` · **`role.picker`** (PO / WORKER / **Product-Lead** —
`agent_role_*`) · `persona` (CLAUDE.md) · `launch` · `worktree`. Aktion `add.confirm` („Anlegen"), `add.cancel`.

**Ehrlichkeits-Regeln:**
- **Angelegt ≠ gestartet:** nach Erfolg zeigt `add.spawnHint` „Angelegt. Der Agent startet noch nicht — über die
  Lifecycle-Steuerung starten." → der neue Agent erscheint in der Liste als **nicht-laufend** (STOPPED/UNKNOWN,
  P2-a-Statuspunkt); Start läuft über die P2-a-Controls. **Nie** einen Auto-Start vortäuschen.
- **Server-autoritative Eindeutigkeit:** ID-Kollision → `add.error` = `agent_add_id_exists`; zweiter PO →
  `agent_add_po_exists` „nur ein PO pro Projekt". **Die UI prüft das nicht clientseitig vorweg** (sie könnte über
  einen Race lügen) — sie zeigt die Server-Ablehnung.
- `agent_add_spawn_hint`-Ton = neutral/info (kein Erfolgs-Grün-Overload — es ist ein Zwischenstand, kein „läuft").

---

## 3. Entfernen (CYP-87) — **irreversibel, die Ehrlichkeits-Mitte**

Bestätigungs-Dialog `agentMgmt.remove.dialog` (`role="alertdialog"`), **nie** ein stiller Sofort-Remove:

1. **Titel:** `agent_remove_title` „Agent „%1$s" entfernen?"
2. **Folgenanzeige** `remove.consequences` = `agent_remove_consequences` „Die laufende Session wird gestoppt." —
   **was genau passiert**, vor der Bestätigung (09 Querschnitt).
3. **Worktree-Wahl** `remove.worktreeChoice`: **behalten** (`agent_remove_worktree_keep`) **oder** **löschen**
   (`agent_remove_worktree_delete`). Default = **behalten** (die zerstörungsfreie Wahl zuerst).
4. **Datenverlust-Warnung** `remove.worktreeWarning` (nur bei „löschen"): `agent_remove_worktree_warning`
   „Nicht committete/nicht gepushte Arbeit in „%1$s" geht unwiderbringlich verloren." — der Offenlegungssatz,
   **darf umbrechen, nie `ellipsis`**.
5. **Destruktive Bestätigung** `remove.confirm`: Wortlaut je Worktree-Wahl — „Agent entfernen"
   (`agent_remove_confirm`) bzw. „Endgültig löschen" (`agent_remove_confirm_delete`); **error-getönt**, `cancel`
   ist Default/erstfokussiert.
6. **Letzter-PO-Leitplanke (server-erzwungen):** ein Versuch, den **einzigen** PO zu entfernen → `remove.error`
   `agent_remove_last_po` „Der einzige PO kann nicht entfernt werden — Hub-and-Spoke bräche." **Advisory in der UI,
   der Server ist der Guard** — dasselbe Muster wie die ACL-PO-Lockout-Leitplanke (CYP-49). Die UI erfindet keinen
   Erfolg; die Ablehnung ist die ehrliche Antwort.

---

## 4. Konfig ändern (CYP-88) — *wirkt erst beim Neustart*

Dialog `agentMgmt.edit.dialog`: **`role.picker`** · `persona` · `launch`. **ID + Worktree sind gesperrt**
(`agent_edit_id_locked_hint` „ID und Worktree sind fest und hier nicht änderbar.") — read-only angezeigt, nicht
editierbar.

- **Effect-Hint** `edit.effectHint` = `agent_edit_effect_hint` „Gespeichert. Wirkt erst beim nächsten Start —
  jetzt neu starten, damit die neue Konfiguration zieht." **AMBER (`EFFECT_DEFERRED`), nie Erfolgs-Grün**
  („gespeichert ≠ aktiv") — **zeigt auf den P2-a-`restartBtn`** (reuse `agent_ctl_restart`, **kein** eigener
  Restart-Control hier). Exakt das CYP-433-API-Key-Muster.
- **Zwei getrennte PO-Fälle (CYP-101, nie zusammenlegen):**
  - `agent_edit_po_exists` = Rolle PO **bei einem anderen** Agenten belegt (kein zweiter PO).
  - `agent_edit_last_po` = der **einzige** PO will die PO-Rolle **abgeben** (Guardrail `editWouldDropLastPo`,
    Hub-and-Spoke bräche).
  Der Impl darf **nicht** beides auf `…po_exists` mappen (die falsche Botschaft fürs Abgeben) — getrennte Keys,
  getrennte Botschaften.

---

## 5. Operator-Gate & Server-Autorität (nicht-optimistisch) — wortgleich zu den anderen P2-Flächen

- **Present-but-disabled** ohne Operator (Add/Edit/Remove `aria-disabled`, Liste sichtbar) — **kein Fake** (CYP-317).
- **Nicht-optimistisch:** Anlegen/Ändern/Entfernen kippen die Liste **erst nach Server-Bestätigung**; ein Reject
  (id_exists / po_exists / last_po / generisch) landet auf der jeweiligen `…​.error`-Zeile, **nie** als
  vorgetäuschter Erfolg. Die Guardrails (ein-PO, letzter-PO) sind **Server**-Regeln — die UI zeigt sie, prüft sie
  nicht als alleinige Instanz.

---

## 6. Querverweise (Reuse, keine Duplikate)

- **P2-a Lifecycle (CYP-431):** Status in der Zeile = `agent.<id>.status`; Start des neu-angelegten Agenten +
  Neustart-nach-Edit über die Lifecycle-Controls (`restartBtn`). Diese Fläche **steuert den Lebenszyklus nicht
  selbst** — sie verwaltet die **Konfiguration** und verweist auf die Lifecycle-Controls.
- **CYP-433 API-Key:** identisches „gespeichert ≠ aktiv → Neustart"-Effect-Hint-Muster (amber, zeigt auf `restartBtn`).
- **ACL PO-Lockout (CYP-49):** identisches „advisory in der UI, Server ist der Guard"-Muster für die
  Letzter-PO-Leitplanke.

---

## 7. Abnahme-Zähne (diskriminierend) — je mit der falschen Impl, die er ablehnt

1. **Anlegen ≠ starten.** Nach Add zeigt `spawnHint`; der neue Agent ist **nicht** RUNNING. **Mutation:**
   Auto-Start / „läuft"-Anzeige direkt nach Anlegen ⇒ rot.
2. **Entfernen erfordert Bestätigung + Folgenanzeige.** Kein Sofort-Remove; `consequences` präsent; bei „löschen"
   ist `worktreeWarning` präsent. **Mutation:** direkter Remove ohne Dialog / ohne Datenverlust-Warnung bei Löschen ⇒ rot.
3. **Worktree-Default = behalten.** Die zerstörungsfreie Wahl ist vorausgewählt. **Mutation:** „löschen" default ⇒ rot.
4. **Letzter PO nicht entfernbar (Server-Guard).** Versuch → `agent_remove_last_po`, **kein** durchgeführter Remove.
   **Mutation:** letzter PO entfernt / Erfolg vorgetäuscht ⇒ rot.
5. **Edit-Effect-Hint amber, nie grün; zeigt auf Restart.** **Mutation:** grüner „aktiv"-Ton / eigener Restart-Button hier ⇒ rot.
6. **ID+Worktree gesperrt.** Im Edit read-only. **Mutation:** ID/Worktree editierbar ⇒ rot.
7. **PO-Fälle getrennt.** `edit_po_exists` (belegt) ≠ `edit_last_po` (abgeben). **Mutation:** beides auf denselben Text ⇒ rot.
8. **Operator-Gate present-but-disabled, kein Fake.** Nicht-Operator: Aktionen `aria-disabled`, Liste sichtbar.
   **Mutation:** aktives Fake-Control **oder** Aktionen entfernt ⇒ rot.
9. **Server-autoritativ.** Eindeutigkeit (id/PO) als Server-Reject, nicht client-vorweggeraten. **Mutation:**
   client-seitige „ID frei?"-Anzeige ohne Server ⇒ rot (Race-Lüge).

---

## 8. Keys & Tags — alles bestehend (0 neu)

**Keys (CYP-86/87/88, Reuse):** `agent_mgmt_*`, `agent_add_*`, `agent_remove_*`, `agent_edit_*`, `agent_role_*`,
`a11y_agent_add_*`; + Reuse `agent_ctl_restart`, `agent_status_*` (Listenzeile), `workspace_operator_only`.
**Tags (Reuse):** Area `agentMgmt` (`panel`/`list`/`item.<id>[.edit|.remove]`/`addButton`/`gateHint`/`add.*`/
`remove.*`/`edit.*`) → im DOM als `data-testid`, punktfrei-Schema unverändert; + Reuse `agent.<id>.status`/
`startBtn`/`stopBtn`/`restartBtn` (Lifecycle in der Zeile). **Kein neuer Key aus dem Medienwechsel.**

## 9. DOM/A11y

- Remove-Dialog = `role="alertdialog"`, `cancel` erstfokussiert, destruktiver Confirm error-getönt + klar benannt.
- Worktree-Wahl = `radiogroup` (behalten/löschen); Warnung als `role="alert"` bei „löschen".
- Rollen-Picker = `radiogroup`. Effect-Hint/Fehler = `role="status"`/`role="alert"`. Kein `ellipsis` auf
  Offenlegungs-/Warn-/Fehlertext. Zielgröße ≥ 24px. Farbe nie alleiniger Träger. Token-Ebene (CYP-423) vorausgesetzt.

**Nichts gebaut — Spec + Dev5-Referenz. Verwaltet Konfiguration; den Lebenszyklus steuert P2-a. Irreversibles wird
gezeigt bevor es passiert; der Server ist der Guard, die UI die ehrliche Anzeige.**
