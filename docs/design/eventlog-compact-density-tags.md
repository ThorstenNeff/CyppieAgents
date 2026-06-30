# Event-Log Compact-Dichte — testTags (CYP-158, Klasse B)

> docs-only · Grounded @ develop `d57dccb`. Geteilter QA-Vertrag mit CYP-7 — nie still umbenennen.

## Neue Tags: **KEINE**

Klasse B fügt **keinen** neuen interaktiven Knoten hinzu — nur Layout-Container wechseln (`Row` → `FlowRow`;
`Row` → 2-zeilige Gruppierung). Alle bestehenden Knoten behalten ihre Tags → CYP-7-Assertions gelten weiter.
Der Single-Pane-Back existiert bereits (`eventBrowse.back`).

## Reuse — unverändert (Container wechselt, Knoten + Tag bleiben)
| Knoten | Tag | Quelle |
|---|---|---|
| Filter-Bar | `eventBrowse.filterBar` (`EventBrowseTags.FILTER_BAR`) | bestehend (Row → FlowRow) |
| Filter-Chips | `eventBrowse.filter.project/agent/type/severity/timeWindow/correlation` | bestehend |
| Event-Tabelle | `eventBrowse.table` (`EventBrowseTags.TABLE`) | bestehend |
| Event-Zeile (Browse) | `eventBrowse.row.<i>` + `.<qualifier>` + rowById | bestehend (`rowTag`/`qualifierTag`/`byIdTag`) |
| Projekt-Zelle | `eventBrowse.row.<i>.project` (`rowProject`) | bestehend |
| Tail-Header-Chips | `eventTail.filter.agent/type/severity/project`, `eventTail.pauseToggle`, `eventTail.live/paused` | bestehend (Chips → FlowRow) |
| Event-Zeile (Tail) | `eventTail.row.<i>` + qualifier + rowById + rowProject | bestehend |
| Single-Pane Zurück | `eventBrowse.back` (`EventBrowseTags.BACK`) | bestehend |

## Wichtig — EventRow-Reflow erhält die Tag-Struktur
In der 2-Zeilen-Variante bleiben **dieselben Kind-Knoten** mit **denselben** Tags, nur auf zwei visuelle
Zeilen verteilt:
- Zeile 1: Severity-Rail (`qualifierTag`), Zeitstempel, Typ (`byIdTag`).
- Zeile 2: Avatar/Name, Korrelation, Projekt (`projectTag`).

`rowTag` bleibt auf dem **äußeren** Container (egal ob `Row` oder `Column`-aus-2-`Row`s), ebenso die
`contentDescription`. **Kein Tag-Rename, kein neuer Tag.**
