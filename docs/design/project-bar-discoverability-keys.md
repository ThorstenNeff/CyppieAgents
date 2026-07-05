# i18n-Keys — Projekt-Leiste Discoverability (CYP-233)

> Owner: UIUX-Designer · Story **CYP-233** · Stand: 2026-07-05 · Status: Vorschlag — Keys landen MIT dem CYP-233-Impl-Slice
> (Shared-Key-Drift → mit Dev/CYP-7 timen).
> Konvention (verifiziert gg. `values/strings.xml`, develop `0394316`): **Underscore-Realkeys**, keine Argumente. **DE = Default**
> (`values/`), **EN** (`values-en/`). Parität Pflicht. Prefix `project_switcher_`.

## Neue Keys (2)

| Key | DE | EN |
|---|---|---|
| `project_switcher_menu_label` | Projekte | Projects |
| `project_switcher_single_hint` | Weitere Projekte über „Projekte verwalten" anlegen. | Create more projects via "Manage projects". |

> `project_switcher_menu_label` = das sichtbare Label vor dem „▾"-Caret (Menü-Button). `project_switcher_single_hint` erscheint **nur**
> bei genau einem Projekt; der zitierte Name **muss** exakt `project_manage` entsprechen (heute DE „Projekte verwalten" / EN „Manage
> projects") — er verweist auf den real vorhandenen Menü-Eintrag.

---

## Reuse (keine neuen Keys — gg. Code verifiziert @ `0394316`)
| Reused Key | Zweck in CYP-233 |
|---|---|
| `a11y_project_switcher_menu` („Projekt-Menü öffnen, aktiv: %1$s") | a11y des Menü-Buttons — **bleibt** (nur die sichtbare Beschriftung war die Lücke) |
| `project_manage` („Projekte verwalten") | Ziel-Eintrag, auf den `project_switcher_single_hint` verweist |

---

## Zähl-/Validierungs-Block (Selbst-Validierung)
- **Neue Keys gesamt: 2** — `project_switcher_menu_label`, `project_switcher_single_hint`. **DE+EN-Parität 2/2.**
- **Argument-Keys (`%…$s`): 0.**
- **0 Kollision** gg. `strings.xml`/`values-en` @ `0394316` (Prefix `project_switcher_menu_label`/`_single_hint` neu; im Push
  `grep`-gegengeprüft).
- **Konsistenz-Anker:** die Zitierung „Projekte verwalten" in `project_switcher_single_hint` == `project_manage` (bei Copy-Änderung
  dort mitziehen).
- **⚠ Shared-Key-Drift:** landen in `:app:shared`-Resources → CYP-233-Impl + Test-Modul (CYP-7) re-syncen. **Mit dem Impl-Slice timen.**
