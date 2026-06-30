# Event-Log Compact-Dichte — i18n Keys (CYP-158, Klasse B)

> docs-only · Grounded @ develop `d57dccb`.

## Neue Keys: **KEINE**

Klasse B ist eine reine **Layout-Anpassung** (Chip-`Row` → `FlowRow`; EventRow `Row` → deterministische
2-Zeilen-Gruppierung; Pane-Threshold 560→600). Es ändert sich **kein Text** — alle Chips, Labels, Severity-,
Typ- und Zeit-Strings bleiben identisch.

## Reuse / unverändert
| Bereich | Keys | Status |
|---|---|---|
| Filter-Chips (Browse) | `event_filter_project/_agent/_type/_severity/_timewindow/_correlation`, `event_filter_active`, `event_view_project/_all_projects`, `event_filter_project_all` | unverändert, nur Container FlowRow |
| Tail-Header | `event_tail_pause/_resume/_paused/_live`, `event_filter_*` | unverändert |
| Event-Zeile | `event_severity_*`, `a11y_event_row`, `a11y_event_gap`, `event_row_project`, `event_gap_dropped` | unverändert (a11y-Text bleibt am äußeren Container) |

- **DE/EN-Parität:** keine Änderung.
- **Kollision:** keine (kein neuer Key).
