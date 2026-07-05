# testTag-Schema — Agent-Add-Onboarding (CYP-228)

> Owner: UIUX-Designer · Story **CYP-228** · Stand: 2026-07-05 · Status: Vorschlag
> **Schema (Test-Contract v0.5 §2):** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`, **prefixless**, camelCase,
> Segmentwerte `[A-Za-z0-9-]+` (keine Punkte — Trenner). Erweitert die bestehende Area **`agentMgmt`** — verifiziert gg.
> `AgentMgmtTags.kt` @ `6cfff22`.
> **Vertrag Dev/QA (CYP-7):** API, nicht still umbenennen; koordiniert über den PO.

---

## 1. Neue Tags (2) — in `AgentMgmtTags`

| Element | testTag | Zweck |
|---|---|---|
| Leerzustand-Block | `agentMgmt.empty` | Onboarding bei `agents.isEmpty()` (Titel + Body); CTA = bestehender `ADD_BUTTON` |
| Auto-Vergeben-Note | `agentMgmt.add.autoNote` | INFO-Zeile „Token/Branch/Kanal system-vergeben" im Add-Dialog |
| Projekt-Scope-Note | `agentMgmt.add.projectNote` | INFO-Zeile am Dialog-Kopf „Wird im aktiven Projekt %1$s angelegt" (Fold Support-Gap) |

> Optional (falls QA Titel/Body getrennt adressieren will): `agentMgmt.empty.title` / `agentMgmt.empty.body` — nicht zwingend,
> wenn die Container-Text-Assertion reicht.

---

## 2. Reuse — KEINE neuen Feld-Tags

Die Inline-Hints (`supportingText`) und Placeholder hängen an den **bestehenden** Add-Feld-Tags — kein neuer Tag nötig:

| Feld | bestehender Tag (reuse) |
|---|---|
| Agent-ID | `agentMgmt.add.id.input` |
| Name | `agentMgmt.add.name.input` |
| Rolle | `agentMgmt.add.role.picker` |
| Persona | `agentMgmt.add.persona.input` |
| Startkommando | `agentMgmt.add.launch.input` |
| Worktree | `agentMgmt.add.worktree.input` |
| CTA (Empty-State) | `agentMgmt.addButton` (bestehend, **kein** zweiter Button) |
| Operator-Gate | `agentMgmt.gateHint` (bestehend) |

---

## 3. Test-relevante Disclosure-Anker (für QA/CYP-7)

- **Leerzustand statt leerer Fläche:** bei `agents.isEmpty()` ist `agentMgmt.empty` präsent; CTA verweist auf den **bestehenden**
  `agentMgmt.addButton` (kein zweiter Button-Tag).
- **Operator-Gate im Empty-State:** Nicht-Operator → `agentMgmt.empty` **und** `agentMgmt.gateHint` präsent, `addButton` disabled
  (assertIsNotEnabled) — kein toter CTA.
- **Auto-Note ehrlich:** `agentMgmt.add.autoNote` nennt Token/Branch/Kanal als system-vergeben; „Anlegen≠Start"
  (`agentMgmt.add.spawnHint`) bleibt.
- **Feld-Hints:** an den bestehenden `agentMgmt.add.*.input`-Nodes assertierbar (supportingText-Semantik).

---

## 4. Hand-off + Zähl-/Validierungs-Block
- Neue Konstanten in `AgentMgmtTags` (`:app:shared`), geteilt mit Tester (CYP-7); Änderungen über den PO.
- **Neue Tags gesamt: 3** (`agentMgmt.empty`, `agentMgmt.add.autoNote`, `agentMgmt.add.projectNote`); optional +2
  (`…empty.title`/`…empty.body`).
- **0 Kollision** gg. `AgentMgmtTags.kt` @ `6cfff22` (kein `agentMgmt.empty`/`…add.autoNote`/`…add.projectNote` vorhanden).
- **Reuse-gegen-Code verifiziert:** `ADD_BUTTON`=`agentMgmt.addButton`, `GATE_HINT`=`agentMgmt.gateHint`, `LIST`=`agentMgmt.list`,
  `ADD_*_INPUT`=`agentMgmt.add.*.input`, `ADD_SPAWN_HINT`=`agentMgmt.add.spawnHint` — alle bestehend.
- **⚠ Shared-Tag-Drift:** mit dem CYP-228-Impl-Slice timen.
