# testTag-Schema — Titelleisten-Expand/Restore (CYP-241)

> Owner: UIUX-Designer · Story **CYP-241** · Stand: 2026-07-05 · Status: Vorschlag
> **Schema (Test-Contract v0.5 §2):** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`, **prefixless**, camelCase.
> Bestehende Area **`window`** — verifiziert gg. `WindowTestTags.kt` @ `328e7ed`.
> **Vertrag Dev/QA (CYP-7):** API, nicht still umbenennen; koordiniert über den PO.

---

## 1. Neue Tags: **KEINE (0)**

Der Doppelklick-Expand fügt **keinen sichtbaren Control** hinzu — die Geste sitzt auf der **bestehenden** Titelleiste. Der QA-relevante
Unterschied ist **Zustand**, kein neues Element → er wird über **`stateDescription`** (Semantik-Property, kein Tag) exponiert, nicht über
einen neuen Tag.

---

## 2. Reuse — bestehende Tags (gg. `WindowTestTags.kt` @ `328e7ed` verifiziert)

| Element | bestehender Tag (reuse) | Rolle in CYP-241 |
|---|---|---|
| Titelleiste (Drag-Handle) | `WindowTestTags.titleBar(id)` = `window.<id>.titlebar` | **Gesten-Ziel**: QA doppeltippt diesen Knoten; trägt zusätzlich `stateDescription` (§3) |
| Fenster-Wurzel | `WindowTestTags.window(id)` = `window.<id>` | alternativer Träger der `stateDescription` (Impl-Wahl: Titelleiste **oder** Wurzel — einer, konsistent) |
| Fit/Re-Tile-Affordance | `WindowTestTags.FIT` = `window.host.fit` | löst `fit()` → alle Expand-Anker gelöscht (QA: Zustand fällt danach auf „normal") |
| Host/Desktop | `WindowTestTags.HOST` = `window.host` | unverändert |

---

## 3. Zustands-Exposition (Semantik statt Tag)

`stateDescription` am Fenster-Knoten (Titelleiste oder `window.<id>`-Wurzel — Impl-Wahl, **einer**, konsistent):

| Zustand | `stateDescription` (Key) | Bedeutung |
|---|---|---|
| Restore-Anker vorhanden | `window_state_expanded` | vergrößert & zentriert, Doppelklick → Restore verfügbar |
| kein Anker | `window_state_normal` | Normalgröße / kein verfügbarer Restore |

**Ehrlichkeit:** `stateDescription` spiegelt **Anker-Präsenz** (= Restore-Verfügbarkeit), nicht die reine Fenstergröße — zieht der Nutzer
ein vergrößertes Fenster weg (Anker gelöscht), meldet der Zustand ehrlich „Normalgröße" (kein Restore), obwohl es visuell groß bleibt.

---

## 4. Test-relevante Anker (für QA/CYP-7)

- **① Expand:** Doppeltipp auf `window.<id>.titlebar` → `stateDescription == window_state_expanded`; Geometrie zentriert, ≤ Viewport, nie Vollbild.
- **② Restore (unberührt):** zweiter Doppeltipp → `stateDescription == window_state_normal`; Geometrie == Original.
- **③ Re-Expand nach Änderung:** Drag/Resize dazwischen → zweiter Doppeltipp → `stateDescription == window_state_expanded` (frischer Re-Expand; **kein toter Doppelklick**).
- **④ Re-Tile invalidiert:** Klick auf `window.host.fit` → `stateDescription == window_state_normal` an allen Fenstern.
- Geometrie-Assertions laufen zusätzlich **außerhalb Compose** gegen den `WindowReducer`/`WindowManagerState` (reine Float-Logik, wie der bestehende State-Test).

---

## 5. Hand-off + Zähl-/Validierungs-Block
- **Neue Tags gesamt: 0.** Kein Eintrag in `WindowTestTags` nötig.
- **Reuse-gegen-Code verifiziert @ `328e7ed`:** `titleBar(id)`=`window.<id>.titlebar`, `window(id)`=`window.<id>`, `FIT`=`window.host.fit`,
  `HOST`=`window.host` — alle bestehend.
- **Neu-Semantik:** `stateDescription` (2 Keys, siehe `-keys.md`) — Property, kein Tag.
- **⚠ Shared-Tag-Drift:** keine neuen Konstanten → kein Tag-Re-Sync mit CYP-7 nötig; nur die 2 Keys timen mit dem Impl-Slice.
