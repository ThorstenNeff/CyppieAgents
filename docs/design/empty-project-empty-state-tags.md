# testTag-Schema — Empty-Project Empty-State (CYP-250)

> Owner: UIUX-Designer · Story **CYP-250** · Stand: 2026-07-06 · Status: Vorschlag
> **Schema (Test-Contract v0.5 §2):** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`, **prefixless**, camelCase.
> Bestehende Area **`window`** (Host/Desktop) — verifiziert gg. `WindowTestTags.kt` @ `34b4fd4`.
> **Vertrag Dev/QA (CYP-7):** API, nicht still umbenennen; koordiniert über den PO.

---

## 1. Neue Tags: **3** — die `window.host.empty.*`-Familie

| Element | neuer Tag | Rolle |
|---|---|---|
| Empty-State-Container (Desktop, 0 Agenten) | `WindowTestTags.EMPTY` = `window.host.empty` | Präsenz = „0-Agenten-Leerzustand sichtbar"; Träger von Heading + Body |
| Primäre CTA („Agent hinzufügen") | `WindowTestTags.emptyAddBtn` = `window.host.empty.addBtn` | routet in den bestehenden `openAdd`-Flow; operator-gated (`enabled`) |
| Gate-Hinweis (Nicht-Operator) | `WindowTestTags.emptyGateHint` = `window.host.empty.gateHint` | `TonedHint(GATED)` mit `workspace_operator_only`; nur wenn nicht-Operator |

> Konsistent mit der Empty-State-Präzedenz des PhonePager (`phonePager.empty`) — der Desktop-Canvas bekommt sein
> Äquivalent unter `window.host.*`. Heading/Body brauchen **keine** eigenen Tags (der Container-Tag + der reused Copy
> genügen für QA); nur die **interaktiven**/zustands-tragenden Knoten (CTA, Gate-Hinweis) sind separat getaggt.

---

## 2. Reuse — bestehende Tags/Anker (gg. Code @ `34b4fd4` verifiziert)

| Element | bestehender Anker (reuse) | Rolle in CYP-250 |
|---|---|---|
| Desktop/Canvas | `WindowTestTags.HOST` = `window.host` | Container-Ebene; **unverändert** (der Empty-State liegt darin) |
| Agenten-Verwaltungs-Fenster | `AGENT_MGMT_WINDOW_ID` (Fenster-ID) | CTA-Ziel: `focus(AGENT_MGMT_WINDOW_ID)` + `openAdd` |
| Add-Button (Agenten-Verwaltung) | `AgentMgmtTags.ADD_BUTTON` = `agentMgmt.addButton` | der **bestehende** Add-Einstieg (die CTA routet dorthin, ersetzt ihn nicht) |
| CYP-228-Empty-State (Panel-intern) | `AgentMgmtTags.EMPTY` = `agentMgmt.empty` | **paralleles** Muster (nicht dasselbe Element — anderer Surface); Copy geteilt |

**Nicht** verwendet: `PhonePagerTags.EMPTY` (`phonePager.empty`) ist der **Phone**-Empty-State (fensterlos); der Desktop
ist ein **anderer** Surface + **agentenlos** (kein Reuse dieses Knotens/Copy).

---

## 3. Sichtbarkeits-/Zustands-Logik (kein Tag, aber QA-relevant)

- `window.host.empty` **existiert nur, während `managedAgents.isEmpty()`** (0 Agenten) — **nicht** an
  `state.windows.isEmpty()` gekoppelt (System-Fenster sind immer da). QA prüft **Präsenz/Absenz**.
- CTA `window.host.empty.addBtn`: `enabled` == Operator; `window.host.empty.gateHint` **nur** wenn nicht-Operator.

---

## 4. Test-relevante Anker (für QA/CYP-7)

- **① Erscheint bei 0 Agenten:** frisches/agentloses Projekt → `window.host.empty` **präsent**, trägt
  `agent_empty_title` + `agent_empty_body`; CTA `window.host.empty.addBtn` mit Label `agent_add` sichtbar.
- **② Self-clear bei ≥1 Agent:** nach dem ersten Agenten (dessen Fenster erscheint) → `window.host.empty` **absent**.
- **③ NICHT an Fensterzahl gekoppelt:** System-Fenster (Agenten-Verwaltung etc.) present **und** `window.host.empty`
  present gleichzeitig — der Empty-State triggert auf 0 **Agenten**, nicht 0 Fenster.
- **④ Operator-CTA live:** Operator → `window.host.empty.addBtn` `enabled`; Klick → Agenten-Verwaltung fokussiert +
  Add-Dialog offen (bestehender `openAdd`-Flow).
- **⑤ Nicht-Operator-Gate:** kein Operator → `window.host.empty.addBtn` disabled **und** `window.host.empty.gateHint`
  präsent (`workspace_operator_only`); **kein toter CTA**.
- **⑥ Ehrliche Copy:** `window.host.empty` a11y liest „noch keine Agenten", nie „leerer Desktop/nichts hier".

---

## 5. Hand-off + Zähl-/Validierungs-Block

- **Neue Tags gesamt: 3** — `window.host.empty`, `window.host.empty.addBtn`, `window.host.empty.gateHint`.
- **Reuse-gegen-Code verifiziert @ `34b4fd4`:** `WindowTestTags.HOST` = `window.host`, `AgentMgmtTags.ADD_BUTTON` =
  `agentMgmt.addButton`, `AgentMgmtTags.EMPTY` = `agentMgmt.empty` — alle bestehend.
- **⚠ Shared-Tag-Drift:** **3 neue Konstanten** → **`WindowTestTags` + CYP-7-Test-Modul re-syncen** (mit dem Impl-Slice
  timen). Tags sind API zwischen Dev und QA.
