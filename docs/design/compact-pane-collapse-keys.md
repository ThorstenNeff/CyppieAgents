# Compact Pane-Collapse — i18n Keys (CYP-156, Klasse A)

> docs-only · Grounded @ develop `d57dccb`.

## Neue Keys: **KEINE**

Klasse A ist eine reine **Layout-Anpassung** (Two-Pane → Single-Pane unter `PANE_COLLAPSE_WIDTH`). Es kommt
kein neuer Text hinzu.

## Reuse

| Zweck | Key | Quelle | Status |
|---|---|---|---|
| Single-Pane „Zurück" (Comm + ProductLead) | `comm_back` | bestehend (values/ + values-en/) | **Reuse** — bereits von `acl/AclPanel.kt` und `eventlog/EventBrowsePanel.kt` (Z. 297) im Single-Pane-Back genutzt |

- **DE/EN-Parität:** `comm_back` existiert in beiden Sätzen — keine Aktion.
- **Kollision:** keine (kein neuer Key).

## §-Ask (PO)
- **Default = `comm_back` für beide.** Falls für den ProductLead-Single-Pane-Zurück ein report-eigener
  Wortlaut gewünscht ist, wird **ein** neuer Key koordiniert (z. B. `report_back`) — sonst Reuse. `comm_back`
  ist semantisch generisch genug („‹ Zurück").
