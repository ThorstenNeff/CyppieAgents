# Onboarding: Inline-Feldhilfe + Leerzustand im „Agent hinzufügen"-Formular — UX/UI-Design-Spec (CYP-228)

> Owner: UIUX-Designer · Story **CYP-228** (Medium, S; Erst-Nutzer-Polish über der Agenten-Verwaltung CYP-86/87/88) · Stand: 2026-07-05
> Begleitdateien: `agent-add-onboarding-keys.md`, `-tags.md`, `-tokens.json`.
> **Grounded gg. develop `6cfff22`:** `agentmgmt/AgentManagementPanel.kt` (`AddDialog`/`LabeledField`/`RolePicker`, `AgentManagementPanel`),
> `AgentManagementViewModel.kt` (`AddForm`), `model/AgentMgmtGuard.kt` (Validierung), `agentmgmt/AgentMgmtTags.kt`, `values/strings.xml`.
> **Rein UI + Strings — keine Model-/Endpoint-/Validierungs-Änderung.** Auslöser: Erst-Nutzer (Auftraggeber) stand vor dem Formular
> und wusste nicht, was in die Felder gehört; die Felder haben nur `label`, keine Inline-Hilfe, und die leere Liste hat keinen Hinweis.

---

## 0. Problem & Leitidee

Das Add-Formular (`AddDialog`) zeigt je Feld **nur ein `label`** (`OutlinedTextField(label=…)`), **kein** `placeholder`/`supportingText`
→ Bedeutung, Pflicht-vs-optional und Auto-Vergebenes sind unsichtbar. Bei **leerer Agentenliste** (`state.agents.isEmpty()`) rendert
`AgentManagementPanel` nur eine leere `LIST`-Column — **kein** Onboarding. Zwei ergänzende Bausteine, beide reiner Reuse:

- **A — Inline-Feldhilfe:** dauerhafter `supportingText` (Bedeutung + Pflicht/optional) + Beispiel-`placeholder` je Feld.
- **B — Leerzustand-Onboarding:** ein kleiner Empty-State-Block bei leerer Liste, CTA = der **bestehende** Add-Button.

**Ehrlichkeits-Kern (mein Lane, §5):** die Hilfe muss (1) **Pflicht vs. optional** klar machen, (2) das **Auto-Vergebene** benennen
(Token/Branch/Kanal — kein Feld darf ein Token-Eingabefeld vortäuschen), (3) den bestehenden **„Anlegen ≠ Start"**-Hinweis
(`agent_add_spawn_hint`) bewahren. Kein Hint verspricht eine Wirkung, die das Anlegen nicht hat.

---

## 1. Reuse-Bestand (gg. `6cfff22` verifiziert)

| Baustein | Datei | Reuse in CYP-228 |
|---|---|---|
| `LabeledField(value,onChange,label,tag,…)` | `AgentManagementPanel.kt` | + optionale Params `hint`(→`supportingText`) / `placeholder`(→`placeholder`) |
| `OutlinedTextField` | dito | `supportingText`/`placeholder` sind native Slots (kein neues Widget) |
| `TonedHint(INFO)` + `agent_add_spawn_hint` | dito | Muster für die Auto-Vergeben-Note; „Anlegen≠Start" bleibt |
| `RolePicker` | dito | eine `supportingText`-Zeile darunter (Worker/PO-Bedeutung) |
| `AgentMgmtTags.ADD_BUTTON` / `GATE_HINT` / `LIST` | `AgentMgmtTags.kt` | Empty-State steht über `LIST`, CTA = bestehender `ADD_BUTTON`, Gate reused |
| `AgentMgmtTags.ADD_*_INPUT` | dito | die Hints hängen an den **bestehenden** Feld-Tags — **keine** neuen Feld-Tags |

**Anti-Divergenz:** kein zweiter Add-Button, kein neues Feld-Widget — nur `LabeledField` erweitert + ein Empty-State-Block.

---

## 2. A) Inline-Feldhilfe (`supportingText` dauerhaft + `placeholder` Beispiel)

