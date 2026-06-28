# i18n-Keys — Per-Fenster-Badges (v0.1)

> Owner: UIUX-Designer · Ticket: **CYP-55** · Status: **Entwurf** · Stand: 2026-06-28
> Begleitend zu `docs/WINDOW-BADGES.md`. `compose.resources` → **Underscore-Real-Keys** (gepunktete Form = menschlicher Namespace). Platzhalter positional (`%1$s`).
> **Reuse-First:** Fenster-**Titel** aus `WindowState.title` (kein Key). Severity-**Namen** aus CYP-34 (`event-log-keys.md`) — **nicht** duplizieren. Pager-Badge-Wortlaut aus CYP-54 wird hier real verdrahtet.

## 1. Count-Badge (Comm — B1 „neu seit zuletzt")

| Namespace (human) | Real-Key | DE (Quelle) | EN |
|---|---|---|---|
| `pager.activity.badge` | `pager_activity_badge` | %1$s neu | %1$s new |
| `badge.count.overflow` | `badge_count_overflow` | 9+ | 9+ |

> **Reuse:** `pager_activity_badge` ist in **CYP-54** (`phone-pager-keys.md`) vorgesehen und wurde dort als „**mit CYP-55 timen**" deferred. CYP-55 ist die Stelle, an der er **real** in `:app:shared` landet (DE+EN) — Count-Badge in Titelleiste **und** Pager-Aktivitäts-Badge nutzen denselben Wortlaut. **Kein** zweiter „neu"-Key.

## 2. Accessibility-Keys (Farbe nie alleiniger Träger)

| Namespace (human) | Real-Key | DE | EN |
|---|---|---|---|
| `a11y.badge.unread` | `a11y_badge_unread` | %1$s: %2$s neue Nachrichten | %1$s: %2$s new messages |
| `a11y.badge.severity` | `a11y_badge_severity` | %1$s: höchste Severity %2$s | %1$s: highest severity %2$s |
| `a11y.badge.error` | `a11y_badge_error` | %1$s: Agent-Fehler | %1$s: agent error |
| `a11y.badge.attention` | `a11y_badge_attention` | %1$s: stockt evtl., ggf. Eingabe nötig | %1$s: may be stalled, input may be needed |

> `%1$s` = `WindowState.title` (Fenster-/Agentname). `%2$s` = Zahl bzw. Severity-Name. Der Screenreader nennt **Fenstername + Bedeutung** („Frontend: 3 neue Nachrichten", „Event-Log: höchste Severity Fehler"), nicht nur „Badge".
> `a11y_badge_severity` `%2$s` = Severity-Name **aus CYP-34** (`event-log-keys.md`: error/warn/info/debug-Labels) — **Reuse**, kein neuer Severity-String.

## 3. Disclosure-kritische Wortwahl (verbindlich)

- **`pager_activity_badge` = „%1$s neu"** ist ein **Aufmerksamkeits-Hinweis** („es gibt Neues"), **nicht** „zugestellt/erledigt/ungelesen-von-Record". Bei B1 bedeutet die Zahl „**neu seit du zuletzt hingesehen hast**" (Session-Aktivität) — der a11y-Text bleibt bewusst „neue Nachrichten" (ehrlich für Aktivität), und der Badge **verschwindet bei Fokus** (Hinweis erledigt).
- **`a11y_badge_attention`** beschreibt einen **Stall-Hinweis** (A2), **keine erfundene Frage** — Wortlaut „stockt evtl., ggf. Eingabe nötig", **nicht** „Agent fragt …". (`WAITING_FOR_INPUT` wird **nicht** gefakt.)
- **`a11y_badge_error`** nur bei echtem `AgentStatus.ERROR` (aus dem Transkript ableitbar).
- **Fail-closed:** kein erlaubtes/ehrliches Aggregat → **kein** Badge → **kein** a11y-Knoten (kein „0 neue Nachrichten" annonciert).

> **⚠ Shared-Key-Drift:** Keys landen in `:app:shared`-Resources → CYP-55-Impl + Test-Modul (CYP-7) müssen re-syncen. **Mit der Impl timen.** DE+EN-Parität ist Pflicht. `pager_activity_badge`: mit dem CYP-54/CYP-50-Stand abgleichen, dass er **genau einmal** definiert wird.
