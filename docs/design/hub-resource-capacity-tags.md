# Hub-Ressourcen-/Überlast-UX — testTag-Vertrag (CYP-417 / Slice S-G ResourceGovernor)

> Owner: UIUX-Designer · Story CYP-417 (Epic CYP-395) · Stand 2026-07-11 · Status: **Spec-Closure (Q1–Q5 geruled)**;
> eingefroren als UI-Vorlage für den S-G-Bau. Begleit-Spec: `hub-resource-capacity-ux-spec.md`.
> Test-Contract v0.5 §2 (`docs/TEST-CONTRACT.md`): **prefixless** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`,
> Segment-Werte `[A-Za-z0-9-]+` (camelCase, **keine Punkte** im Wert). **Geteilte API mit QA (CYP-7) — nicht still
> umbenennen, über den PO koordinieren.**
> **Keine neue Area.** Die Kapazitäts-/Überlast-UI lebt in der **bestehenden** Area `workspace` (`WorkspaceTags`,
> Area `workspace`, hat bereits `workspace.roleIndicator`, verifiziert @ develop `2877fc8c`) — sie ist workspace-scoped
> (Top-Bar `ProjectSwitcherBar` über dem `WindowHost`), **nicht** per-Agent.

## Kapazität + Überlast (Area `workspace`, erweitert `WorkspaceTags`)
| Tag (Funktion/Konstante) | Wert | Zweck |
|---|---|---|
| `capacity` | `workspace.capacity` | Hub-Kapazitäts-Readout „N/M Agenten" in/neben `ProjectSwitcherBar`. **Präsent ⇔ Kapazität geschätzt**; unbekannt ⇒ **absent** (fail-closed, `null≠0`, Q3). |
| `capacityFull` | `workspace.capacity.full` | Qualifier/Variante im **Voll**-Zustand (`current == estimatedMax`, Q2) — Readout tönt WARN-Amber. |
| `overloadBanner` | `workspace.overloadBanner` | Hub-scoped WARN-Amber-Banner bei echtem Server-Abweis (§5). Erscheint **nur** bei tatsächlichem fail-closed Abweis. |
| `overloadBannerDismiss` | `workspace.overloadBanner.dismiss` | Quittier-Aktion („Verstanden"); Banner ist persistent-bis-klärt **und** dismissbar (Q5). |

> **Event-Zeile:** das content-freie WARN-Event (§6) rendert über die **bestehenden** Event-Log-Zeilen-Tags mit
> Severity-Qualifier `warn` (`eventTail.row.<i>.warn` / `eventBrowse.row.<i>.warn`) — **kein** neuer Event-Tag nötig.

## Fail-closed-Anker (für §-QA)
- `workspace.capacity` ist **absent**, wenn Kapazität unbekannt (nie „0/0"); `workspace.capacity.full` nur bei `current ==
  estimatedMax`.
- `workspace.overloadBanner` erscheint **nur** bei echtem Server-Abweis (kein Client-seitiges Erfinden aus der Schätzung, H5).
- Kein Tag trägt Erfolgs-Grün; Readout-Headroom neutral, Voll + Banner WARN-Amber (Form+Label, WCAG 1.4.1).

## Reuse (bestehende Tags/Anchor — NICHT neu anlegen; verifiziert @ `2877fc8c`)
| Reuse-Tag/Anchor | Quelle | Rolle hier |
|---|---|---|
| Area `workspace` / `workspace.roleIndicator` (`WorkspaceTags`) | CYP (Workspace) | bestehende workspace-scoped Area + Top-Bar-Nachbar des Kapazitäts-Readouts |
| `ProjectSwitcherBar` (Top-Bar über `WindowHost`) | CYP (Project) | Host der Kapazitäts-/Überlast-Chrome (workspace-scoped, `trailing`-Slot) |
| `eventTail.row.<i>.warn` / `eventBrowse.row.<i>.warn` (`EventLogTags`) | CYP-41/42 | Event-Log-Zeile des content-freien WARN-Events (kein neuer Tag) |
| `FrameBanner`-Muster (`AgentWindow.kt` `HandoffBanners`, WARN-Amber) | CYP-381 | **Muster** für den hub-scoped Banner (Dev hebt die heute-private/per-Agent-Composable auf workspace-scoped, Nahtstelle S-3) |

## Self-Validation
- **4 neue Tags** in der bestehenden Area `workspace` (`capacity`, `capacity.full`, `overloadBanner`,
  `overloadBanner.dismiss`). Kein Instanz-Scope (workspace-singulär, ein Hub pro verbundener Sitzung).
- **0 Kollision:** die vier Werte existieren nicht in `WorkspaceTags` (@ `2877fc8c`); Event nutzt bestehende Row-Tags.
- **Geteilte API mit QA (CYP-7):** neue `workspace.*`-Member über den PO mit dem Tester abstimmen (Frozen-Contract).
- Jeder Tag ist in `hub-resource-capacity-ux-spec.md` (§8) verankert und trägt einen Copy-/a11y-Key aus
  `hub-resource-capacity-keys.md`.
