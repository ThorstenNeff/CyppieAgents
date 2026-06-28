# Product-Lead Report — i18n-Keys (CYP-89 / CYP-90)

> Owner: UIUX-Designer · Epic CYP-77 · Stand 2026-06-28 · Status: Vorschlag — wartet auf Dev-Gegenlesen
> Konvention (verifiziert gg. `app/shared/.../composeResources/values/strings.xml`): **Underscore-Realkeys** (keine Punkte), positionsbasierte Argumente `%1$s`. **DE = Default** (`values/`), **EN** (`values-en/`). Parität Pflicht.
> Modul: `:app:shared` compose.resources → **Shared-Key-Sync mit der Impl timen** (CYP-90).

## Neue Keys
### Fenster & Trigger
| Key | DE | EN |
|---|---|---|
| `report_title` | Product-Lead-Berichte | Product-Lead reports |
| `report_generate` | Bericht erzeugen | Generate report |
| `report_generating` | Wird erzeugt… | Generating… |
| `report_type_usage` | Nutzungs-Leitfaden | Usage guide |
| `report_type_status` | Status-Bericht | Status report |
| `report_type_defects` | Defekt- & Lücken-Register | Defect & gap register |

### Liste & Detail
| Key | DE | EN |
|---|---|---|
| `report_as_of` | Stand: %1$s | As of: %1$s |
| `report_provenance` | Quellen: %1$s · Zeitraum: %2$s | Sources: %1$s · Window: %2$s |
| `report_advisory` | Beobachtete Defekte/Lücken – nicht vollständig oder bestätigt. | Observed defects/gaps — not exhaustive or confirmed. |
| `report_snapshot_hint` | Momentaufnahme – kann bereits veraltet sein. | Snapshot — may already be outdated. |
| `report_empty` | Noch keine Berichte – oben einen erzeugen. | No reports yet — generate one above. |
| `report_access_denied` | Nur mit Operator-Token einsehbar. | Viewable only with an operator token. |
| `report_error` | Bericht fehlgeschlagen | Report failed |
| `a11y_report_generate` | Bericht erzeugen | Generate report |
| `a11y_report_snapshot` | Bericht-Momentaufnahme, %1$s | Report snapshot, %1$s |

## Reuse (bestehende Keys — NICHT neu anlegen)
| Key | Quelle | Zweck hier |
|---|---|---|
| `event_severity_error` / `_warn` / `_info` / `_debug` | CYP-34 | Severity-Label je Defekt-Zeile (Register) — Reuse, kein neues Severity-Schema |
| `event_detail_source_ts` | CYP-34 | „Beobachtet: %1$s" in der Provenienz (sourceTs ≠ ts) |

> **Hinweis (As-of):** `report_as_of` ist **timestamped** (eigener Key, Snapshot MUSS seine Stand-Zeit tragen) — analog `event_connection_offline` „Stand %1$s" (das no-arg `comm_status_offline` ist hier bewusst NICHT passend, da ein Bericht eine konkrete Stand-Zeit hat).

## Self-Validation
- 17 neue Keys, alle DE+EN; alle im Spec + `product-lead-tags.md` referenziert.
- Argument-Keys: `report_as_of`/`a11y_report_snapshot` (`%1$s`=Stand-ts), `report_provenance` (`%1$s`=Quellen, `%2$s`=Zeitraum). Keine sensiblen Bodies interpoliert (content-frei).
- Reuse-Keys gegen `strings.xml` (develop) verifiziert: `event_severity_*` + `event_detail_source_ts` vorhanden.
