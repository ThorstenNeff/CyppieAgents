# testTag-Schema — Restart-Hinweis am Home-Agentenfenster (CYP-239)

> Owner: UIUX-Designer · Story **CYP-239** · Stand: 2026-07-06 · Status: Vorschlag
> **Schema (Test-Contract v0.5 §2):** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`, **prefixless**, camelCase.
> Bestehende, instanz-gescopte Area **`agent`** — verifiziert gg. `AgentViewTags.kt` @ `e6f0882`.
> **Vertrag Dev/QA (CYP-7):** API, nicht still umbenennen; koordiniert über den PO.

---

## 1. Neuer Tag: **1** — der Home-Restart-Hinweis-Knoten

| Element | neuer Tag | Rolle |
|---|---|---|
| Restart-Pending-Hinweis im Agentenfenster-Kopf | `AgentViewTags.restartHint(id)` = `agent.<id>.restartHint` | Träger des reused `TonedHint(EFFECT_DEFERRED)`; sichtbar nur solange `needsRestart` |

> Konsistent mit der bestehenden `agent.<id>.*`-Familie (`stream`/`input`/`header`/`status`/`restartBtn`/…). Übergabe an
> die reused Komponente: `TonedHint(text = agent_edit_effect_hint, tone = EFFECT_DEFERRED, tag = AgentViewTags.restartHint(id))`.

---

## 2. Reuse — bestehende Tags (gg. `AgentViewTags.kt` @ `e6f0882` verifiziert)

| Element | bestehender Tag (reuse) | Rolle in CYP-239 |
|---|---|---|
| Fenster-Kopf (Status + Lifecycle, CYP-73) | `AgentViewTags.header(id)` = `agent.<id>.header` | **Platzierungs-Anker:** der Hinweis rendert im/am Kopf |
| Restart-Button (CYP-73) | `AgentViewTags.restartBtn(id)` = `agent.<id>.restartBtn` | **Auflösungs-Aktion:** der Hinweis verweist darauf, fügt keine zweite hinzu |
| Lifecycle-Status | `AgentViewTags.status(id)` = `agent.<id>.status` | benachbart; unverändert |

**Nicht** verwendet: `WindowBadgeTags` / `window.<id>` Badge-Slot — bewusst (Spec §D3: `Attention` ist „honest ERROR
only"; ein restart-pending Agent ist kein Fehler).

---

## 3. Sichtbarkeits-/Zustands-Logik (kein Tag, aber QA-relevant)

- Der Knoten `agent.<id>.restartHint` **existiert nur, während der Agent restart-pending ist** (fail-closed;
  Abwesenheit = ehrlicher Leerzustand). QA prüft **Präsenz/Absenz**, nicht ein „leer"-Zustand.
- **Nur restart-deferred:** vorhanden nach Persona/Connector-Pending; **abwesend** nach reiner Name-/Farb-Änderung.

---

## 4. Test-relevante Anker (für QA/CYP-7)

- **① Erscheint bei Pending:** Agent restart-pending (Persona gespeichert ≠ aktiv) → `agent.<id>.restartHint` **präsent**,
  trägt den `agent_edit_effect_hint`-Text, amber `EFFECT_DEFERRED` (+ „!"-Glyph).
- **② Nicht bei immediate:** nur Name/Farbe geändert → `agent.<id>.restartHint` **absent**.
- **③ Auto-Clear:** Agent-Restart (CYP-73) → Pending löst → `agent.<id>.restartHint` **verschwindet**.
- **④ Kein Fehler-Anschein:** Knoten ist **nicht** `agent.<id>`-`Attention`/`⚠`; a11y liest „aufschiebend/Neustart nötig",
  nie „Fehler".
- **⑤ Per-Agent-Isolation:** Pending an Agent A → nur `agent.A.restartHint` präsent, `agent.B.restartHint` absent.
- **⑥ Auflösungs-Route:** `agent.<id>.restartBtn` (bestehend) ist im selben Kopf erreichbar; kein zweiter Restart-Knoten.

---

## 5. Hand-off + Zähl-/Validierungs-Block

- **Neue Tags gesamt: 1** — `AgentViewTags.restartHint(id)` = `agent.<id>.restartHint`.
- **Reuse-gegen-Code verifiziert @ `e6f0882`:** `header(id)`, `restartBtn(id)`, `status(id)` — alle bestehend in
  `AgentViewTags`.
- **⚠ Shared-Tag-Drift:** **1 neue Konstante** → **`AgentViewTags` + CYP-7-Test-Modul re-syncen** (mit dem Impl-Slice
  timen). Der Tag ist API zwischen Dev und QA.
- **Kein Badge-Slot missbraucht:** bewusste Disclosure-Entscheidung (§D3), dokumentiert.
