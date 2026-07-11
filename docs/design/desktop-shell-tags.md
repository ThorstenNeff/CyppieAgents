# Phase-2 Desktop-App-Shell — testTag-Vertrag (CYP-449, Epic CYP-427)

> Owner: UIUX-Designer · Story CYP-449 (Epic CYP-427) · Stand 2026-07-11 · Status: **Spec-Closure (Q1–Q4 geruled)**;
> eingefroren als UI-Vorlage. Begleit-Spec: `desktop-shell-ux-spec.md`. Volle §14-Durchsicht macht der PO vor Build-GO.
> Test-Contract v0.5 §2 (`docs/TEST-CONTRACT.md`): **prefixless** `<area>[.<scopeId>].<element>[.<qualifier>]`, camelCase,
> keine Punkte im Wert. **Geteilte API mit QA (CYP-7) — über den PO koordinieren.**
> **Area `shell` existiert bereits** (inline-Const `SHELL_LOADING_TAG = "shell.loading"`, `AgentShell.kt:992`) — die
> Shell-Regionen-Tags **erweitern** sie additiv (0 Kollision mit `shell.loading`). Vorschlag: die Tags in einem
> `ShellTags`-Objekt bündeln (inkl. des bestehenden `shell.loading`).

## Shell-Regionen (net-new)
| Tag | Wert | Zweck |
|---|---|---|
| `hubContext` | `shell.hubContext` | Hub-Kontext-Zeile ① (§5, oberste Shell-Zeile). **Absent im Lokal-Modus** (fail-closed, H4). |
| `hubSwitch` | `shell.hubContext.switch` | `Hubs ▾`-Switcher (§5, Vorbild `Projekte ▾`). |
| `hubSwitchConfirm` | `shell.hubContext.switchConfirm` | Confirm-Dialog vor dem Wechsel (§6.0, Q3). |
| `relayDrop` | `shell.relayDrop` | globale Relay-Drop-Fläche ③ (§7, H3). Erscheint **nur** bei Drop; **absent im Lokal-Modus**. |
| `switchTransition` | `shell.switchTransition` | „Trenne von X … verbinde mit Y" (§6). |

## Fail-closed-Anker (für §-QA)
- `shell.hubContext` + `shell.relayDrop` **absent im Lokal-Modus** (H4, kein Phantom).
- Nach Hub-Wechsel **kein** `window.<altId>` des alten Hubs (voller Teardown, H2/§6).
- `shell.relayDrop` = **eine** globale Fläche (nicht N per-Fenster-Chips, H3); `remote.connect.connected` nie vor echtem LIVE.

## Reuse (bestehende Tags/Areas — NICHT neu anlegen; verifiziert @ `8816d406`)
| Reuse | Quelle | Rolle hier |
|---|---|---|
| `shell.loading` (inline `SHELL_LOADING_TAG`) | bestehend (`AgentShell.kt:992`) | Loading-Gate; die neuen `shell.*`-Tags koexistieren |
| Area `remote` (`remote.context.*`/`remote.trust.*`/`remote.relayDrop`/`remote.connect.*`) | CYP-429 | Inhalt der Hub-Kontext-Zeile ① + Relay-Fläche ③ (Shell hostet, keine Varianten) |
| Area `hubConnect` (`mode.remote`, HubList, Connect) | CYP-395/419 | gemounteter Hub-Flow (§4) |
| `projectSwitcher.*` (`ProjectTags`) | Project | ProjectSwitcherBar ② |
| `window.host` / `window.<id>.*` (`WindowTestTags`) | CYP-26 | WindowHost ④ |
| `workspace.roleIndicator` (`WorkspaceTags`) | Workspace | Nachbar der Hub-Kontext-Zeile |

## Self-Validation
- **5 neue Tags** in der bestehenden Area `shell` (`hubContext`, `hubContext.switch`, `hubContext.switchConfirm`,
  `relayDrop`, `switchTransition`). Kein Instanz-Scope (Shell-singulär).
- **0 Kollision:** die fünf Werte existieren nicht (nur `shell.loading` bestehend, distinkt).
- **Geteilte API mit QA (CYP-7):** über den PO mit dem Tester abstimmen (Frozen-Contract).
- Jeder Tag ist in `desktop-shell-ux-spec.md` (§12) verankert und trägt (wo Text) einen Copy-/a11y-Key aus `desktop-shell-keys.md`.
