# testTag-Schema — Tastatur-Äquivalent Fenster-Expand/Restore (CYP-245)

> Owner: UIUX-Designer · Story **CYP-245** · Stand: 2026-07-06 · Status: Vorschlag
> **Schema (Test-Contract v0.5 §2):** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`, **prefixless**, camelCase.
> Bestehende Area **`window`** — verifiziert gg. `WindowTestTags.kt` @ `e6f0882`.
> **Vertrag Dev/QA (CYP-7):** API, nicht still umbenennen; koordiniert über den PO.

---

## 1. Neue Tags: **KEINE (0)**

CYP-245 fügt **keinen sichtbaren Control** hinzu — es ist eine **Tastenbindung** (`Enter`) auf dem **bestehenden**,
bereits fokussierbaren Fenster-Wurzel-Knoten. Der QA-relevante Unterschied ist **Zustand** (Expand/Restore), und der
ist seit CYP-241 über **`stateDescription`** exponiert (Semantik-Property, kein Tag). → **keine neue Konstante.**

---

## 2. Reuse — bestehende Tags (gg. `WindowTestTags.kt` @ `e6f0882` verifiziert)

| Element | bestehender Tag (reuse) | Rolle in CYP-245 |
|---|---|---|
| Fenster-Wurzel (fokussierbar, Key-Ziel) | `WindowTestTags.window(id)` = `window.<id>` | **Tastatur-Ziel:** QA fokussiert diesen Knoten und drückt `Enter`; trägt `stateDescription` (§3) |
| Titelleiste (Doppelklick-Ziel, CYP-241) | `WindowTestTags.titleBar(id)` = `window.<id>.titlebar` | **Referenz-Pfad:** QA vergleicht `Enter`-Ergebnis mit Doppeltipp auf diesen Knoten (Parität) |
| Fit/Re-Tile-Affordance | `WindowTestTags.FIT` = `window.host.fit` | löst `fit()` → alle Expand-Anker gelöscht (Zustand fällt auf „normal", auch für den Tastaturpfad) |
| Host/Desktop | `WindowTestTags.HOST` = `window.host` | unverändert |

---

## 3. Zustands-Exposition (Semantik statt Tag) — geerbt aus CYP-241, unverändert

`stateDescription` am Fenster-Knoten (`window.<id>`-Wurzel), gesetzt aus:

| Zustand | `stateDescription` (Key) | Bedeutung |
|---|---|---|
| Restore-Anker vorhanden | `window_state_expanded` | vergrößert & zentriert, Toggle → Restore verfügbar |
| kein Anker | `window_state_normal` | Normalgröße / kein verfügbarer Restore |

**Ehrlichkeit:** Der Wert spiegelt **Anker-Präsenz** (= Restore-Verfügbarkeit), nicht die reine Fenstergröße — für
`Enter` **wie** für den Doppeltipp identisch (derselbe `toggleExpand`).

---

## 4. Test-relevante Anker (für QA/CYP-7)

- **① Expand (Tastatur):** Fokus auf `window.<id>`, `Enter` → `stateDescription == window_state_expanded`; Geometrie
  zentriert, ≤ Viewport, nie Vollbild.
- **② Restore (Tastatur):** zweites `Enter` → `stateDescription == window_state_normal`; Geometrie == Original.
- **③ Re-Expand nach Änderung:** manuelle Pfeiltasten-Bewegung dazwischen → `Enter` → `window_state_expanded`
  (frischer Re-Expand; **kein toter Key**).
- **④ Parität Maus↔Tastatur:** `Enter`-Ergebnis-Geometrie **und** `stateDescription` == Doppeltipp-Ergebnis auf
  `window.<id>.titlebar` (identischer `toggleExpand`).
- **⑤ Kein Composer-Hijack:** Fokus in einem Kind-Eingabefeld (Composer) → `Enter` **verändert die Fenstergeometrie
  nicht** (bleibt Feld-`Enter`); `stateDescription` unverändert.
- **⑥ Fit invalidiert:** Klick/Aktivierung `window.host.fit` → `stateDescription == window_state_normal` an allen
  Fenstern (auch tastaturseitig zuvor expandierten).
- Geometrie-Assertions laufen zusätzlich **außerhalb Compose** gegen `WindowManagerState.toggleExpand`/`WindowReducer`
  (reine Float-Logik — der Tastaturpfad ruft exakt dieselbe Funktion, daher deckt der bestehende State-Test die
  Geometrie bereits ab; der Compose-Test prüft nur die **Verdrahtung** `Enter → onToggleExpand` + Fokus-Scoping).

---

## 5. Hand-off + Zähl-/Validierungs-Block

- **Neue Tags gesamt: 0.** Kein Eintrag in `WindowTestTags` nötig.
- **Reuse-gegen-Code verifiziert @ `e6f0882`:** `window(id)`=`window.<id>`, `titleBar(id)`=`window.<id>.titlebar`,
  `FIT`=`window.host.fit`, `HOST`=`window.host` — alle bestehend.
- **Zustand:** über die **bestehenden** CYP-241-Keys `window_state_expanded`/`window_state_normal` (Property, kein Tag).
- **⚠ Shared-Tag-Drift:** keine neuen Konstanten → **kein** Tag-Re-Sync mit CYP-7 nötig; nur der 1 Pflicht-Key
  (+ optional 2 Cleanup-Keys) timen mit dem Impl-Slice.
