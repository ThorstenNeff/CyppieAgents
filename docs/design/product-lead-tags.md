# Product-Lead Report — testTags (CYP-89 / CYP-90)

> Owner: UIUX-Designer · Epic CYP-77 · Stand 2026-06-28 · Status: Vorschlag — wartet auf Dev-Gegenlesen
> Schema (Test-Contract v0.5 §2, Dev/QA-Vertrag CYP-7): **prefixlos** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`, Segmentwerte `[A-Za-z0-9-]+` (camelCase ok, **keine Punkte** im Wert). Area hier = `productLead`.
> **Code = Source of Truth:** Reuse-Tags unten gegen die echten `*Tags.kt` (develop) verifiziert (Lehre aus CYP-26-Tag-Drift).

## Neue Tags (Area `productLead`)
| Tag | Element |
|---|---|
| `productLead.panel` | Wurzel-Container im Fensterinhalt |
| `productLead.trigger` | „Bericht erzeugen"-Leiste |
| `productLead.trigger.usage` | Typ: Nutzungs-Leitfaden |
| `productLead.trigger.status` | Typ: Status-Bericht |
| `productLead.trigger.defects` | Typ: Defekt-/Lücken-Register |
| `productLead.generating` | Fortschritt „wird erzeugt" |
| `productLead.list` | Snapshot-Liste (links) |
| `productLead.snapshot.<id>` | Listenzeile je Snapshot (scope = Report-id) |
| `productLead.snapshot.<id>.ts` | „Stand: <ts>" der Zeile |
| `productLead.detail` | Detail-Pane (rechts) |
| `productLead.detail.asOf` | prominente „Stand: <ts>" |
| `productLead.detail.provenance` | Quellen + Fenster + „beobachtet" |
| `productLead.detail.advisory` | Advisory-Hinweis (Defekt-Register) |
| `productLead.detail.section.<key>` | Report-Sektion |
| `productLead.detail.defect.<index>` | Defekt-/Lücken-Zeile im Register |
| `productLead.detail.defect.<index>.<severity>` | Severity-Qualifier (error/warn/info/debug) |
| `productLead.empty` | Empty-State (keine Berichte) |
| `productLead.gateHint` | Operator-Gate-Hinweis (fail-closed) |
| `productLead.error` | Fehlerzeile |

## Reuse (bestehende Tags/Token — gegen Code verifiziert, NICHT neu definieren)
| Tag/Token | Quelle | Zweck hier |
|---|---|---|
| `window.<id>` (= `window.productLead`) | `WindowTestTags.window(id)` | Product-Lead-Fenster im Manager |
| `window.<id>.content` | `WindowTestTags.content(id)` | Fensterinhalt (productLead.panel darunter) |
| `window.<id>.titlebar` | `WindowTestTags.titleBar(id)` | Fenster-Titelleiste |
| `severity_rail` (Token) | `event-log-tokens.json` (CYP-34) | Severity-Rail je Defekt-Zeile (Hue+Icon+Text), kein neues Schema |

## Self-Validation
- 19 neue Tags; Schema-konform (camelCase-Werte wie `gateHint`/`asOf`/`detail`; keine Punkte in Segmentwerten).
- Per-Snapshot-Scope `productLead.snapshot.<id>[.ts]` + Defekt-Zeile `…detail.defect.<index>[.<severity>]` folgen dem Präzedenzfall tieferer Verschachtelung (`comm.channel.<id>`, `agent.<id>.event.<index>.<kind>`, `eventBrowse.row.<index>.<severity>`).
- Reuse gegen Code verifiziert (develop): `WindowTestTags.window/content/titleBar` exakt; `severity_rail` in `event-log-tokens.json`. Kein Drift.
- Fenster-id `productLead` ist Vorschlag; finaler Konstantenname (`PRODUCT_LEAD_WINDOW_ID`) = Dev, Tag folgt der id.
