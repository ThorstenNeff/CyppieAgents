# i18n-Keys — Event-Log-UI (Browse + Live-Tail) (v0.1)

> Owner: UIUX-Designer · Tickets: **CYP-41** (Browse) + **CYP-42** (Live-Tail), Epic **CYP-34** · Status: **Entwurf** · Stand: 2026-06-27
> Begleitend zu `docs/EVENT-LOG-UI.md` + `docs/design/event-log-tokens.json`.
> Mechanismus: **`compose.resources`** → identifier-safe **Underscore-Real-Keys** (gepunktete Form = menschlicher Namespace). Platzhalter **positional** (`%1$s`).
> **Reuse:** `comm_back` (Single-Pane Zurück) kommt aus CYP-17 (`comm-panel-keys.md`) — hier **nicht** dupliziert. Severity-Hues/Status-Wortregeln aus CYP-12.

Diese Keys machen Zustände **ohne Farbe/Glyph** zugänglich (WCAG 1.3.1/4.1.2; „Farbe nie allein") und ersetzen hartcodierte Strings.

---

## 1. Severity-Labels (event.severity.*)

| Namespace (human) | Real-Key | DE (Quelle) | EN |
|---|---|---|---|
| `event.severity.error` | `event_severity_error` | Fehler | Error |
| `event.severity.warn` | `event_severity_warn` | Warnung | Warning |
| `event.severity.info` | `event_severity_info` | Info | Info |
| `event.severity.debug` | `event_severity_debug` | Debug | Debug |

## 2. Browse-UI — Filter, Detail, Drilldown (CYP-41)

| Namespace (human) | Real-Key | DE | EN |
|---|---|---|---|
| `event.title` | `event_title` | Event-Log | Event log |
| `event.empty` | `event_empty` | Keine Events | No events |
| `event.filter.active` | `event_filter_active` | Filter aktiv – Teilmenge | Filter active – subset |
| `event.filter.agent` | `event_filter_agent` | Agent | Agent |
| `event.filter.type` | `event_filter_type` | Typ | Type |
| `event.filter.severity` | `event_filter_severity` | Severity | Severity |
| `event.filter.timewindow` | `event_filter_timewindow` | Zeitfenster | Time window |
| `event.filter.correlation` | `event_filter_correlation` | Korrelations-ID | Correlation ID |
| `event.load.more` | `event_load_more` | Mehr laden | Load more |
| `event.detail.source_ts` | `event_detail_source_ts` | Beobachtet: %1$s | Observed: %1$s |
| `event.drilldown.show_run` | `event_drilldown_show_run` | Zeig den ganzen Lauf | Show the whole run |
| `event.drilldown.show_session` | `event_drilldown_show_session` | Zeig die ganze Session | Show the whole session |
| `event.drilldown.correlated_by` | `event_drilldown_correlated_by` | Korreliert über %1$s | Correlated by %1$s |
| `event.usage.banded_hint` | `event_usage_banded_hint` | Token-Stände gesampelt (10%-Bänder) | Token levels sampled (10% bands) |
| `event.type.unknown` | `event_type_unknown` | Unbekannter Typ: %1$s | Unknown type: %1$s |

## 3. Live-Tail-UI (CYP-42)

| Namespace (human) | Real-Key | DE | EN |
|---|---|---|---|
| `event.tail.title` | `event_tail_title` | Live-Tail | Live tail |
| `event.tail.live` | `event_tail_live` | Live | Live |
| `event.tail.pause` | `event_tail_pause` | Pause | Pause |
| `event.tail.resume` | `event_tail_resume` | Fortsetzen | Resume |
| `event.tail.paused` | `event_tail_paused` | Pausiert | Paused |
| `event.tail.buffered_count` | `event_tail_buffered_count` | %1$s neue (pausiert) | %1$s new (paused) |
| `event.tail.buffer_overflow` | `event_tail_buffer_overflow` | Puffer voll – älteste pausierte Events verworfen | Buffer full – oldest paused events dropped |
| `event.tail.trimmed` | `event_tail_trimmed` | Ältere getrimmt – %1$s | Older trimmed – %1$s |

## 4. Verbindung & Lücken (Disclosure-kritisch — beide UIs)

| Namespace (human) | Real-Key | DE | EN |
|---|---|---|---|
| `event.gap.dropped` | `event_gap_dropped` | %1$s Events verworfen – Log in diesem Fenster unvollständig | %1$s events dropped – log incomplete in this window |
| `event.connection.offline` | `event_connection_offline` | Verbindung getrennt – Stand %1$s | Disconnected – as of %1$s |
| `event.access.denied` | `event_access_denied` | Nur für Operatoren | Operators only |

## 5. Accessibility-Keys (Farbe/Icon nie alleiniger Träger)

`%1$s`=severity, `%2$s`=type, `%3$s`=agentId, `%4$s`=Zeit. Macht die Event-Zeile (`docs/EVENT-LOG-UI.md` §4) screenreader-tauglich.

| Namespace (human) | Real-Key | DE | EN |
|---|---|---|---|
| `a11y.event.row` | `a11y_event_row` | %1$s, %2$s, von %3$s, %4$s | %1$s, %2$s, from %3$s, %4$s |
| `a11y.event.gap` | `a11y_event_gap` | Lücke: %1$s Events verworfen | Gap: %1$s events dropped |
| `a11y.event.correlation` | `a11y_event_correlation` | Korrelations-ID %1$s, Lauf anzeigen | Correlation ID %1$s, show run |
| `a11y.event.tail.status` | `a11y_event_tail_status` | Live-Tail-Status: %1$s | Live tail status: %1$s |

---

## 6. Disclosure-kritische Wortwahl (verbindlich — nicht ohne UX-Review ändern)

- **`event_gap_dropped` benennt die Lücke explizit** als „unvollständig" — Telemetrie darf Unvollständigkeit nie kaschieren (PRD §3.3, „No silent caps"). Gilt auch clientseitig: `event_tail_buffer_overflow`.
- **`event_detail_source_ts` = „Beobachtet"**, nicht die maßgebliche Zeit — `sourceTs` ist informativ, `ts`/`seq` ordnen (PRD §5).
- **`event_usage_banded_hint`** stellt klar: Token-Stände sind **gesampelt** (Band-Stützstellen), keine kontinuierliche Kurve (PRD §4.2).
- **`event_tail_paused`/`event_tail_live`** trennen sauber: pausiert ≠ live — die Ansicht nie als aktuell ausgeben, solange eingefroren.
- **`event_access_denied` = „Nur für Operatoren"** — fail-closed, kein Teildaten-Hinweis (PRD §7).
- **`event_filter_active`** verhindert, dass eine gefilterte Teilmenge als „nichts passiert" fehlgelesen wird.

## 7. Shared-Key-Drift (Pflicht-Hinweis)

Beide Sets landen in `:app:shared`-Resources. Das jeweils **konsumierende Modul (CYP-41- bzw. CYP-42-Impl) muss re-syncen**, sonst bricht ein geteilter Check. **Konsumenten timen ihren Re-Sync mit ihrem Ticket** — nicht isoliert mergen.
