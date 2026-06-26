# Accessibility-Keys — Fenster-Manager & Agent-Renderer (v0.1)

> Owner: UIUX-Designer · Tickets: **CYP-22** (Fenster, iOS-Dev) + **CYP-23** (Renderer, Dev) · Stand: 2026-06-26
> **Kanonischer Ort:** geteiltes Repo `KMPCyppieAgents` unter `docs/design/a11y-keys.md` (Quelle statt Chat — Team-Konvention).
> Mechanismus: **`compose.resources`** → identifier-safe **Underscore-Real-Keys**; Platzhalter positional (`%1$s`).
> Begleitend zu `docs/STATE-VISUALIZATION.md` (CYP-12, Disclosure-Wortregeln) und `docs/COLOR-CODING.md` (CYP-14).

Diese Keys machen Zustände **ohne Farbe/Glyph** zugänglich (WCAG 1.3.1/4.1.2; CYP-12 „Farbe nie allein"). Sie ersetzen hartcodierte Strings im UI durch lokalisierte Resources.

---

## 1. Fenster-a11y → CYP-22 (iOS-Dev)

Ersetzt die hartcodierten DE-Strings in `app/shared/.../window/WindowManager.kt` (Fenster-`contentDescription`, Titelleiste, Resize-Griff). `%1$s` = `window.title`.

| Real-Key | DE (Quelle) | EN | Verwendung |
|---|---|---|---|
| `a11y_window_label` | Agentenfenster %1$s | Agent window %1$s | Fenster-Root (`heading()` + contentDescription) |
| `a11y_window_titlebar` | Titelleiste %1$s, mit Pfeiltasten verschieben | Title bar %1$s, move with arrow keys | Titelleiste |
| `a11y_window_resize` | Größe ändern, %1$s | Resize, %1$s | Resize-Griff |
| `a11y_window_focused` | %1$s, fokussiert | %1$s, focused | Fokus-Ansage (N1, ergänzend zum visuellen Fokus-Outline) |

---

## 2. Transcript-a11y → CYP-23 (Dev, CYP-6-Folge)

Für die Zeilen in `app/shared/.../agentview/AgentWindow.kt`. Statt nur Glyph (`⟳/✓/✗`, `▌`) + Farbe ein `contentDescription` über diese Keys setzen. `%1$s` = `event.tool` (Tool-Zeilen) bzw. `event.label` (Result-Fehler).

| Real-Key | DE (Quelle) | EN | Zeile |
|---|---|---|---|
| `a11y_assistant_streaming` | Agent schreibt | Agent is typing | `AssistantText` (Cursor `▌`) |
| `a11y_tool_running` | Werkzeug läuft: %1$s | Tool running: %1$s | `ToolCall` ⟳ RUNNING |
| `a11y_tool_ok` | Werkzeug ausgeführt: %1$s | Tool executed: %1$s | `ToolCall` ✓ OK |
| `a11y_tool_error` | Werkzeug-Fehler: %1$s | Tool error: %1$s | `ToolCall` ✗ ERROR |
| `a11y_result_success` | Ergebnis: Turn abgeschlossen | Result: turn complete | `Result` (isError=false) |
| `a11y_result_error` | Ergebnis: fehlgeschlagen — %1$s | Result: failed — %1$s | `Result` (isError=true) |
| `a11y_notice` | Hinweis: %1$s | Notice: %1$s | `Notice` |
| `a11y_agent_status` | Agentenstatus: %1$s | Agent status: %1$s | AgentStatus-Chip (CYP-12 Ebene B) |

---

## 3. Disclosure-Wortregeln (verbindlich — nicht ohne UX-Review ändern)

- `a11y_tool_ok` = **„ausgeführt"/„executed"** — NICHT „erfolgreich/success" (OK = ohne Fehler zurückgekehrt, ≠ korrekt/verifiziert).
- `a11y_result_success` = **„Turn abgeschlossen"/„turn complete"** — NICHT „erledigt/done" (Turn-Ende ≠ Aufgabe korrekt erledigt).
- Status wird über Text zugänglich gemacht, ohne eine Garantie zu suggerieren (CYP-12 §5).

## 4. Shared-Key-Drift (Pflicht-Hinweis)

Beide Sets landen in `:app:shared`-Resources. Das jeweils **konsumierende Modul muss re-syncen**, sonst bricht ein geteilter Check. **Konsumenten timen ihren Re-Sync mit ihrem Ticket** (CYP-22 bzw. CYP-23) — nicht isoliert mergen.
