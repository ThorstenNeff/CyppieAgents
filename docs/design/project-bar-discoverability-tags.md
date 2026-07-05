# testTag-Schema — Projekt-Leiste Discoverability (CYP-233)

> Owner: UIUX-Designer · Story **CYP-233** · Stand: 2026-07-05 · Status: Vorschlag
> **Schema (Test-Contract v0.5 §2):** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`, **prefixless**, camelCase.
> Erweitert die bestehende Area **`projectSwitcher`** — verifiziert gg. `ProjectTags.kt` @ `0394316`.
> **Vertrag Dev/QA (CYP-7):** API, nicht still umbenennen; koordiniert über den PO.

---

## 1. Neue Tags (1) — in `ProjectTags`

| Element | testTag | Zweck |
|---|---|---|
| Ein-Projekt-Hinweis | `projectSwitcher.singleHint` | INFO-Hinweis im Dropdown, nur bei `projects.size == 1` (verweist auf „Projekte verwalten") |

---

## 2. Reuse — KEINE neuen Tags für das Menü-Label

Das sichtbare Label „Projekte ▾" hängt am **bestehenden** Menü-Button-Tag — kein neuer Tag nötig:

| Element | bestehender Tag (reuse) |
|---|---|
| Menü-Button (jetzt „Projekte ▾") | `projectSwitcher.menu` |
| „Projekte verwalten"-Eintrag (Ziel des Hinweises) | `projectSwitcher.manage` |
| Wechsel-Hinweis / Scope-Hinweis (bestehend) | `projectSwitcher.switchHint` / `projectSwitcher.scopeHint` |

---

## 3. Test-relevante Disclosure-Anker (für QA/CYP-7)

- **② Affordance:** `projectSwitcher.menu` zeigt jetzt Text „Projekte" (+ „▾"); Öffnen-Verhalten + a11y unverändert.
- **③ Ein-Projekt-Hinweis:** `projectSwitcher.singleHint` ist **präsent bei genau einem Projekt** und **abwesent ab zwei**
  (QA prüft beide Zustände); verweist auf den real vorhandenen `projectSwitcher.manage`.
- **Operator-Gate:** ohne Operator bleibt das Menü informativ; der Hinweis ist neutrale Info, kein CTA.

---

## 4. Hand-off + Zähl-/Validierungs-Block
- Neue Konstante in `ProjectTags` (`:app:shared`), geteilt mit Tester (CYP-7); Änderungen über den PO.
- **Neue Tags gesamt: 1** (`projectSwitcher.singleHint`).
- **0 Kollision** gg. `ProjectTags.kt` @ `0394316` (kein `projectSwitcher.singleHint` vorhanden).
- **Reuse-gegen-Code verifiziert:** `MENU`=`projectSwitcher.menu`, `MANAGE`=`projectSwitcher.manage`,
  `SWITCH_HINT`=`projectSwitcher.switchHint`, `SCOPE_HINT`=`projectSwitcher.scopeHint` — alle bestehend.
- **⚠ Shared-Tag-Drift:** mit dem CYP-233-Impl-Slice timen.
