# testTag-Schema — L-Client-UX (CYP-262)

> Owner: UIUX-Designer · Story **CYP-262** · Stand: 2026-07-06 · Status: Vorschlag
> **Schema (Test-Contract v0.5 §2):** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`, **prefixless**, camelCase.
> Bestehende Areas **`agent`** (instanz-scoped) + **`projectSwitcher`** — verifiziert gg. `AgentViewTags.kt`/`ProjectTags.kt` @ `44b1b1b`.
> **Vertrag Dev/QA (CYP-7):** API, nicht still umbenennen; koordiniert über den PO.

---

## 1. Neue Tags

### Teil 1 (Spawn-Flow): **0**
Das transiente „Startet…" rendert auf dem **bestehenden** `StatusIndicator`-Knoten `agent.<id>.status` — **kein neuer Tag**.
Der QA-relevante Unterschied ist der **Text/Zustand** (Label „Startet…"), kein neues Element.

### Teil 2 (Background/LRU): **1** (+ 1 optional D4)

| Element | neuer Tag | Rolle |
|---|---|---|
| per-Projekt-Session-Indikator (▾-Menü-Zeile) | `ProjectTags.itemSession(id)` = `projectSwitcher.item.<id>.session` | Träger des `TonedHint(INFO)`; zeigt BACKGROUND/SUSPENDED (ACTIVE = ●, kein Indikator) |
| *(optional, D4 §-Ask)* Bar-Level-Background-Summe | `ProjectTags.BACKGROUND_SUMMARY` = `projectSwitcher.backgroundSummary` | „N Projekte laufen im Hintergrund" (nur wenn PO den Bar-Hinweis will) |

> Konsistent mit der bestehenden `projectSwitcher.item.<id>.*`-Familie (`.active`). Übergabe:
> `TonedHint(text = project_session_{background|suspended}, tone = INFO, tag = ProjectTags.itemSession(id))`.

---

## 2. Reuse — bestehende Tags/Anker (gg. Code @ `44b1b1b` verifiziert)

| Element | bestehender Tag (reuse) | Rolle in CYP-262 |
|---|---|---|
| Agent-Status (Punkt+Label) | `AgentViewTags.status(id)` = `agent.<id>.status` | **Teil 1:** trägt „Gestoppt"→„Startet…"→„Läuft"/Fehler |
| Reconnecting-Chip | `AgentViewTags.reconnecting(id)` = `agent.<id>.reconnecting` | **Teil 1:** Terminal-Connect (nur `!= LIVE`) |
| Lifecycle-Fehler | `AgentViewTags.lifecycleError(id)` = `agent.<id>.lifecycleError` | **Teil 1:** Spawn-Fehler „Start fehlgeschlagen" |
| Start-Control | `AgentViewTags.startBtn(id)` = `agent.<id>.startBtn` | **Teil 1:** löst den Spawn aus |
| Projekt-Zeile / Aktiv | `ProjectTags.item(id)` / `.item(id).active` | **Teil 2:** Träger-Zeile des Session-Indikators / ACTIVE-Markierung |

---

## 3. Test-relevante Anker (für QA/CYP-7)

**Teil 1:**
- **① Anlegen→STOPPED:** neuer Agent → `agent.<id>.status` == „Gestoppt" (nicht „Läuft"); CYP-250-Empty-State absent.
- **② Start→Startet…:** Klick `agent.<id>.startBtn` → `agent.<id>.status` == „Startet…" (transient, **nicht** „Läuft").
- **③ Running:** `/ws/lifecycle` RUNNING → `agent.<id>.status` == „Läuft"; `agent.<id>.reconnecting` verschwindet auf `LIVE`.
- **④ Spawn-Fehler:** `spawn_failed` → `agent.<id>.status` bleibt „Gestoppt" + `agent.<id>.lifecycleError` == „Start fehlgeschlagen".

**Teil 2 (sobald Server-Session-State bindet, §5):**
- **⑤ Background:** verlassenes, laufendes Projekt → `projectSwitcher.item.<id>.session` == „Läuft im Hintergrund" (INFO-Ton, „i"-Glyph).
- **⑥ Suspended:** LRU-evicted Projekt → `projectSwitcher.item.<id>.session` == „Suspendiert — Resume beim Öffnen" (INFO).
- **⑦ Active ohne Extra-Indikator:** aktives Projekt → `projectSwitcher.item.<id>.active` present, **kein** `.session`-Indikator.
- **⑧ Kein Fehler-Ton:** weder ⑤ noch ⑥ nutzt den ERROR-/`✕`-Ton (a11y liest „Projekt-Sitzung: …", nie „Fehler").

---

## 4. Hand-off + Zähl-/Validierungs-Block

- **Neue Tags gesamt: 1** (`projectSwitcher.item.<id>.session`) **+ optional 1** (`projectSwitcher.backgroundSummary`, D4).
- **Teil 1: 0 neue Tags** (reuse `agent.<id>.status`/`.reconnecting`/`.lifecycleError`/`.startBtn`).
- **Reuse-gegen-Code verifiziert @ `44b1b1b`:** `AgentViewTags.{status,reconnecting,lifecycleError,startBtn}`, `ProjectTags.{item,item.active}` — alle bestehend.
- **⚠ Shared-Tag-Drift:** 1 (+1 opt) neue Konstante → **`ProjectTags` + CYP-7-Test-Modul re-syncen** (mit dem Impl-Slice timen).
- **⚠ Backend-Dep (T2):** die `.session`-Indikator-Knoten existieren erst, wenn der Server den per-Projekt-Session-State liefert (§5).
