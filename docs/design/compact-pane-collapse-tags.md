# Compact Pane-Collapse — testTags (CYP-156, Klasse A)

> docs-only · Grounded @ develop `d57dccb`. Tag-Schema (Test-Contract): `<area>[.<scopeId>].<element>`.
> **Geteilter QA-Vertrag mit CYP-7 — Tags nie still umbenennen/ergänzen.**

## Reuse — unverändert (nur der Layout-Container wechselt, der Knoten bleibt)
Die Single-Pane-Variante zeigt **dieselben** Knoten wie heute, nur Liste **oder** Detail statt
nebeneinander. Die Tags bleiben → bestehende Assertions (CYP-7) gelten weiter.

| Knoten | Tag | Quelle |
|---|---|---|
| Kanal-Liste | `comm.channelList` (`CommTags.CHANNEL_LIST`) | bestehend |
| Kanal-Zeile | `comm.channel.<id>` (`CommTags.channel`) | bestehend |
| Timeline | `comm.timeline` (`CommTags.TIMELINE`) | bestehend |
| Composer | `comm.composerInput`/`comm.composerSend`/`comm.composerReadonly` | bestehend |
| ProductLead Snapshot-Liste | `productLead.list` (`ProductLeadTags.LIST`) | bestehend |
| ProductLead Snapshot | `productLead.snapshot.<id>` (`ProductLeadTags.snapshot`) | bestehend |
| ProductLead Detail | `productLead.detail` (`ProductLeadTags.DETAIL`) | bestehend |
| ProductLead TriggerBar | `productLead.trigger*` | bestehend (bleibt oberhalb, immer sichtbar) |

## NEU — ein Knoten je Panel (Single-Pane-„Zurück"), QA/CYP-7-koordiniert (NICHT still)
Heute gibt es **keinen** Back-Knoten in CommPanel/ProductLead (beide sind immer Two-Pane). Die Single-Pane-
Variante braucht eine **neue** „Zurück"-Affordanz (Liste ↔ Detail) → ein neuer interaktiver Knoten je Panel.
**Präzedenz:** `eventlog/EventBrowseTags.kt` hat bereits `const val BACK = "eventBrowse.back"` für genau
dieses Single-Pane-Muster.

| Knoten | Vorgeschlagener Tag | Begründung |
|---|---|---|
| CommPanel Single-Pane Zurück | `comm.back` (`CommTags.BACK`) | folgt `eventBrowse.back`-Präzedenz |
| ProductLead Single-Pane Zurück | `productLead.back` (`ProductLeadTags.BACK`) | dito |

- **Disclosure-Korrektur ggü. Audit-Headline:** das Audit (`uiux/COMPACT-WIDTH-AUDIT-DESIGN.md`) sagte „NULL
  neue Tags" — beim Code-Abgleich zeigt sich: die **Inhalts**-Knoten brauchen keine, aber der **Back**-Knoten
  ist neu (kein `comm.back`/`productLead.back` heute). Diese 2 Tags sind **bewusst neu**, dem QA/CYP-7-Vertrag
  gemeldet — kein stiller Tag. (Text bleibt `comm_back`-Reuse, s. keys.md.)

## a11y
- `comm_back` trägt bereits `contentDescription`; der neue Back-Knoten nutzt es. Fokus-Führung: Auswahl →
  Detail, Zurück → Liste.
