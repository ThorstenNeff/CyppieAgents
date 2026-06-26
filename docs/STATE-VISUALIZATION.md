# Zustands-Visualisierung — Agentenfenster (v0.1)

> Owner: UIUX-Designer · Ticket: **CYP-12** (Epic CYP-3) · Status: **Entwurf — wartet auf Dev-Gegenlesen (CYP-6)** · Stand: 2026-06-26
> **Kanonischer Ort:** dieses Dokument im geteilten Repo `KMPCyppieAgents` unter `docs/STATE-VISUALIZATION.md`
> (geteilte Artefakte gehören ins Git-Repo mit `origin`, nicht in agent-lokale Ordner — PO-Konvention 2026-06-26).
> Begleit-Artefakte: `docs/design/state-tokens.json` (maschinenlesbare Tokens), `docs/design/i18n-keys.md` (Key-Liste + Konvention).
> **Brand:** CyppieAgents (eigene Identität, Anti-Hype). NeonFi nur als Referenz für bewährte Foundation-Patterns, **nicht** als wörtliche Basis.

Diese Spec definiert **was** das Agentenfenster für jeden Zustand zeigt und **wie ehrlich** es das tut. Sie ist gegen das reale UI-Modell des Renderers (CYP-6, `AgentEvent`) und die Wire-Events (CYP-9, `StreamJsonEvent`) gemappt — keine erfundenen Zustände. Sie schreibt **keine** Implementierung vor; die Compose-Umsetzung gehört Dev.

---

## 0. Verbindlicher Bezugsrahmen (verifiziert im Code, 2026-06-26)

- **Renderer-Modell (Mapping-Anker):** `app/shared/.../agentview/AgentEvent.kt` (Branch `feature/CYP-6-agent-window-renderer`):
  - `AssistantText(id, text, complete)` — Assistant-Turn-Text; `complete=false` = laufender Delta, `complete=true` = Turn-Ende.
  - `ToolCall(id, tool, summary, status)` mit `enum ToolStatus { RUNNING, OK, ERROR }` — eine Zeile, per gleicher `id` aktualisiert.
  - `Result(id, label, isError)` — Tool-/Turn-Ergebnis, distinct markiert.
  - `Notice(id, text)` — System-/Lifecycle-Hinweis (Session gestartet, Agent gestoppt, Key geändert).
- **Wire-Modell (Herkunft der Zustände):** `core/.../model/StreamJsonEvent.kt` (CYP-9): `SystemEvent(subtype=init)`, `AssistantEvent`, `UserEvent`, `ResultEvent(isError, subtype)`, `ToolUseBlock`/`ToolResultBlock` (gepaart über `tool_use_id`), `RateLimitEvent`. Der **Mediator** mappt `StreamJsonEvent → AgentEvent`.
- **Architektur (05):** Agentenfenster = strukturiertes `commonMain`-Compose-UI über dem Event-Strom — **kein** Terminal-Emulator. Eingabefeld = „Nachricht an den Agenten", kein Shell-Prompt.
- **Modul/Package:** `:app:shared`, Basis-Package `com.tneff.cyppieagents` (UI: `…app.shared`).

---

## 1. Zwei Ebenen von Zustand

Es gibt **zwei** klar getrennte Zustandsebenen. Sie nicht zu vermischen ist die wichtigste Design-Entscheidung hier.

| Ebene | Wo sichtbar | Quelle | Granularität |
|---|---|---|---|
| **A — Transcript-Event-Zustand** | In der scrollenden Transcript-Liste, pro Zeile | Direkt aus `AgentEvent` | pro Event |
| **B — Agent-Gesamtstatus** | Fenster-Titelleiste/Chrome + Desktop-Agenten-Liste (S2) | **Abgeleitet** aus dem Event-Strom | pro Agent (1 Wert) |

> **⚠ Lücke/Dev-Ask (wichtig):** Ebene B existiert im Code **noch nicht**. `AgentViewModel` (CYP-6) exponiert nur `transcript: StateFlow<List<AgentEvent>>`, **keinen** Agent-Gesamtstatus. Für „running/ok/error/idle/waiting-for-input am Agenten" braucht es einen **abgeleiteten `StateFlow<AgentStatus>`** (Vorschlag §3.3). Das ist mit Dev (CYP-6) über den PO abzustimmen, **bevor** der Desktop-Status (S2) gebaut wird — sonst implizite Design-Entscheidung → Rework.

---

## 2. Ebene A — Transcript-Event-Zustände

