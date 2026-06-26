# i18n-Keys — Zustands-Visualisierung (v0.1)

> Owner: UIUX-Designer · Ticket: **CYP-12** · Status: **Mechanismus bestätigt (compose.resources)** · Stand: 2026-06-26
> Begleitend zu `docs/STATE-VISUALIZATION.md`. **Brand:** CyppieAgents (Anti-Hype).

## 1. Konvention

- **Sprachen:** `de` = **Quelle/Default**, `en` = vollständige Zweitsprache. (RTL später; Keys/Layout schon RTL-tauglich denken.)
- **Mechanismus (mit Dev bestätigt): `compose.resources`** → reale Keys müssen **identifier-safe** sein (Buchstaben/Ziffern/Underscore). Zugriff `Res.string.<key>`.
- **Zwei Spalten:** „Namespace (human)" = die gepunktete Lesart zur Gruppierung; **„Real-Key" = die tatsächliche Underscore-Form** in `strings.xml`/compose.resources. Nur die Real-Key-Spalte ist verbindlich.
- **Keine UI-Strings hartcodiert** — alle nutzerlesbaren Zustands-Texte über Keys.
- **Platzhalter:** compose.resources nutzt positional args (`%1$s`); `{tool}`/`{status}` unten = Lesehilfe für die Position.

> **⚠ Shared-Key-Drift:** Diese Keys landen in `:app:shared`-Resources → konsumierendes Modul (CYP-6) muss re-syncen, sonst bricht ein geteilter Check. **Lieferung mit CYP-6-Umsetzung timen.**

## 2. Key-Liste (Wording trägt Disclosure-Honesty — siehe Spec §5)

| Namespace (human) | Real-Key (compose.resources) | DE (Quelle) | EN |
|---|---|---|---|
| `agent.state.streaming` | `agent_state_streaming` | Schreibt… | Typing… |
| `tool.status.running` | `tool_status_running` | Läuft… | Running… |
| `tool.status.ok` | `tool_status_ok` | Ausgeführt | Executed |
| `tool.status.error` | `tool_status_error` | Fehler | Error |
| `result.success` | `result_success` | Turn abgeschlossen | Turn complete |
| `result.error` | `result_error` | Fehlgeschlagen | Failed |
| `agent.status.running` | `agent_status_running` | Arbeitet | Working |
| `agent.status.idle` | `agent_status_idle` | Bereit | Idle |
| `agent.status.error` | `agent_status_error` | Fehler | Error |
| `agent.status.offline` | `agent_status_offline` | Offline | Offline |

> **`agent_status_waiting`** („Wartet auf Eingabe" / „Waiting for input") ist **vorbereitet, aber post-MVP** — erst mit explizitem Block-Signal ausliefern (Spec §3.2). Nicht in die erste Key-Lieferung aufnehmen.

### Disclosure-kritische Wortwahl (nicht ändern ohne UX-Review)
- `tool_status_ok` = **„Ausgeführt"/„Executed"** — NICHT „Erfolgreich/Success" (OK = ohne Fehler zurückgekehrt, ≠ korrekt/verifiziert).
- `result_success` = **„Turn abgeschlossen"/„Turn complete"** — NICHT „Erledigt/Done" (Turn-Ende ≠ Aufgabe korrekt erledigt).

## 3. Accessibility-Keys (Screenreader-Labels)

| Namespace (human) | Real-Key | DE | EN |
|---|---|---|---|
| `a11y.tool.running` | `a11y_tool_running` | Werkzeug läuft: %1$s | Tool running: %1$s |
| `a11y.tool.ok` | `a11y_tool_ok` | Werkzeug ausgeführt: %1$s | Tool executed: %1$s |
| `a11y.tool.error` | `a11y_tool_error` | Werkzeug-Fehler: %1$s | Tool error: %1$s |
| `a11y.agent.status` | `a11y_agent_status` | Agentenstatus: %1$s | Agent status: %1$s |
| `a11y.streaming` | `a11y_streaming` | Agent schreibt | Agent is typing |

> Screenreader-Labels machen Zustand **ohne Farbe** zugänglich — ergänzt „Farbe nie allein" (Spec §4.3).
