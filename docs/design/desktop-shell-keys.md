# Phase-2 Desktop-App-Shell — i18n-Keys (CYP-449, Epic CYP-427)

> Owner: UIUX-Designer · Story CYP-449 (Epic CYP-427) · Stand 2026-07-11 · Status: **Spec-Closure (Q1–Q4 geruled)**;
> eingefroren als UI-Vorlage. Begleit-Spec: `desktop-shell-ux-spec.md`. Volle §14-Durchsicht macht der PO vor Build-GO.
> Konvention (verifiziert @ develop `8816d406`): Underscore-Realkeys, `%1$s`-Args, DE-Default + EN, Parität Pflicht.
> **Fast reine Reuse** — die Shell hostet CYP-429 (`remote_*`) + hubConnect (`hubconnect_*`) + bestehende Surfaces
> (`project_*`/`window_*`); net-new nur die Shell-Regionen unten. Neue Familie `shell_*` (greenfield, 0 Kollision verifiziert).

## Shell-Regionen (net-new)
| Key | DE | EN |
|---|---|---|
| `shell_hub_switcher` | Hubs | Hubs |
| `shell_hub_switch_confirm_title` | Von Hub %1$s trennen? | Disconnect from hub %1$s? |
| `shell_hub_switch_confirm_body` | Laufende Aktionen werden ungewiss. Der aktuelle Hub-Workspace wird geschlossen. | In-flight actions become uncertain. The current hub workspace will close. |
| `shell_hub_switch_confirm_action` | Trennen & wechseln | Disconnect & switch |
| `a11y_shell_hub_context` | Hub-Kontext: Fern-Betrieb auf %1$s | Hub context: remote session on %1$s |

> **`shell_hub_switch_confirm_*` (Q3):** ehrliche Rahmung — nennt die **Konsequenz** (laufende Aktionen werden ungewiss +
> Workspace schließt), weil der Switch = voller Teardown (H2/§6) ist; verhindert versehentliches Aussteigen. **`%1$s` =
> aktueller Hub-Name.** Confirm-**Abbrechen** reused den bestehenden Dialog-Abbrechen (`connector_optin_cancel`-Muster) —
> **kein** neuer Cancel-Key.

## Reuse (bestehende Keys — NICHT neu anlegen; verifiziert @ `8816d406`)
| Reuse | Quelle | Rolle hier |
|---|---|---|
| `remote_context_operating` / `remote_e2e_indicator` / `remote_trust_pinned` / `remote_relay_dropped` / `remote_switch_transition` / `remote_conn_degraded` | CYP-429 | Hub-Kontext-Zeile ① + Relay-Fläche ③ + Transition (§5/§6/§7) — **identisch**, keine Varianten |
| `hubconnect_*` (Liste/Presence/Connect/Modus) | CYP-395/419 | gemounteter Hub-Flow (§4) |
| `project_switcher_active` / `project_switch_hint` | Project | ProjectSwitcherBar ② (bestehend) |
| `window_fit_action` / `window.*`-Copy | CYP-26 | WindowHost ④ (bestehend) |
| `connector_optin_cancel` | Connector | Confirm-Dialog-Abbrechen (Muster) |

## Self-Validation
- **5 neue Keys**, alle DE+EN befüllt, gleiche Argument-Anzahl je Sprache: 4 `shell_*` + 1 `a11y_shell_*` = **5**.
- **Argument-Keys (genau ein `%1$s`, DE=EN):** `shell_hub_switch_confirm_title`, `a11y_shell_hub_context` = **2**; alle
  übrigen **0 Arg**. DE/EN-Argument-Anzahl identisch.
- **Kein content-tragender/sensibler Klartext** — `%1$s` = nur Hub-Name (Anzeige).
- **Kollision:** **0** — `shell_*` greenfield (`grep name="shell_"` @ `8816d406` liefert nichts).
- **DE/EN-Parität:** jede Zeile beidseitig.
- Jeder Key ist in `desktop-shell-ux-spec.md` (§13) und `-tags.md` verankert.