Jede Transcript-Zeile trägt **drei redundante Signalträger** (WCAG 1.4.1 „Use of Color"): **Farbe + Icon + Text-Label**. Farbe ist nie alleiniger Träger.

| `AgentEvent` | Zustand | Token (semantisch) | Icon | Label-Key | Wording-Hinweis (Disclosure) |
|---|---|---|---|---|---|
| `AssistantText` `complete=false` | **streaming** | `state.running` | animierter Cursor/Caret | `agent.state.streaming` | reiner Modell-Text = **advisory** |
| `AssistantText` `complete=true` | **complete** | `state.neutral` | — (Cursor verschwindet) | — | — |
| `ToolCall` `RUNNING` | **läuft** | `state.running` | Progress/Spinner | `tool.status.running` | „läuft" ist faktisch (Prozess offen) |
| `ToolCall` `OK` | **ausgeführt** | `state.ok` | Häkchen | `tool.status.ok` | **„ausgeführt", nicht „erfolgreich/korrekt"** (siehe §5) |
| `ToolCall` `ERROR` | **Fehler** | `state.error` | Warn-Dreieck | `tool.status.error` | faktisch (Tool meldete `is_error`) |
| `Result` `isError=false` | **abgeschlossen** | `state.ok` | Häkchen-Kreis | `result.success` | **„Turn abgeschlossen", nicht „Aufgabe erledigt"** |
| `Result` `isError=true` | **fehlgeschlagen** | `state.error` | Fehler-Kreis | `result.error` | faktisch; nie einklappen/verbergen |
| `Notice` | **Hinweis** | `state.notice` | Info | (Text kommt aus Event) | neutral, nicht alarmierend |

**Regeln:**
- **Fehler nie verbergen.** `ERROR`/`isError` sind immer expandiert/sichtbar, nie hinter „mehr anzeigen".
- **Streaming-Indikator ist Bewegung mit statischem Fallback** (Reduced-Motion respektieren, §4.4).
- **`OK` ≠ Erfolg.** Ein Häkchen am Tool-Call sagt nur: *das Tool kehrte ohne Fehler zurück.* Es sagt **nicht**, dass das Ergebnis korrekt/verifiziert ist. Label und Tooltip müssen das tragen (§5).

---

## 3. Ebene B — Agent-Gesamtstatus (abgeleitet)

### 3.1 Status-Set

**MVP `AgentStatus` (mit Dev bestätigt, abgeleitet aus dem Transcript):** `RUNNING` / `ERROR` / `IDLE` / `OFFLINE`.

| Status | Token | Icon | Label-Key | Bedeutung (ehrlich) |
|---|---|---|---|---|
| `RUNNING` | `state.running` | Progress | `agent.status.running` | Agent arbeitet (Turn aktiv: Assistant streamt **oder** Tool RUNNING) |
| `IDLE` | `state.idle` | Ruhe-Punkt | `agent.status.idle` | Turn abgeschlossen, **nichts** offen erwartet; nächster Turn jederzeit möglich |
| `ERROR` | `state.error` | Warn-Dreieck | `agent.status.error` | Letzter Turn endete mit `Result.isError` |
| `OFFLINE` | `state.offline` | Strich/leer | `agent.status.offline` | Session gestoppt/nicht verbunden (Lifecycle/`Notice`) |

> **`WAITING_FOR_INPUT` ist bewusst NICHT im MVP** (Token/Key/Icon stehen für später bereit). Begründung in §3.2 — kein explizites Signal im stream-json, deshalb nicht raten (Disclosure). Kommt erst mit der Eskalations-Naht (05 §5).

### 3.2 `IDLE` vs `WAITING_FOR_INPUT` — der Ehrlichkeits-Kern

Diese beiden **dürfen nicht synonym** verwendet werden:
- **`IDLE`** = „Turn fertig, ich erwarte nichts Bestimmtes." Default nach jedem erfolgreichen `Result`.
- **`WAITING_FOR_INPUT`** = „Ich bin **blockiert** und brauche eine Antwort, um fortzufahren."

> **Entschieden (Dev + PO, 2026-06-26):** Das stream-json-`ResultEvent` signalisiert **nicht explizit** „warte auf Input"; ein abgeschlossener Assistant-Turn ist technisch ununterscheidbar von „Agent stellt eine Rückfrage". Deshalb: Default nach `Result(success)` = **`IDLE`**; **`WAITING_FOR_INPUT` bleibt draußen**, bis die Eskalations-Naht (05 §5) ein **explizites Signal** liefert (`PermissionRequest`-Event o. ä.). Bis dahin **nicht raten** — sonst implizieren wir eine Rückfrage, die es nicht gibt (Disclosure-Verstoß).

### 3.3 Ableitungs-Vorschlag (für Dev, CYP-6)

Vorgeschlagene reine Funktion `deriveStatus(transcript): AgentStatus` bzw. ein zusätzlicher `StateFlow<AgentStatus>` im `AgentViewModel`:

```
letztes Event ist ToolCall(RUNNING) ............... RUNNING
es streamt ein AssistantText(complete=false) ...... RUNNING
letztes Result(isError=true) ...................... ERROR
letztes Result(isError=false) ..................... IDLE
Session gestoppt (Notice/Lifecycle) ............... OFFLINE
vor erstem Event .................................. IDLE
(WAITING_FOR_INPUT: erst mit explizitem Block-Signal, post-MVP)
```

Dies ist ein **Design-Vorschlag**, keine Implementierungsvorgabe — die Platzierung (ViewModel vs. Mediator) entscheidet Dev.

---

## 4. Design-Tokens

Vollständige, maschinenlesbare Werte in `docs/design/state-tokens.json`. Hier die Leitregeln.

### 4.1 Semantik statt Rohwert
Komponenten referenzieren **semantische** Tokens (`state.running`, `state.ok`, …), nie Hex direkt. Jedes semantische Token hat einen Wert für **dark** (Default des Agentenfensters) und **light**.

### 4.2 Kontrast (Pflicht)
- **Text/Label:** ≥ **4.5:1** gegen die jeweilige Fläche (WCAG AA).
- **Icons/Nicht-Text-UI:** ≥ **3:1** (WCAG 1.4.11).
- Die in `state-tokens.json` gewählten Hex-Werte sind so gewählt, dass sie diese Schwellen treffen sollen; **automatische Kontrast-Validierung steht aus** (Dev/QA, vor „Fertig"). Werte sind Vorschlag.

### 4.3 Farbe nie allein
Jeder Zustand = **Farbe + Icon + Text**. Damit ist die Darstellung auch bei Farbfehlsichtigkeit und in Graustufen eindeutig — unabhängig vom exakten Hex.

### 4.4 Motion
- `RUNNING`/streaming = dezente Animation (Spinner / pulsierender Caret).
- **Reduced-Motion** (`prefers-reduced-motion` / Plattform-Flag): statischer Indikator (z. B. statisches Progress-Icon), keine Endlos-Animation.

### 4.5 Typografie
- Status-Labels: Label/Caption-Stil (kompakt, gut lesbar).
- Tool-`summary` (z. B. Bash-Kommando): **monospace**, einzeilig mit Ellipsis; voller Text per Tooltip/Expand.

---

## 5. Disclosure-Honesty (verbindlich)

Die Statusanzeige darf nie eine **Garantie** suggerieren, die nicht existiert. Trennung **garantiert/faktisch** vs. **advisory**:

| Signal | Kategorie | Ehrliche Aussage | Verboten |
|---|---|---|---|
| `ToolCall OK` | **faktisch** | „Tool ohne Fehler ausgeführt" | „erfolgreich", „korrekt", „verifiziert" |
| `ToolCall/Result ERROR` | **faktisch** | „Fehler / fehlgeschlagen" | Verbergen, Einklappen, Beschönigen |
| `Result success` | **faktisch (eng)** | „Turn abgeschlossen" | „Aufgabe erledigt", „fertiggestellt" |
| `AssistantText` | **advisory** | Modell-Aussage (Selbst-Einschätzung) | als System-/Plattform-Garantie darstellen |
| `WAITING_FOR_INPUT` | nur mit echtem Signal | „Agent ist blockiert" | als Default nach jedem Turn raten |

**Konkrete Wortregeln (Resource-Werte):**
- DE: `tool.status.ok` = **„Ausgeführt"** (nicht „Erfolgreich"). `result.success` = **„Turn abgeschlossen"** (nicht „Erledigt").
- EN: `tool.status.ok` = **“Executed”** (not “Success”). `result.success` = **“Turn complete”** (not “Done”).
- Advisory Modell-Text wird visuell als Agenten-Aussage gerahmt (Absender = Agent), nie als Plattform-Banner.

