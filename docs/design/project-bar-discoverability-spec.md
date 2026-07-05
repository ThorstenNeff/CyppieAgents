# Projekt-Leiste Discoverability — UX/UI-Design-Spec (CYP-233)

> Owner: UIUX-Designer · Story **CYP-233** (Low, XS–S; Erst-Nutzer-Politur der Projekt-Leiste CYP-91/92) · Stand: 2026-07-05
> Begleitdateien: `project-bar-discoverability-keys.md`, `-tags.md`, `-tokens.json`.
> **Grounded gg. develop `0394316`:** `project/ProjectSwitcherBar.kt` (`projectSwitcher.*`-Tags, `ProjectTags`), `values/strings.xml`.
> **Rein UI + Copy/a11y — keine Model-/Endpoint-Änderung.** Auslöser: Multi-Projekt-Support-Fund — ein Erst-Nutzer findet Anlegen/
> Wechseln nicht (steckt im ▾-Menü) und weiß bei nur einem Projekt nicht, wie er weitere anlegt. (Der stärkere dritte Fund — der
> Add-Agent-Dialog nennt das aktive Projekt nicht — ist als `agent_add_project_scope_note` in **CYP-228** gefaltet.)

---

## 0. Umfang & Leitidee

Zwei kleine Discoverability-Bausteine an der bestehenden Projekt-Leiste (`ProjectSwitcherBar`), beide reiner Reuse:

- **② Sichtbarer Menü-Affordance:** der Menü-Trigger ist heute ein **nacktes „▾"** (`projectSwitcher.menu`) → ein sichtbares Label
  **„Projekte ▾"** macht klar, dass hier Wechseln **und** Verwalten sitzt.
- **③ Ein-Projekt-Onboarding:** bei genau **einem** Projekt ein dezenter Hinweis im Dropdown, dass weitere über „Projekte verwalten"
  anlegbar sind.

**Ehrlichkeits-Kern (mein Lane):** ② ist **rein Affordance** (kein Verhaltenswechsel — dasselbe Menü); ③ verweist **nur** auf den
**real vorhandenen** „Projekte verwalten"-Eintrag (kein toter Hinweis) und erscheint **nur** bei einem Projekt (kein Dauer-Rauschen).

---

## 1. Reuse-Bestand (gg. `0394316` verifiziert)

| Baustein | Datei | Reuse in CYP-233 |
|---|---|---|
| Menü-`TextButton` (`Text("▾")`, Tag `projectSwitcher.menu`) | `ProjectSwitcherBar.kt` | Label „Projekte ▾" statt nacktem Caret (gleicher Button/Tag) |
| `a11y_project_switcher_menu` („Projekt-Menü öffnen, aktiv: %1$s") | strings.xml | **bleibt** — a11y ist schon gut; nur die **sichtbare** Beschriftung fehlte |
| `DropdownMenu` + `project_manage` („Projekte verwalten") + Tag `projectSwitcher.manage` | dito | der Ein-Projekt-Hinweis verweist auf diesen bestehenden Eintrag |
| `TonedHint(INFO)` (CYP-99) | dito | der Ein-Projekt-Hinweis (wie `switchHint`/`scopeHint`) |

**Anti-Divergenz:** kein neuer Menü-Weg, kein zweiter „Anlegen"-Button — nur ein sichtbares Label + ein bedingter INFO-Hinweis.

---

## 2. ② Sichtbarer Menü-Affordance

Der Menü-`TextButton` zeigt statt `Text("▾")` das Label **„Projekte ▾"**: `project_switcher_menu_label` („Projekte") + Caret.
Tag/Verhalten/a11y unverändert (`projectSwitcher.menu`, `a11y_project_switcher_menu`). So ist auf einen Blick klar, dass hier das
**Wechseln und Verwalten** von Projekten sitzt — heute rät ein Erst-Nutzer beim nackten „▾".

---

## 3. ③ Ein-Projekt-Onboarding (`state.projects.size == 1`)

Im geöffneten Dropdown, **nur wenn genau ein Projekt existiert**, ein dezenter INFO-Hinweis (`TonedHint`, Tag
`projectSwitcher.singleHint`) **über** dem „Projekte verwalten"-Eintrag: `project_switcher_single_hint` — „Weitere Projekte über
‚Projekte verwalten' anlegen." Der zitierte Menü-Name **muss** exakt dem `project_manage`-String entsprechen (heute „Projekte
verwalten") — verweist auf den real vorhandenen Eintrag.

> Erscheint **nur** bei einem Projekt: sobald ein zweites existiert, trägt die Projektliste selbst die Discoverability (kein
> Dauer-Hinweis).

---

## 4. Modell / Verhalten (unverändert)

**Keine** Änderung an `Project`/den Routes/dem Switch-Verhalten. Nur: die sichtbare Menü-Beschriftung, ein bedingter INFO-Hinweis.
Der Wechsel bleibt der non-destruktive scoped Re-Fetch (unverändert), das Operator-Gate bleibt (ohne Operator ist das Menü
informativ; der Ein-Projekt-Hinweis ist neutrale Info, kein CTA).

---

## 5. Disclosure-Ehrlichkeit — Invarianten (Abnahme-Checkliste UX-QA)

1. **② rein Affordance:** „Projekte ▾" öffnet dasselbe Menü; kein Verhaltenswechsel; a11y unverändert korrekt.
2. **③ nur bei einem Projekt:** der Hinweis erscheint bei `projects.size == 1`, verschwindet ab dem zweiten Projekt.
3. **③ kein toter Hinweis:** verweist auf den **real vorhandenen** „Projekte verwalten"-Eintrag; der zitierte Name == `project_manage`.
4. **Operator-Gate intakt:** ohne Operator bleibt das Menü informativ; kein Hinweis fordert zu einer gesperrten Aktion auf.
5. **a11y + Parität:** sichtbares Label + Hinweis werden gelesen; DE+EN 1:1.

---

## 6. Counts / Reuse (Selbst-Validierung → Begleitdateien)

- **Neue i18n-Keys:** **2** (`project_switcher_menu_label`, `project_switcher_single_hint`), DE+EN-Parität — siehe
  `project-bar-discoverability-keys.md`.
- **Neue Tags:** **1** (`projectSwitcher.singleHint`); der Menü-Label reused `projectSwitcher.menu` — siehe `-tags.md`.
- **Neue Tokens/Farben:** **0**.
- **Größe: XS–S** — reine UI + Copy/a11y, keine Model-/Endpoint-Änderung.

*Nur Design/Spec/Verifikation, keine Implementierung. Key/Tag-Landing mit dem CYP-233-Impl-Slice timen (CYP-7). UX-QA nach Dev-Impl
durch UIUX.*
