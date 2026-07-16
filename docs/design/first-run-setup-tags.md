# First-Run Operator-Setup — testTags

> Owner: UIUX-Designer · Epic CYP-623 · Story CYP-629 · Stand 2026-07-16 ·
> Status: **Vorschlag — wartet auf Dev-Gegenlesen.** Companion zu `first-run-setup-ux-spec.md` / `-keys.md` / `-tokens.json`.
> Test-Contract v0.5 §2: prefixless `<area>[.<scopeId>].<element>[.<qualifier>]`, Segment-Werte `[A-Za-z0-9-]+`
> (camelCase, **keine Punkte im Wert**). Area `firstRun`, single-instance. Reuse-Tags gg. Code @ develop `2f664e33` verifiziert.
> **Shared API mit QA (CYP-7) — nicht still umbenennen; über den PO koordinieren.**

## Neue Tags (Area `firstRun`)

| Tag | Element | Ton (`HintTone`) | a11y-Politeness |
|---|---|---|---|
| `firstRun.gate` | Gate-Wurzel (Vollflächen-Fläche) | — | — |
| `firstRun.intro` | Intro/Orientierungs-Text | `INFO` | — |
| `firstRun.degradedNote` | „läuft, aber unkonfiguriert = normal" | `INFO` | Polite |
| `firstRun.stepper` | Schritt-Indikator-Container | — | — |
| `firstRun.step.apiKey` | Schritt-Chip API-Key | — | — |
| `firstRun.step.repo` | Schritt-Chip Repository | — | — |
| `firstRun.step.team` | Schritt-Chip Team | — | — |
| `firstRun.apiKey.posture` | ehrliche at-rest-Posture-Zeile | `INFO` | — |
| `firstRun.apiKey.saved` | „gespeichert, greift beim ersten Start" | `INFO` | Polite |
| `firstRun.repo.saved` | „Repository gesetzt, klont jetzt" | `INFO` | Polite |
| `firstRun.repo.cloning` | „wird geklont …" (animiert/indeterminate, §4.4) | `INFO` | Polite |
| `firstRun.repo.cloningSlow` | Lebenszeichen-Zeile nach ~15s „kann Minuten dauern" | `INFO` | Polite (einmal) |
| `firstRun.repo.cloneOk` | „geklont, Team kann arbeiten" | `INFO` | Polite |
| `firstRun.repo.cloneFailed` | Clone-Fehlschlag (url/auth/generisch) | **`ERROR`** | **Assertive** |
| `firstRun.team.intro` | Team-Rahmung „startet mit 1 PO" | `INFO` | — |
| `firstRun.complete` | Abschluss-Fläche (Titel+Body) | `INFO` | Polite |
| `firstRun.openWorkspace` | CTA „Workspace öffnen" | — | — |
| `firstRun.skip` | CTA „Später einrichten" | — | — |

**Cross-Area (Konsument des Skip, §6.3):**
| Tag | Area | Element | Ton | a11y |
|---|---|---|---|---|
| `workspace.unconfiguredBanner` | `workspace` | voller Unkonfiguriert-Banner im degradierten Workspace | `INFO` | Polite |
| `workspace.unconfiguredCollapse` | `workspace` | Einklapp-Control des Banners (§6.3a Nag-Fix) | — | — |
| `workspace.unconfiguredChip` | `workspace` | leiser passiver Indikator-Chip (eingeklappt) | `INFO` | — |
| `workspace.setupResume` | `workspace` | Banner-CTA „Einrichtung fortsetzen" → öffnet Gate | — | — |
| `agent.<id>.ctlUnconfigured` | `agent` (scoped) | Start-blockiert-Grund am per-Agent-Control | **`GATED`** | Polite |

> **`agent.<id>.ctlUnconfigured`** ist **agent-scoped** (mirrort `agent.<id>.*`, z. B. `agent.<id>.restartBtn`).
> ⟂ AgentView/Lifecycle — **Start-Control-Naming in `AgentViewTags` vor Bau gegenlesen** und exakte Verankerung
> mit Dev koordinieren (ux-spec §6.3c); der Ton (`GATED`) + Ehrlichkeit (Grund vor Klick) sind der Kern.