`LabeledField` bekommt `hint: String? = null` (→ `OutlinedTextField.supportingText`) und `placeholder: String? = null`
(→ `OutlinedTextField.placeholder`). Beide sind Teil der Feld-Semantik (a11y: der Screenreader liest `supportingText` mit).

**Projekt-Scope-Note (am KOPF des Dialogs — Fold aus dem Multi-Projekt-Support-Gap):** eine ruhige `TonedHint(INFO)` **vor** den
Feldern (Tag `agentMgmt.add.projectNote`): `agent_add_project_scope_note` — „Wird im aktiven Projekt **%1$s** angelegt." (`%1$s` =
Anzeigename des aktiven Projekts). Löst die häufige Modell-Verwirrung („aus bestehenden Terminal-Fenstern übernehmen?") **an der
Quelle**: ein Agent gehört **immer zum aktiven Projekt** (Fenster = projekt-scoped Agenten; kein projekt-übergreifendes Anlegen).
**Ehrlichkeits-Anker:** nennt das aktive Projekt **wahr** (`%1$s` = realer Name); kein Hinweis impliziert eine projekt-übergreifende
Wirkung.

| Feld (Tag) | `supportingText` (Key) | `placeholder` (Key) |
|---|---|---|
| **Agent-ID** (`agentMgmt.add.id.input`) | `agent_add_id_hint` — „Pflicht · unveränderlich. Nur a–z, 0–9, `-`, `_`. Wird zu Ordner, Branch & Kanal." | `agent_add_id_placeholder` — „frontend" |
| **Name** (`…name.input`) | `agent_add_name_hint` — „Pflicht · Anzeigename, jederzeit änderbar." | `agent_add_name_placeholder` — „Frontend-Entwickler" |
| **Rolle** (`…role.picker`) | `agent_add_role_hint` — „Pflicht · Worker = ausführender Agent · PO = Koordinator (genau einer)." | — (Radios) |
| **Persona/CLAUDE.md** (`…persona.input`) | `agent_add_persona_hint` — „Optional · Rolle & Regeln des Agenten (wird zur CLAUDE.md). Leer = später ergänzbar." | `agent_add_persona_placeholder` — „Du bist der Frontend-Entwickler. Aufgaben, DoD, Konventionen …" |
| **Startkommando** (`…launch.input`) | `agent_add_launch_hint` — „Optional · Befehl, der die Session startet. Leer = Projekt-Standard." | `agent_add_launch_placeholder` — „claude" |
| **Worktree-Ordner** (`…worktree.input`) | `agent_add_worktree_hint` — „Optional · eigener Ordnername. Leer = automatisch aus der ID." | — (leer = aus ID) |

**Auto-Vergeben-Note** (eine ruhige `TonedHint(INFO)` unter den Feldern, Muster `agent_add_spawn_hint`; Tag `agentMgmt.add.autoNote`):
`agent_add_autofields_note` — „Token, Git-Branch und Hub-Kanal vergibt das System automatisch. Farbe & Avatar setzt du danach über
das ⋮-Panel des Agenten." Der bestehende `agent_add_spawn_hint` („Angelegt. Der Agent startet noch nicht …") **bleibt** unverändert.

> Copy-Verweis: die ID-Regel (`^[a-zA-Z0-9_-]+$`, nicht `operator`, unique) stammt aus `AgentMgmtGuard.validateAdd`; die
> Blank→Default-Zusagen (Launch/Worktree) aus `confirmAdd` (`ifBlank { null }`) + der Boot-Ableitung. Nur wahre Zusagen.

---

## 3. B) Leerzustand-Onboarding (`state.agents.isEmpty()`)

In `AgentManagementPanel`, wenn `state.agents.isEmpty()`: ein Empty-State-Block **statt** der leeren `LIST`-Column (Tag
`agentMgmt.empty`):

