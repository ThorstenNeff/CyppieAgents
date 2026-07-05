# i18n-Keys — Agent-Add-Onboarding (CYP-228)

> Owner: UIUX-Designer · Story **CYP-228** · Stand: 2026-07-05 · Status: Vorschlag — **Key-Sync-Punkt** (Keys landen MIT dem
> CYP-228-Impl-Slice; Shared-Key-Drift → mit Dev/CYP-7 timen).
> Konvention (verifiziert gg. `values/strings.xml`, develop `6cfff22`): **Underscore-Realkeys** (keine Punkte), keine Argumente.
> **DE = Default** (`values/`), **EN** (`values-en/`). Parität Pflicht. Prefix `agent_add_` / `agent_empty_`.

## Neue Keys (13)

### A) Feld-Hints — `supportingText` (dauerhaft sichtbar)
| Key | DE | EN |
|---|---|---|
| `agent_add_id_hint` | Pflicht · unveränderlich. Nur a–z, 0–9, „-", „_". Wird zu Ordner, Branch & Kanal. | Required · permanent. Only a–z, 0–9, "-", "_". Becomes folder, branch & channel. |
| `agent_add_name_hint` | Pflicht · Anzeigename, jederzeit änderbar. | Required · display name, editable anytime. |
| `agent_add_role_hint` | Pflicht · Worker = ausführender Agent · PO = Koordinator (genau einer). | Required · Worker = doer · PO = coordinator (exactly one). |
| `agent_add_persona_hint` | Optional · Rolle & Regeln des Agenten (wird zur CLAUDE.md). Leer = später ergänzbar. | Optional · the agent's role & rules (becomes its CLAUDE.md). Blank = add later. |
| `agent_add_launch_hint` | Optional · Befehl, der die Session startet. Leer = Projekt-Standard. | Optional · command that starts the session. Blank = project default. |
| `agent_add_worktree_hint` | Optional · eigener Ordnername. Leer = automatisch aus der ID. | Optional · custom folder name. Blank = derived from the ID. |

### A) Feld-Placeholder — Beispielwert (nur Textfelder)
| Key | DE | EN |
|---|---|---|
| `agent_add_id_placeholder` | frontend | frontend |
| `agent_add_name_placeholder` | Frontend-Entwickler | Frontend developer |
| `agent_add_persona_placeholder` | Du bist der Frontend-Entwickler. Aufgaben, DoD, Konventionen … | You are the frontend developer. Tasks, DoD, conventions … |
| `agent_add_launch_placeholder` | claude | claude |

### A) Auto-Vergeben-Note (INFO)
| Key | DE | EN |
|---|---|---|
| `agent_add_autofields_note` | Token, Git-Branch und Hub-Kanal vergibt das System automatisch. Farbe & Avatar setzt du danach über das ⋮-Panel des Agenten. | Token, git branch and hub channel are assigned automatically. Set colour & avatar afterwards via the agent's ⋮ panel. |

### B) Leerzustand-Onboarding
| Key | DE | EN |
|---|---|---|
| `agent_empty_title` | Noch keine Agenten | No agents yet |
| `agent_empty_body` | Lege deinen ersten Agenten an — ID und Name genügen. Er startet erst über die Lifecycle-Steuerung. | Add your first agent — an ID and a name are enough. It won't run until you start it via the lifecycle controls. |

---

## Reuse (keine neuen Keys — gg. Code verifiziert @ `6cfff22`)
| Reused Key | Zweck in CYP-228 |
|---|---|
| `agent_add_spawn_hint` | „Anlegen ≠ Start" — bleibt unverändert (nicht doppeln) |
| `agent_add`, `agent_add_confirm`, `agent_cancel` | Dialog-/Button-Beschriftungen (unverändert) |
| `workspace_operator_only` (via `GATE_HINT`) | Operator-Gate im Empty-State (reuse) |
| `agent_role_po` / `agent_role_worker` | Rollen-Beschriftung (der neue `agent_add_role_hint` beschreibt nur ihre Bedeutung) |

---

## Zähl-/Validierungs-Block (Selbst-Validierung)
- **Neue Keys gesamt: 13** — 6 `agent_add_*_hint` + 4 `agent_add_*_placeholder` + `agent_add_autofields_note` + 2 `agent_empty_*`.
  **DE+EN-Parität 13/13.**
- **Argument-Keys (`%…$s`): 0.**
- **0 Kollision** gg. `strings.xml`/`values-en` @ `6cfff22` (Prefixe `agent_add_*_hint`/`_placeholder`/`agent_add_autofields_note`/
  `agent_empty_*` neu; im Push `grep`-gegengeprüft).
- **Kein Secret in Keys:** keine E-Mail/Token/Endpoints; Beispiele sind neutrale Anzeige-Werte.
- **Ehrlichkeit:** Hints spiegeln `AgentMgmtGuard`/`confirmAdd` (Pflicht nur ID+Name; Blank-Defaults; ID-Charset); Auto-Note nennt
  nur wirklich Auto-Vergebenes.
- **⚠ Shared-Key-Drift:** landen in `:app:shared`-Resources → konsumierendes Modul (CYP-228-Impl + Test-Modul CYP-7) muss
  re-syncen. **Mit dem CYP-228-Impl-Slice timen.**
