# Connector-Capabilities — testTag-Vertrag (CYP-119)

> Owner: UIUX-Designer · Epic CYP-118 · Story CYP-119 · Stand 2026-06-29 · Status: Vorlauf — wartet auf Gegenlesen.
> Test-Contract v0.5 §2 (`docs/TEST-CONTRACT.md`): **prefixless** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`,
> Segment-Werte `[A-Za-z0-9-]+` (camelCase, **keine Punkte** im Wert). **Geteilte API mit QA (CYP-7) — nicht
> still umbenennen, über den PO koordinieren.**
> **Neue Area `connector`.** Host-verankert in den bestehenden Surfaces (`agent.<id>.header`,
> `agentMgmt.{add,edit}.dialog`) — das sind **eigene** Connector-Tags, kein Reuse fremder Tag-Werte.
> Verifiziert gg. develop `ac70819` (Anchor-Tags real vorhanden, siehe Reuse-Tabelle).

## Scope-Vokabular
- `scope` (Capability-Surface) ∈ { `<agentId>` (Live, am Agentenfenster), `preview` (im B-Opt-in-Dialog) }.
- `dim` (Capability-Dimension) ∈ { `structuredUsage`, `toolGranularity`, `reliableResult`, `rateLimitSignal`, `coordination` } — **exakt die `Capabilities`-Feldnamen** (`core/.../model/ConnectorCapabilities.kt` @ `4fe60d5`), segment-sicher (camelCase). Unbekannte künftige Dimension → `dim` = sanitisierte Wire-Id (Label `connector_dim_unknown`, §-Ask 4). _(Forward-prep: bei der aktuellen festen 5-Feld-`Capabilities`-DTO noch nicht erreichbar — greift erst mit einem erweiterbaren Carrier; kein Defekt.)_
- `kind` (Connector) ∈ { `streamJson`, `mcp` } (Tag-`selectorId`, camelCase) — **mappt auf Wire `@SerialName`** `stream_json`/`mcp` (`ConnectorKind` STREAM_JSON/MCP @ `4fe60d5`). Underscore ist **kein** gültiges Tag-Segment → camelCase im Tag, lowercase-underscore auf dem Draht.

## Capability-Anzeige (pro Agent + Vorschau)
| Tag (Funktion/Konstante) | Wert | Zweck |
|---|---|---|
| `fidelityBadge(agentId)` | `connector.<agentId>.fidelityBadge` | Kompakter Degradations-Marker im Header; **präsent ⇔ Fidelity < voll / nicht gemeldet** (fail-closed durch Absenz). Klick → Panel. |
| `capabilityPanel(agentId)` | `connector.<agentId>.capabilityPanel` | Container des ausführlichen Panels. |
| `activeConnector(agentId)` | `connector.<agentId>.activeConnector` | Aktiver-Connector-Identitätstext (Identität ≠ Fidelity). |
| `capability(scope, dim)` | `connector.<scope>.capability.<dim>` | Eine Dimensions-Zeile (Label + speist-Sekundärzeile). |
| `capabilityStatus(scope, dim)` | `connector.<scope>.capability.<dim>.status` | Status-Chip der Zeile (Glyph+Label+Tönung; Status als Text/a11y assertierbar). |

## Connector-Auswahl (in `agentMgmt.{add,edit}.dialog`)
| Tag | Wert | Zweck |
|---|---|---|
| `PICKER` | `connector.picker` | A/B-RadioGroup (spiegelt `RolePicker`). |
| `pickerOption(kind)` | `connector.picker.<kind>` | Option `streamJson` (Default, vorausgewählt) / `mcp` (nie vorausgewählt). RadioButton getaggt → enabled/selected assertierbar. |
| `PICKER.defaultNote` (inline `TonedHint`-Tag) | `connector.picker.defaultNote` | INFO-Hinweis: A erstklassig, B Opt-in — macht den fail-closed-Default explizit (`connector_default_note`). |
| `PICKER.effectHint` (inline `TonedHint`-Tag) | `connector.picker.effectHint` | „gespeichert ≠ aktiv – Neustart" **im Picker** (EFFECT_DEFERRED), sichtbar bei B-Draft / Edit-Kontext. Reuse des **Keys** `agent_edit_effect_hint`, aber **eigener** Tag auf der Connector-Surface. |

## B-Opt-in-Dialog (Risiko-Aufklärung)
| Tag (Konstante) | Wert | Zweck |
|---|---|---|
| `OPTIN_DIALOG` | `connector.optInDialog` | Dialog-Container (spiegelt `AuthorizeDialog`). |
| `OPTIN_RISK_BYPASS` | `connector.optInDialog.riskBypass` | Risiko: umgeht interaktiv/autonom-Trennung. |
| `OPTIN_RISK_ACCOUNT` | `connector.optInDialog.riskAccount` | Risiko: Drosselung/Sperrung trägt das Nutzerkonto. |
| `OPTIN_RISK_FRAGILE` | `connector.optInDialog.riskFragile` | Risiko: technisch brüchig. |
| `OPTIN_PREVIEW` | `connector.optInDialog.capabilityPreview` | Degradierte-Caps-Vorschau (hostet `capability(preview, *)`). |
| `OPTIN_ACK` | `connector.optInDialog.ack` | Bewusste Bestätigungs-Checkbox (spiegelt `ownerConsent`) → gated `canConfirm`. |
| `OPTIN_HUMAN_ONLY` | `connector.optInDialog.humanOnly` | Anti-Injection-Note (load-bearing, §2.6/§5). |
| `OPTIN_CONFIRM` | `connector.optInDialog.confirm` | Benannter Confirm-Button. |
| `OPTIN_CANCEL` | `connector.optInDialog.cancel` | Abbrechen. |
| `OPTIN_ERROR` | `connector.optInDialog.error` | Fehlerzeile (Aktivierung/Gate). |

## Reuse (bestehende Tags — NICHT neu anlegen; verifiziert @ `ac70819`)
| Reuse-Tag | Quelle | Rolle hier |
|---|---|---|
| `agent.<id>.header` (`AgentViewTags.header`) | CYP-73 | Host-Anchor des Fidelity-Badges |
| `agentMgmt.add.dialog` / `agentMgmt.edit.dialog` (`AgentMgmtTags.ADD_DIALOG`/`EDIT_DIALOG`) | CYP-86/88 | Host-Anchor des Connector-Pickers |
| `agent_edit_effect_hint` (**Key**, CYP-88) | CYP-88 | „gespeichert ≠ aktiv – Neustart" — Key wiederverwendet, im Picker unter eigenem Tag `connector.picker.effectHint` gerendert (s. o.) |
| `agentMgmt.gateHint` (`AgentMgmtTags.GATE_HINT`) | CYP-86 | Config-Operator-Gate (geerbt, kein neuer Tag) |

## Self-Validation
- **19 neue Tag-Identifier** in Area `connector`: 5 Capability-Anzeige (`fidelityBadge`, `capabilityPanel`,
  `activeConnector`, `capability`, `capabilityStatus`) + 4 Picker (`PICKER`, `pickerOption`,
  `picker.defaultNote`, `picker.effectHint`) + 10 Opt-in
  (`OPTIN_DIALOG`, `…RISK_BYPASS`, `…RISK_ACCOUNT`, `…RISK_FRAGILE`, `…PREVIEW`, `…ACK`, `…HUMAN_ONLY`,
  `…CONFIRM`, `…CANCEL`, `…ERROR`). _(CYP-123-Doc-Sync 2026-06-29: `picker.defaultNote`/`picker.effectHint`
  ergänzt — sie werden in der Impl als `TonedHint`-Tags gerendert; 17→19.)_
- Alle Werte erfüllen `[A-Za-z0-9-]+` je Segment (camelCase, keine Punkte im Wert); `dim`/`kind`/`scope`
  segment-sicher.
- Jeder Tag ist in `connector-capabilities-spec.md` verankert; 4 Reuse-Tags real auf develop `ac70819`
  verifiziert (kein Neuanlegen).
- Kollision: Area `connector` ist greenfield (kein bestehender `connector.*`-Tag im Code).
