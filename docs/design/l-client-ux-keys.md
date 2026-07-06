# i18n-Keys — L-Client-UX (CYP-262)

> Owner: UIUX-Designer · Story **CYP-262** · Stand: 2026-07-06 · Status: Vorschlag
> Konvention (verifiziert gg. `values/strings.xml` @ `44b1b1b`): Underscore-Realkeys, **DE = Default** (`values/`),
> **EN** (`values-en/`), Parität Pflicht. Positional `%1$s`.

---

## 1. Neue Keys — Teil 1 (Spawn-Flow, client-only): **1**

| Key | DE | EN |
|---|---|---|
| `agent_status_starting` | Startet… | Starting… |

> **Ehrlichkeits-Anker:** spiegelt **die Start-Anfrage in Flight**, nicht den Prozess-Zustand — bewusst **„Startet…"**, nie „Läuft".
> Transient (client-`startPending`), resolvet immer in `agent_status_running` (Erfolg) oder `agent_status_stopped` + Fehlergrund.
> Reiht sich in die bestehende `agent_status_*`-Familie ein (Konsistenz).

## 2. Neue Keys — Teil 2 (Background/LRU-Indikator): **3** (+ 1 optional D4)

| Key | DE | EN |
|---|---|---|
| `project_session_background` | Läuft im Hintergrund | Running in the background |
| `project_session_suspended` | Suspendiert — Resume beim Öffnen | Suspended — resumes on open |
| `a11y_project_session` | Projekt-Sitzung: %1$s | Project session: %1$s |
| *(optional, D4 §-Ask)* `project_session_background_summary` | %1$s weitere Projekte laufen im Hintergrund | %1$s more projects running in the background |

> **Ehrlichkeits-Anker:**
> - `project_session_background` — **echte laufende Agenten** (Ressourcen), bewusst sichtbar/entdeckbar.
> - `project_session_suspended` — pausiert + **resumt beim Öffnen**; **kein** Datenverlust, **kein** Fehler; **kein** K=3/LRU-Jargon.
> - Beide: **INFO-Ton** (`HintTone.INFO`), nie ERROR/WARN — normale Zustände.
> - `a11y_project_session` trägt den Zustands-Text (Screenreader-Parität zu `a11y_agent_status`).
> - `project_session_background_summary` (**optional**) = Bar-Level-Ressourcen-Ehrlichkeit; nur wenn PO den Bar-Hinweis will (§10).

---

## 3. Reuse — bestehende Keys (gg. Code @ `44b1b1b` verifiziert, KEINE neuen)

| Reused Key | DE | Rolle in CYP-262 |
|---|---|---|
| `agent_status_running` / `_stopped` / `_error` / `_unknown` | Läuft / Gestoppt / Fehler / Unbekannt | Teil 1 Zustands-Anzeige (StatusIndicator) — „Startet…" resolvet hierhin |
| `agent_reconnecting` | Verbindung wird wiederhergestellt… | Teil 1 Terminal-Connect (ReconnectingChip, nur `!= LIVE`) |
| `agent_ctl_start` / `_stop` / `_restart` | Start / Stop / Neustart | Teil 1 Lifecycle-Controls |
| `agent_ctl_err_spawn_failed` | Start fehlgeschlagen | Teil 1 Spawn-Fehler (ehrlich, bleibt STOPPED) |
| `a11y_agent_status` | Agentenstatus: %1$s | Teil 1 a11y — deckt auch „Startet…" |
| `project_switcher_active` | Aktives Projekt: %1$s | Teil 2 ACTIVE-Markierung (●, bestehend — kein neuer Indikator) |
| `project_switch_hint` | Wechsel lädt die Oberfläche… – nichts wird gelöscht. | Teil 2 Switch-Kontext (bestehend) |

---

## Zähl-/Validierungs-Block (Selbst-Validierung)

- **Neue Keys Pflicht: 4** — `agent_status_starting` (T1); `project_session_background`, `project_session_suspended`,
  `a11y_project_session` (T2). **+ 1 optional** (`project_session_background_summary`, D4). **DE+EN-Parität je 1:1.**
- **Argument-Keys (`%…$s`):** `a11y_project_session` (1×`%1$s`), optional `project_session_background_summary` (1×`%1$s`). Sonst 0.
- **0 Kollision** gg. `strings.xml`/`values-en` @ `44b1b1b` (Prefixe `agent_status_starting`/`project_session_*` neu; im Push `grep`-geprüft).
- **Kein Secret in Keys:** keine E-Mail/Token/Endpoints; neutrale Zustands-Copy.
- **Ehrlichkeit:** „Startet…" ≠ „Läuft"; „Läuft im Hintergrund" (Ressourcen sichtbar) ≠ „Suspendiert — Resume beim Öffnen" (pausiert,
  kein Verlust); INFO nie ERROR; kein LRU-Jargon.
- **⚠ Shared-Key-Drift:** landen in `:app:shared` → Impl (CYP-256/CYP-255) + CYP-7-Test-Modul re-syncen. **Mit dem Impl-Slice timen.**
- **⚠ Backend-Dep (T2):** die Session-Copy rendert erst, wenn der Server den per-Projekt-Session-State liefert (Kontrakt .4b, Spec §5).
