# Connector-Capabilities — i18n-Keys (CYP-119)

> Owner: UIUX-Designer · Epic CYP-118 · Story CYP-119 · Stand 2026-06-29 · Status: Vorlauf — wartet auf Gegenlesen.
> Konvention (verifiziert gg. `app/shared/.../composeResources/values/strings.xml`, develop `ac70819`):
> **Underscore-Realkeys** (keine Punkte), positionsbasierte Argumente `%1$s`. **DE = Default** (`values/`),
> **EN** (`values-en/`). Parität Pflicht.
> Modul `:app:shared` compose.resources → **Shared-Key-Sync mit der Impl timen** (CYP-119): das konsumierende
> Modul muss nach Key-Landung re-syncen, sonst bricht ein Shared-Check.

## Connector-Auswahl & Identität
| Key | DE | EN |
|---|---|---|
| `connector_picker_label` | Connector | Connector |
| `connector_kind_stream_json` | stream-json (API) – Standard | stream-json (API) — default |
| `connector_kind_mcp` | Claude Code via MCP (Abo) – Opt-in | Claude Code via MCP (subscription) — opt-in |
| `connector_default_note` | stream-json ist der erstklassige Standard. Connector B ist eine Opt-in-Alternative. | stream-json is the first-class default. Connector B is an opt-in alternative. |
| `connector_active` | Aktiver Connector: %1$s | Active connector: %1$s |

## Capability-Panel & Tri-State
| Key | DE | EN |
|---|---|---|
| `connector_capabilities_title` | Connector-Fähigkeiten | Connector capabilities |
| `connector_cap_available` | verfügbar | available |
| `connector_cap_limited` | eingeschränkt | limited |
| `connector_cap_unavailable` | nicht verfügbar | unavailable |
| `connector_degraded_note` | Eingeschränkte oder nicht verfügbare Fähigkeiten werden ehrlich markiert – nie vorgetäuscht. | Limited or unavailable capabilities are marked honestly — never faked. |
| `connector_fidelity_badge` | Eingeschränkt | Limited |
| `connector_fidelity_unknown` | Fähigkeiten noch nicht gemeldet | Capabilities not yet reported |
| `a11y_connector_fidelity_badge` | Connector-Fähigkeiten anzeigen | Show connector capabilities |

## Capability-Dimensionen (Doc 10 §3)
| Key | DE | EN |
|---|---|---|
| `connector_dim_structured_usage` | Token-Nutzung pro Turn | Per-turn token usage |
| `connector_dim_tool_granularity` | Tool-Aufruf-Detail | Tool-call detail |
| `connector_dim_reliable_result` | Sauberes Turn-Ende | Clean turn end |
| `connector_dim_rate_limit_signal` | Rate-Limit-Signal | Rate-limit signal |
| `connector_dim_coordination` | Hub-Koordination | Hub coordination |
| `connector_dim_unknown` | Unbekannte Dimension | Unknown dimension |

## Dimension „speist" (welche Funktion degradiert — Doc 10 §3)
| Key | DE | EN |
|---|---|---|
| `connector_feeds_structured_usage` | Speist: Token-Schwellen & Compact | Feeds: token thresholds & compact |
| `connector_feeds_tool_granularity` | Speist: Event-Log-Tiefe | Feeds: event-log depth |
| `connector_feeds_reliable_result` | Speist: „Agent fertig"-Erkennung | Feeds: ‘agent done’ detection |
| `connector_feeds_rate_limit_signal` | Speist: Warden-Stall-Erkennung | Feeds: warden stall detection |
| `connector_feeds_coordination` | Speist: Mediation/Koordination | Feeds: mediation/coordination |

