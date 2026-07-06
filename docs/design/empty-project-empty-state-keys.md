# i18n-Keys — Empty-Project Empty-State (CYP-250)

> Owner: UIUX-Designer · Story **CYP-250** · Stand: 2026-07-06 · Status: Vorschlag
> Konvention (verifiziert gg. `values/strings.xml` @ `34b4fd4`): Underscore-Realkeys, **DE = Default** (`values/`),
> **EN** (`values-en/`), Parität Pflicht.

---

## 1. Neue Keys: **KEINE (0)** — Near-pure-Reuse

Der Desktop-Empty-State nutzt **ausschließlich bestehende** Strings — Titel/Body verbatim aus dem CYP-228-Empty-State,
CTA-Label und Gate-Hinweis aus dem bestehenden Add-Flow:

| Reused Key (@ `34b4fd4`) | DE | EN | Rolle in CYP-250 |
|---|---|---|---|
| `agent_empty_title` | Noch keine Agenten | No agents yet | **Überschrift** (Heading) |
| `agent_empty_body` | Lege deinen ersten Agenten an — ID und Name genügen. Er startet erst über die Lifecycle-Steuerung. | Add your first agent — an ID and a name are enough. It won't run until you start it via the lifecycle controls. | **Body** (trägt „Anlegen ≠ Start") |
| `agent_add` | Agent hinzufügen | Add agent | **CTA-Button-Label** |
| `workspace_operator_only` | Nur der Operator kann das ändern | Only the operator can change this | **Gate-Hinweis** (Nicht-Operator, `TonedHint(GATED)`) |

> **Warum reuse-verbatim:** exakt der CYP-228-Wortlaut → **ein** Onboarding-Vokabular über Agenten-Verwaltungs-Panel
> **und** Desktop. `agent_empty_body` ist generisch genug für beide Flächen und trägt „startet erst über die
> Lifecycle-Steuerung" (**„Anlegen ≠ Start"**) schon im Text. **0 Divergenz.**

---

## 2. NICHT wiederverwendet (bewusst — semantischer Mismatch)

| Key | Warum nicht |
|---|---|
| `pager_empty` („Keine Fenster" / „No windows", PhonePager-Empty-State) | Der Desktop-Zustand ist **agentenlos**, nicht **fensterlos** (System-/Werkzeug-Fenster koexistieren). „Keine Fenster" wäre eine Lüge. |

---

## 3. Optional (Forward §10, nur auf PO-Wunsch, NICHT blockierend): projekt-gescopte Titel-Variante

Falls der Titel den aktiven Projektnamen nennen soll:

| Key (Vorschlag) | DE | EN |
|---|---|---|
| `agent_empty_title_in_project` | Noch keine Agenten in %1$s | No agents in %1$s yet |

> **Default bleibt Reuse-verbatim (`agent_empty_title`, §1)** — die Projekt-Leiste (`project_switcher_active`) nennt den
> Namen schon, Wiederholung ist redundant. Dieser Sibling **nur**, wenn der PO den Projekt-Bezug im Titel will; dann
> DE+EN paritätisch mit positional `%1$s`.

---

## Zähl-/Validierungs-Block (Selbst-Validierung)

- **Neue Keys Pflicht: 0.** Reuse `agent_empty_title`, `agent_empty_body`, `agent_add`, `workspace_operator_only` —
  alle existieren DE+EN @ `34b4fd4` (verifiziert 1/1 je).
- **Neue Keys optional (Forward): 1** — `agent_empty_title_in_project` (1 Arg `%1$s`, DE+EN 1/1), nur auf PO-Wunsch.
- **Argument-Keys (`%…$s`):** 0 im Pflicht-Set; 1 im optionalen Set.
- **0 Kollision:** der optionale Key ist neu gg. `strings.xml`/`values-en` @ `34b4fd4` (im Push `grep`-gegengeprüft,
  falls gezogen).
- **Kein Secret in Keys:** keine E-Mail/Token/Endpoints; neutrale Onboarding-Copy.
- **Ehrlichkeit:** „noch keine Agenten" (nicht „nichts hier"); „Anlegen ≠ Start" im Body reused; Gate-Hinweis nennt den
  ehrlichen Grund.
- **⚠ Shared-Key-Drift:** bei §1 (Reuse) **keiner** — alle Keys existieren schon. Nur falls der optionale Sibling
  gezogen wird, landet er in `:app:shared` → mit CYP-7/Impl timen.