> **Ein Clone-Status-Slot, nicht vier Tags:** `firstRun.repo.cloneFailed` ist der Fehler-Slot; die drei
> Reason-Copys (`_url`/`_auth`/generisch, siehe -keys.md) rendern in **denselben** Tag (der Zustand ist
> „Clone fehlgeschlagen", die Reason variiert nur den Text). `cloning`/`cloneOk` sind eigene Slots, weil sie
> distinkte Zustände (nicht nur Text-Varianten) sind. So bleibt der QA-Kontrakt schmal.

## Reuse (bestehende Tags — gegen Code verifiziert @ `2f664e33`)
- **API-Key-Sektion (eingebettet, `SettingsTags`):** `settings.section.apiKey`, `settings.apiKey.masked`,
  `settings.apiKey.input`, `settings.apiKey.reveal`, `settings.apiKey.save`, `settings.apiKey.error`.
  **NICHT reused im First-Run:** `settings.apiKey.effectHint` (Restart-Hint) — ersetzt durch `firstRun.apiKey.saved`.
  Kein `settings.apiKey.gateHint` (kein Operator-Gate im First-Run, §2).
- **Repo-Sektion (eingebettet, `SettingsTags`):** `settings.section.repo`, `settings.repo.url.input`,
  `settings.repo.branch.input`, `settings.repo.save`, `settings.repo.status`, `settings.repo.error`.
  **NICHT reused:** `settings.repo.effectHint` — ersetzt durch `firstRun.repo.saved` + `firstRun.repo.clone*`.
- **Roster (eingebettet, `AgentMgmtTags`):** `agentMgmt.panel`, `agentMgmt.list`, `agentMgmt.empty`,
  `agentMgmt.addButton`, `agentMgmt.add.dialog`, `agentMgmt.add.id.input`, `agentMgmt.add.name.input`,
  `agentMgmt.add.role.picker`, `agentMgmt.add.confirm`, `agentMgmt.add.cancel`, `agentMgmt.add.spawnHint`,
  `agentMgmt.add.error` (kompletter Add-Flow, unverändert).
- **Settings-Weg aus dem Banner:** `window.settings` (`WindowTestTags`) — der Banner-Link führt in die
  bestehende Projekt-Settings.

## Self-Validation
- **18 `firstRun.*` (inkl. `firstRun.repo.cloningSlow`, §4.4) + 4 `workspace.*` (`unconfiguredBanner`,
  `unconfiguredCollapse`, `unconfiguredChip`, `setupResume`) + 1 `agent.<id>.ctlUnconfigured`** = **23 neue
  Tags**; Schema-konform
  (`<area>[.<scopeId>].<element>`, camelCase-Werte, keine Punkte im Segmentwert, charset `[A-Za-z0-9-]+`;
  `<id>` ist der Scope-Platzhalter wie bei `agent.<id>.restartBtn`).
- **Kollision:** 0 — `firstRun`-Area greenfield; die vier `workspace.*` gg. bestehende `workspace.*`
  verifizieren (§6.2); `agent.<id>.ctlUnconfigured` mirrort das bestehende `agent.<id>.*`-Schema
  (Start-Control-Naming gg. `AgentViewTags` gegenlesen, §6.3c).
- **Reuse gg. Code verifiziert:** alle zitierten `settings.*`/`agentMgmt.*`/`window.settings`-Tags existieren
  in `SettingsTags.kt` / `AgentMgmtTags.kt` / `WindowTestTags` @ `2f664e33` (nicht aus dem Spec abgeleitet).
- **2 bestehende Effekt-Hint-Tags bewusst NICHT reused** (`settings.*.effectHint`) — Honesty-Begründung in
  ux-spec §3.2/§4.1; im First-Run kein Deferral.
- **Ton + a11y je Tag** zugeordnet (Modell: `auth-tags.md`): Clone-Fehler `ERROR`/Assertive, alle
  Status-/Progress-Zeilen `INFO`/Polite; jeder textuelle Tag trägt einen -keys.md-Key.
- **QA-Frozen-Kontrakt (CYP-7):** neue Tags additiv; keine bestehenden umbenannt/entfernt.
- Jeder Tag ist in `first-run-setup-ux-spec.md` verankert.