> Dies deckt sich mit der Anti-Hype-Brand (01 §7.2 / 05): „du behältst Kontrolle", keine „magische" Erfolgs-Suggestion.

---

## 6. Resource-/Lokalisierungs-Keys

Konvention + vollständige Key-Liste mit DE/EN-Werten: **`docs/design/i18n-keys.md`**.

Kurz: Sprachen **`de` (Quelle/Default) + `en`**. **Mechanismus (mit Dev bestätigt): `compose.resources`** → reale Keys sind **identifier-safe (Underscore)**, z. B. `Res.string.agent_status_running`. Die gepunktete Form (`agent.status.*`) bleibt als **menschlicher Namespace**; die reale Underscore-Key-Spalte steht in `i18n-keys.md`.

> **⚠ Shared-Key-Drift (Pflicht-Flag, meine Rolle):** Sobald diese Keys in `:app:shared`-Resources landen, muss das **konsumierende Modul (CYP-6-Renderer) re-syncen**, sonst bricht ein geteilter Check. **Empfehlung: Key-Lieferung mit der CYP-6-Umsetzung timen**, nicht isoliert mergen.

---

## 7. Entscheidungen & offene Punkte

**Entschieden (Dev + PO, 2026-06-26):**
1. ✅ **`AgentStatus` = RUNNING / ERROR / IDLE / OFFLINE**, abgeleitet aus dem Transcript (§3.3). Platzierung (ViewModel vs. Mediator) liegt bei Dev.
2. ✅ **`WAITING_FOR_INPUT` bleibt draußen**, bis die Eskalations-Naht (05 §5) ein explizites Signal liefert.
3. ✅ **Resource-Mechanismus = `compose.resources`** → Underscore-Keys (siehe `i18n-keys.md`).

**Offen:**
4. **Kontrast-Validierung** der Hex-Werte automatisieren (QA/Dev) vor „Fertig".
5. **Theme-Basis:** dark als Default des Agentenfensters bestätigt? Light-Werte sind mitgeliefert.