## B-Opt-in-Dialog (Risiko-Aufklärung)
| Key | DE | EN |
|---|---|---|
| `connector_optin_title` | Connector B aktivieren? | Enable connector B? |
| `connector_optin_intro` | Connector B (Claude Code via MCP) ist eine Opt-in-Alternative mit geringerer Fidelity und erklärtem Risiko. | Connector B (Claude Code via MCP) is an opt-in alternative with lower fidelity and a stated risk. |
| `connector_optin_risk_bypass` | Umgeht die Trennung interaktiv/autonom; Anthropic erkennt solche Muster aktiv. | Bypasses the interactive/autonomous separation; Anthropic actively detects such patterns. |
| `connector_optin_risk_account` | Das Risiko (Drosselung/Sperrung) trägt dein eigenes Nutzerkonto – nicht die Plattform. | The risk (throttling/suspension) falls on your own account — not the platform. |
| `connector_optin_risk_fragile` | Technisch brüchig: hängt am interaktiven Claude-Code-Verhalten. | Technically fragile: depends on interactive Claude Code behavior. |
| `connector_optin_preview_title` | Was sich dadurch reduziert: | What this reduces: |
| `connector_optin_ack` | Ich verstehe das Risiko und aktiviere Connector B bewusst. | I understand the risk and deliberately enable connector B. |
| `connector_optin_human_only` | Nur du als Operator aktivierst das – niemals ein Agent oder eine Nachricht. | Only you, the operator, enable this — never an agent or a message. |
| `connector_optin_confirm` | Connector B aktivieren | Enable connector B |
| `connector_optin_cancel` | Abbrechen | Cancel |
| `connector_optin_error` | Aktivierung fehlgeschlagen | Activation failed |

> **`connector_optin_human_only` ist load-bearing (Sicherheit):** macht die Anti-Injection-Invariante sichtbar
> — nur Mensch/Operator aktiviert, nie ein Agent/eine Nachricht (Spec §3.3/§5). Wortlaut nicht abschwächen.
> **`connector_kind_mcp`/`connector_optin_intro` machen kein Kosten-Versprechen** (Doc 10 §6.1 offen) — B
> neutral als „Abo, Opt-in", nicht als „günstiger". Disclosure-Ehrlichkeit (Spec §3.1).
> **Tri-State-Labels (`connector_cap_*`) tragen den Sinn im Text** (Farbe nie allein, WCAG 1.4.1); der Glyph
> kommt aus der `TonedHint`/Chip-Komponente, nicht aus dem Key.

## Reuse (bestehende Keys/Komponenten — NICHT neu anlegen; verifiziert @ `ac70819`)
| Reuse | Quelle | Zweck hier |
|---|---|---|
| `agent_edit_effect_hint` | CYP-88 | „gespeichert ≠ aktiv – Neustart" beim Connector-Wechsel (kein neuer Key) |
| `agent_mgmt_operator_required` | CYP-86 | Config-Operator-Gate (geerbt, kein neuer Key) |
| `ui/TonedHint.kt` + `HintTone` | CYP-99 (Komponente) | alle Hinweis-/Status-Töne (Code-Reuse, kein Key-Reuse) |

## Self-Validation
- **35 neue Keys**, alle DE+EN befüllt, gleiche Argument-Anzahl je Sprache:
  5 Auswahl/Identität + 8 Panel/Tri-State (inkl. `a11y_`) + 6 Dimensionen (inkl. `connector_dim_unknown`
  generischer Fallback, PO §-Ask-4) + 5 „speist" + 11 Opt-in = **35**.
- **Argument-Keys:** nur `connector_active` (%1$s = Connector-Name). Sonst kein interpolierter, content-tragender
  oder sensibler Klartext (nur Connector-Identität).
- **Kollision:** `connector_*` greenfield; `connector*`/`capabilit*`/`fidelity*`/`degrad*` existieren **nicht**
  in `strings.xml` @ `ac70819` → 0 Kollision.
- **DE/EN-Parität:** jede Zeile beidseitig; keine Cross-Surface-Wiederverwendung von `agent_`/`acl_`-Keys
  (Drift-Vermeidung, vgl. CYP-51); `connector_optin_cancel` surface-lokal (wie `crossproject_cancel`).
- Jeder Key ist in `connector-capabilities-spec.md` / `-tags.md` verankert.
