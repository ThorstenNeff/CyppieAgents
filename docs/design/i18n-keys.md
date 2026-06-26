# i18n-Keys — Zustands-Visualisierung (Vorschlag v0.1)

> Owner: UIUX-Designer · Ticket: **CYP-12** · Status: **Vorschlag — kein bestehender Projekt-Standard** · Stand: 2026-06-26
> Begleitend zu `docs/STATE-VISUALIZATION.md`. **Brand:** CyppieAgents (Anti-Hype).

## 1. Konventions-Vorschlag

- **Sprachen:** `de` = **Quelle/Default**, `en` = vollständige Zweitsprache. (RTL später; Keys/Layout schon RTL-tauglich denken.)
- **Namespacing:** punkt-getrennt, kleingeschrieben: `<bereich>.<gruppe>.<zustand>`.
  Bereiche: `agent.state.*` (Transcript-Event), `agent.status.*` (Agent-Gesamtstatus), `tool.status.*`, `result.*`, `a11y.*` (Screenreader).
- **Keine UI-Strings hartcodiert** — alle nutzerlesbaren Zustands-Texte über Keys.
- **Platzhalter** im ICU-Stil, falls nötig: `{name}`, `{tool}`.
- **Mechanismus-neutral:** Key-**Namen** sind unabhängig von compose.resources vs. moko-resources. Finaler Mechanismus mit Dev (`:app:shared`) bestätigen.

> **⚠ Shared-Key-Drift:** Diese Keys landen in `:app:shared`-Resources → konsumierendes Modul (CYP-6) muss re-syncen, sonst bricht ein geteilter Check. **Lieferung mit CYP-6-Umsetzung timen.**

## 2. Key-Liste (Wording trägt Disclosure-Honesty — siehe Spec §5)

| Key | DE (Quelle) | EN |
|---|---|---|
| `agent.state.streaming` | Schreibt… | Typing… |
| `tool.status.running` | Läuft… | Running… |
| `tool.status.ok` | Ausgeführt | Executed |
| `tool.status.error` | Fehler | Error |
| `result.success` | Turn abgeschlossen | Turn complete |
| `result.error` | Fehlgeschlagen | Failed |
| `agent.status.running` | Arbeitet | Working |
| `agent.status.idle` | Bereit | Idle |
| `agent.status.waiting` | Wartet auf Eingabe | Waiting for input |
| `agent.status.error` | Fehler | Error |
| `agent.status.offline` | Offline | Offline |

### Disclosure-kritische Wortwahl (nicht ändern ohne UX-Review)
- `tool.status.ok` = **„Ausgeführt"/„Executed"** — NICHT „Erfolgreich/Success" (OK = ohne Fehler zurückgekehrt, ≠ korrekt/verifiziert).
- `result.success` = **„Turn abgeschlossen"/„Turn complete"** — NICHT „Erledigt/Done" (Turn-Ende ≠ Aufgabe korrekt erledigt).
- `agent.status.waiting` nur zeigen, wenn echtes Block-Signal vorliegt (Spec §3.2).

## 3. Accessibility-Keys (Screenreader-Labels)

| Key | DE | EN |
|---|---|---|
| `a11y.tool.running` | Werkzeug läuft: {tool} | Tool running: {tool} |
| `a11y.tool.ok` | Werkzeug ausgeführt: {tool} | Tool executed: {tool} |
| `a11y.tool.error` | Werkzeug-Fehler: {tool} | Tool error: {tool} |
| `a11y.agent.status` | Agentenstatus: {status} | Agent status: {status} |
| `a11y.streaming` | Agent schreibt | Agent is typing |

> Screenreader-Labels machen Zustand **ohne Farbe** zugänglich — ergänzt „Farbe nie allein" (Spec §4.3).