- **Titel** `agent_empty_title` — „Noch keine Agenten".
- **Body** `agent_empty_body` — „Lege deinen ersten Agenten an — ID und Name genügen. Er startet erst über die Lifecycle-Steuerung."
- **CTA:** der **bestehende** `ADD_BUTTON` (`agentMgmt.addButton`) — **kein** zweiter Button; der Empty-State steht darüber/daneben
  und verweist darauf.
- **Operator-Gate bleibt:** Nicht-Operator sieht den Empty-State **plus** den bestehenden `GATE_HINT`, der Add-Button ist disabled
  → **kein toter CTA** (der Empty-State fordert nicht zu etwas auf, das der Nutzer nicht darf).

> **Ehrlichkeits-Anker:** der Empty-State wiederholt „startet erst über die Lifecycle-Steuerung" (kein Vortäuschen, dass Hinzufügen =
> laufender Agent) und respektiert das Operator-Gate.

---

## 4. Modell / Verhalten (unverändert)

**Keine** Änderung an `NewAgentSpec`/`AgentEdit`/`AgentMgmtGuard`/den Routes. Ausschließlich: `LabeledField`-Signatur (+2 optionale
Params), die Add-Dialog-Aufrufe (Hints/Placeholder durchreichen), die Auto-Note, der Empty-State-Zweig. Pflicht-Logik
(`canConfirmAdd`: id+name non-blank, kein zweiter PO) bleibt exakt — die Hints **beschreiben** sie nur.

---

## 5. Disclosure-Ehrlichkeit — Invarianten (Abnahme-Checkliste UX-QA)

1. **Pflicht vs. optional sichtbar:** jedes Feld nennt im `supportingText` „Pflicht"/„Optional" konsistent mit `canConfirmAdd`
   (nur ID+Name Pflicht).
2. **Auto-Vergebenes benannt, nichts vorgetäuscht:** die Auto-Note nennt Token/Branch/Kanal als system-vergeben; **kein** Feld
   suggeriert eine Token-Eingabe. Farbe/Avatar korrekt aufs ⋮-Panel verwiesen.
3. **„Anlegen ≠ Start" bleibt:** `agent_add_spawn_hint` unverändert präsent; der Empty-State wiederholt es.
4. **Nur wahre Zusagen:** Blank-Defaults (Launch=Projekt-Standard, Worktree=aus ID) entsprechen `confirmAdd`/Boot; ID-Regel
   entspricht `SAFE_ID`.
5. **Leerzustand statt leerer Fläche:** bei `agents.isEmpty()` erscheint der Onboarding-Block; CTA = bestehender Add-Button.
6. **Operator-Gate intakt:** Nicht-Operator sieht Empty-State + `GATE_HINT`, Add disabled — kein toter CTA.
7. **a11y + Parität:** `supportingText`/`placeholder`/Empty-State werden vom Screenreader gelesen; DE+EN 1:1.

---

## 6. Counts / Reuse (Selbst-Validierung → Begleitdateien)

- **Neue i18n-Keys:** **14** (6 `…_hint` + 4 `…_placeholder` + `agent_add_autofields_note` + `agent_add_project_scope_note` [1 Arg]
  + `agent_empty_title` + `agent_empty_body`), DE+EN-Parität — siehe `agent-add-onboarding-keys.md`.
- **Neue Tags:** **`agentMgmt.empty`** (+ optional `…empty.title`/`…empty.body`) + `agentMgmt.add.autoNote` +
  `agentMgmt.add.projectNote`; Feld-Hints **ohne** neue Tags (an den bestehenden `agentMgmt.add.*.input`) — siehe
  `agent-add-onboarding-tags.md`.
- **Neue Tokens/Farben:** **0** — siehe `agent-add-onboarding-tokens.json`.
- **Größe: S (1–2 Tage)** — reine UI + Strings, Reuse `LabeledField`/`TonedHint`, keine Model-/Endpoint-Änderung.

*Nur Design/Spec/Verifikation, keine Implementierung. Key/Tag-Landing mit dem CYP-228-Impl-Slice timen (Shared-Key/Tag-Drift —
CYP-7, PO koordiniert). UX-QA nach Dev-Impl durch UIUX (Invarianten §5).*
