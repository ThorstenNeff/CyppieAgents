# Phase-2 Desktop-App-Shell / Operator-Surface UX — Design-Spec (CYP-449, Epic CYP-427)

> Status: **Design-Aufschlag** · docs-only, **kein Bau vor Build-GO** · Owner: UX/UI
> Baut auf **CYP-429 (Remote-Operator-Flow)** auf — jetzt die **Desktop-Fenster-Shell** für den Remote-Operator
> (RR6-ii, **Team-1-Desktop zuerst**). Naht-Konsistenz zu CYP-429 + **CYP-443-Client-Zuständen** über den PO.
> Begleit-Specs: `remote-operator-ux-spec.md` (CYP-429), `hub-connection-ux-spec.md` (CYP-395/419), `WINDOW-RESPONSIVE.md` (CYP-26).
> Companion-Files werden nach Ratifikation eingefroren (jetzt würden Keys/Tags noch driften).

⭐**Struktureller Kern-Befund (Grounding):** die gebaute `connect/`-Hub-Flow-UX (CYP-419, meine CYP-395-Spec) ist
**gebaut, aber NICHT gemountet** — `App` geht heute `AuthGate → AgentShell` direkt. **CYP-449 ist der Slice, der die
Hub-Schicht zwischen Auth und Shell verdrahtet**, die Hub-Kontext-/Switch-Chrome ergänzt, die **eine globale
Relay-Verbindungs-Fläche** hostet und die bestehenden Floating-Window-Operator-Surfaces als **remote-adaptierten
aktiven-Hub-Workspace** rahmt. Kein Neubau der Surfaces — **Verdrahtung + Rahmen**.

---

## 1. Kern-Ehrlichkeit (die tragenden Entscheidungen)

- **H1 — Scope-Hierarchie legibel: EIN Hub → EIN Projekt → N Fenster.** Die Shell macht sichtbar, *auf welchem Hub* du
  bist (Fern-Betrieb-Banner CYP-429 §9), *in welchem Projekt* (`ProjectSwitcherBar`), *mit welchen Fenstern*. Nie
  verwischen, welcher Hub aktiv ist.
- **H2 — Hub-Wechsel = voller Shell-Teardown (CYP-429 Q5).** Ein Hub-Wechsel reißt **alle** Fenster/Surfaces des alten
  Hubs ab (nichts wird über Hubs getragen), zeigt die Transition ehrlich (Fenster leeren, „verbinde mit Hub Y",
  neu-ableiten). **Keine** stehenden Fenster des alten Hubs, die in den neuen bluten.
- **H3 — EINE globale Relay-Verbindungs-Fläche auf Shell-Ebene (CYP-429 H4).** Ein Relay-Drop ist **ein** ehrlicher
  Shell-Zustand (über dem Window-Host), **nicht** N per-Fenster-„reconnecting"-Chips. Der ganze Workspace liest ehrlich
  als „getrennt — verbinde neu".
- **H4 — Lokal/Remote-Shell-Parität, ehrlich unterschieden.** Dieselbe Desktop-Shell trägt Lokal **und** Remote
  (Team-1). Im **Lokal-Modus** ist die Fern-Betrieb-/Relay-Chrome **absent** (fail-closed, kein Phantom); im
  **Remote-Modus** präsent. Die Shell täuscht nie Remote vor, wenn lokal (und umgekehrt).
- **H5 — Operator-Surfaces über das Relay erben CYP-429-Ehrlichkeit.** Die eingebetteten Fenster laufen über den
  Remote-Transport; ihre Optimistic-vs-confirmed-Ehrlichkeit (CYP-429 H5) gilt **unverändert** — die Shell fügt **keine**
  neue Optimistik hinzu.
- **H6 — App-Chrome vs. OS-Chrome ehrlich.** Das OS-Fenster ist minimal (`Window("KMPCyppieAgents")`, kein Menü); die
  Scope-Chrome (Hub-Kontext → Projekt → Fenster) ist **App-eigen**. Kein vorgetäuschtes natives Menü.

---

## 2. Scope

- **Desktop-Native (Team-1 zuerst; Web-UI/Team-2 später, teilt die `commonMain`-Shell).**
- **Enthalten:** (1) Verdrahtung der Hub-Schicht (`AuthGate → PoP → HubConnect → hub-scoped AgentShell`); (2)
  Hub-Kontext-/Switch-Chrome; (3) globale Relay-Verbindungs-Fläche; (4) Rahmen der bestehenden Operator-Surfaces als
  aktiver-Hub-Workspace, remote-adaptiert (Transport-Re-Point, Q5-Teardown); (5) responsives Verhalten.
- **Nicht enthalten (andere Slices):** die einzelnen Surfaces selbst (bestehend), der Remote-Flow-Inhalt (CYP-429),
  die Krypto/Transport (`RemoteHubTransport`/CYP-443), Multi-Hub-Kollaboration.

---

## 3. Reuse-Karte (gegen echten Code gegroundet @ develop `8816d406`)

- **Shell-Root:** `App.kt` (`MaterialTheme → Surface → AuthGate → AgentShell`, `App.kt:61-94`). `AgentShell.kt` root =
  `Column(fillMaxSize)` (`:346`): **Row 1 `ProjectSwitcherBar`** (`:360-376`, Slots `capacityReadout`/`overloadBanner`/
  `trailing`), Loading-Gate (`:377`), **Row 2 `BoxWithConstraints → WindowHost`** (`:732-819`).
- **Transport-Naht (der Hub-Switch-Hebel):** `resolvedTransport = TransportModeResolver.create(...)` (`AgentShell.kt:301-304`);
  jede REST/WS-Quelle liest `resolvedTransport.httpBaseUrl/.wsBaseUrl/.httpClient`. **Ein Hub-Wechsel re-pointet genau
  hier** (`HubTransport` `net/hub/HubTransport.kt`; `LocalHubTransport` / `RemoteHubTransport`-Stub).
- **Window-Host:** `WindowHost` (`window/WindowManager.kt:121`) → `WindowSizeClass`-Split **`WindowCanvas`** (frei-schwebende
  `FloatingWindow`s, Z=Listenindex) **XOR `PhonePager`** (`:177-191`). `WindowState(id,title,x,y,w,h)` (`WindowManagerState.kt:17`),
  `syncWindows` positions-erhaltend (`:587`). Tags `window.host`/`window.<id>.*` (`WindowTestTags`).
- **Top-Bar-Muster:** `ProjectSwitcherBar` (`project/ProjectSwitcherBar.kt`) = `Column` mit Rollen-Indikator-Zeile
  (`:104`), `Projekte ▾`-DropdownMenu-Switcher (`:157-240`, **Vorbild für `Hubs ▾`**), slot-erweiterbar.
- **Gebaute, NICHT gemountete Hub-Flow-UX:** `connect/HubConnectFlow.kt` + `HubConnectSelection.kt` (`HubListView`,
  `PresenceRow`, `ConnectingView`), `HubConnectViewModel`, `HubConnectUiState` (`Preparing…HubList/ChoosingMode/Connecting`),
  `LocalConnectFeed` (`ConnectProgress`/`ConnectCause`), `ControlPlaneClient.hubs()` (`HubDescriptor`) — **CYP-449 mountet
  sie**.
- **Remote-Chrome (CYP-429):** Fern-Betrieb-Banner (§9), Relay-Verbindungszustand + globaler Drop (§7), TOFU-Indikator/
  E2E (§8), Passkey-PoP (§5) — **auf Shell-Ebene integriert**.
- **Responsiv:** `WINDOW-RESPONSIVE.md` (CYP-26), `LayoutBreakpoints.PANE_COLLAPSE_WIDTH=600`, `ProjectSwitcherBar`
  compact `<400`, Column-Cap by Size-Class.

---

## 4. Shell-Komposition & Scope-Hierarchie

**Verdrahtung (neu):** die Hub-Schicht sitzt **zwischen** `AuthGate(Verified)` und `AgentShell` — heute fehlt sie.

```
App
 └ MaterialTheme → Surface
     └ AuthGate  ──(Verified OPERATOR)──►
         └ [Passkey/WebAuthn-PoP]              ← CYP-429 §5 (nur Remote-Zielbuilds)
             └ HubConnect (Liste/Switch/Connect + TOFU)   ← CYP-419, jetzt gemountet
                 └ AgentShell  =  hub-scoped Workspace
                     ├ ① Hub-Kontext-Zeile   ← Fern-Betrieb: Hub X · E2E · gepinnt · Latenz · [Hubs ▾]   (§5/§7)
                     ├ ② ProjectSwitcherBar  ← ● Aktives Projekt · [Projekte ▾] · Kapazität · overloadBanner
                     ├ ③ Relay-Drop-Fläche   ← global, nur bei Drop (§7/H3)
                     └ ④ WindowHost          ← Agenten/Comm/ACL/… Floating-Windows (WindowCanvas / PhonePager)
```

**Scope-Chrome-Stapel (H1):** Hub-Kontext (①) **über** Projekt (②) **über** Fenstern (④) — die Chrome liest die Achsen
von außen nach innen (welcher **Hub** · welches **Projekt** · welche **Fenster**). ① ist eine **neue erste Zeile** im
`AgentShell`-Root-`Column` (vor der `ProjectSwitcherBar`), außerhalb des Loading-Gates — denn ein Hub scoped **über** ein
Projekt. Im **Lokal-Modus** ist ① **absent** (H4).

---

## 5. Hub-Kontext-Zeile & Hub-Switcher

Die Fern-Betrieb-Chrome (CYP-429 §9) wird zur **obersten Shell-Zeile** und trägt den **Hub-Switcher**.

- **Inhalt (neutral, INFO-Register, CYP-429-Töne):** „● **Fern-Betrieb: Hub X**" · „E2E via Relay" · „Identität gepinnt" ·
  subtile Latenz · **`[Hubs ▾]`**-Switcher. Nie `tertiary`-Grün; zeigt **meine** Session, nicht Registry-Presence.
- **Hub-Switcher = `Hubs ▾`** (Vorbild `Projekte ▾`-DropdownMenu, `ProjectSwitcherBar.kt:157`): listet die registrierten
  Hubs (`ControlPlaneClient.hubs()`, Presence advisory/H1), aktiver Hub ist **kein** Ziel. Auswahl → Hub-Wechsel (§6).
- **Platzierung (Q1, §14):** Spec-Default = **eigene erste Shell-Zeile** (Hub scoped über Projekt, H1). Alternative =
  Sibling-Control **in** der `ProjectSwitcherBar`-Row (`Hubs ▾` neben `Projekte ▾`). Empfehlung: eigene Zeile — hält die
  Hub→Projekt-Hierarchie visuell sauber; die `ProjectSwitcherBar` bleibt projekt-scoped.
- **Mid-Session-Switch (Q2, §14):** ist der Hub-Wechsel **jederzeit** aus dem aktiven Workspace erreichbar (Default: ja,
  über `Hubs ▾`) — mit dem vollen Teardown (§6). Bestätigen.
- **Lokal-Modus:** die ganze Zeile ① ist **absent** (kein „Fern-Betrieb", kein `Hubs ▾` wenn nur ein lokaler Hub); der
  Einstieg bleibt der HubConnect-Flow (§4).

---

## 6. Hub-Wechsel-Teardown auf Shell-Ebene (H2, CYP-429 Q5)

Ein Hub-Wechsel ist ein **voller, ehrlicher Shell-Teardown** — kein Fenster des alten Hubs überlebt.

1. **Teardown:** alle Floating-Windows/Overlays des aktiven-Hub-Workspace **schließen**; pending/in-flight-State räumen
   (CYP-429 Q5 — nichts wird über Hubs getragen); die alte Noise-Session/`HubTransport` **abreißen**.
2. **Transition:** Shell zeigt „**Trenne von Hub X … verbinde mit Hub Y**" (`remote_switch_transition`); der WindowHost
   ist leer/neutral (kein stehendes altes Layout).
3. **Connect + Trust:** neuer Relay-Connect (CYP-429 §7) + **TOFU-Check** für Hub Y (§8); non-optimistisch — der aktive
   Hub flippt **erst nach** etablierter neuer Session.
4. **Re-Ableitung:** der WindowHost **leitet den neuen Fenster-Satz** aus dem neuen Hub/Projekt ab (`syncWindows` frisch,
   kein `resetTo` vom alten Layout). **In-flight-Aktionen am alten Hub = ehrlich ungewiss** (nie still als erledigt).
5. **Transport-Re-Point:** genau an `resolvedTransport` (`AgentShell.kt:301`) — der neue `HubTransport` liefert die neuen
   Base-URLs; alle Repos/WS re-subscriben.

**Ein aktiver Hub (MVP):** kein Zwei-Hub-Multiplex, keine parallelen Hub-Workspaces (CYP-429 Q5). Der Teardown ist die
ehrliche Konsequenz von „ein Hub = eine Ressourcen-Einheit".

---

## 7. Globale Relay-Verbindungs-Fläche (H3, CYP-429 H4)

Die **eine** globale Relay-Drop-Fläche (die Lücke aus CYP-429) lebt **hier** in der Shell-Chrome, **über** dem WindowHost.

- **Bei Drop:** ein globaler Zustand „**Remote-Verbindung zu Hub X unterbrochen — verbinde neu…**" (`remote_relay_dropped`,
  neutral `onSurfaceVariant`) in Zeile ③ — **nicht** N per-Fenster-Chips (H3). Der WindowHost darunter bleibt sichtbar
  (Historie), aber **in-flight-Aktionen sind ehrlich ungewiss** (CYP-429 §10).
- **Reconnect:** `Reconnect.kt`-Backoff + Cursor-Resume (gapless); nach Wiederkehr klärt die Fläche sich selbst.
- **Latenz-Degradation (CYP-429 Q3):** „langsam/instabil" erscheint in der Hub-Kontext-Zeile ① (subtil, neutral, nur bei
  echter Degradation) — **getrennt** vom harten Drop (③).
- **Lokal-Modus:** ③ ist **absent** (kein Relay).

---

## 8. Eingebettete Operator-Surfaces (aktiver-Hub-Workspace)

Die bestehenden Floating-Windows **sind** der Workspace — **kein Neubau**, nur remote-adaptiert.

- **Fenster-Satz (bestehend):** Agentenfenster (`AgentWindow`), Comm/Timeline (`CommPanel`), ACL (`AclPanel`),
  Agenten-Verwaltung (`AgentManagementPanel` + Connector-Picker), Settings, Compact, Event-Log Browse/Tail (operator),
  Product-Lead/Roster (operator), Projekt-Switcher-Bar. Alle laufen **über den Remote-Transport** (Re-Point §6).
- **Remote-Adaption = CYP-429-Ehrlichkeit (H5), keine neue Optimistik:** Composer optimistisch (pending→ungewiss-bei-Drop),
  ACL Echo+Watchdog, Rest confirmed = relay-sicher. Die Shell reicht nur den Transport durch.
- **Fenster-Management unverändert:** frei-schwebend (`WindowCanvas`), Z-Order, Fokus, „Fenster einpassen", Per-Agent-
  Settings-Overlay — alles CYP-26/bestehend. Der **Hub-Kontext** rahmt sie, ändert ihr Innenverhalten nicht.

---

## 9. Lokal/Remote-Shell-Parität (H4)

- **Eine Shell, zwei Modi.** Lokal (heute) und Remote (Team-1) teilen `AgentShell` + WindowHost + Surfaces.
- **Unterschied ehrlich:** Remote **fügt** die Hub-Kontext-Zeile ① (Fern-Betrieb/E2E/gepinnt/Latenz/`Hubs ▾`) + die
  Relay-Drop-Fläche ③ **hinzu**; Lokal **hat beide nicht** (fail-closed absent, kein Phantom). Der PoP-Schritt (§4) gilt
  nur für Remote-Zielbuilds.
- **Kein Modus-Bluten:** die Shell zeigt nie Fern-Betrieb-Chrome im Lokal-Modus (täuschte Remote vor) und nie „lokal"
  während einer Remote-Session.

---

## 10. Responsiv / Adaptiv

- **Desktop-Native zuerst (Team-1):** frei-schwebende `WindowCanvas`, Column-Cap by `WindowSizeClass`, „Fenster einpassen"
  (CYP-26). Die neue **Hub-Kontext-Zeile ①** folgt dem `ProjectSwitcherBar`-Muster: bei schmaler Breite (`<400`,
  bestehender Bar-Breakpoint) **kompakte** Darstellung (Hub-Name + `Hubs ▾` gekürzt, Indikatoren als Icons+a11y).
- **Compact/Phone (Team-2 später):** `PhonePager` (eine Seite/Fenster) bleibt; die Hub-Kontext-Zeile wird zur **schlanken
  Kopfzeile**. Detail delegiert an die Phone-Slice (analog CYP-54).
- **Kein Layout-Bruch:** ① + ③ sind schlanke Zeilen über dem Host; sie verdrängen den Host nicht unter die Mindesthöhe
  (Floor-Guard-Prinzip, CYP-389).

---

## 11. Maritim + Material 3

- **Neutral** (`onSurfaceVariant`/INFO `secondary`) für Hub-Kontext, E2E/gepinnt/Latenz — **nie** `tertiary`-Grün.
- **WARN-Amber** (`EventVisuals`) nur für den TOFU-Trust-Änderungs-Alarm (CYP-429 §8.1); **errorContainer** nur harte
  Connect-Fehler; der Relay-Drop (③) ist **neutral** (erwarteter Reconnect, kein Alarm-Rot).
- **Farbe nie allein** (1.4.1); Dark/Light über `maritimeColorScheme`.

---

## 12. testTag-Kontrakt (provisorisch — friert nach Ratifikation)

Fast **reine Reuse** — die Surfaces + der Remote-Flow tragen ihre Tags schon (`window.*`, `project*`, `workspace.*`,
`remote.*`, `hubConnect.*`). Net-new = die **Shell-Regionen**:
```
shell.hubContext            (§5, Zeile ①; absent im Lokal-Modus)
shell.hubContext.switch     (§5, `Hubs ▾`)
shell.relayDrop             (§7, Zeile ③; global, nur bei Drop)
shell.switchTransition      (§6, „Trenne … verbinde")
```
**Reuse:** `remote.context.banner/.hub/.latency/.reconnecting`, `remote.trust.*`, `hubConnect.*` (Liste/Connect),
`window.host`/`window.<id>.*`, `projectSwitcher.*`, `workspace.roleIndicator`.
**Fail-closed-Anker:** `shell.hubContext` + `shell.relayDrop` **absent im Lokal-Modus**; nach Hub-Wechsel **kein**
`window.<altId>` des alten Hubs; `remote.connect.connected` nie vor echtem LIVE.

## 13. Copy (provisorisch — meist Reuse)

Fast alles reused CYP-429 (`remote_*`) + hubConnect (`hubconnect_*`) + bestehend (`project_*`, `window_*`). Net-new (Shell):
| Key | DE | EN |
|---|---|---|
| `shell_hub_switcher` | Hubs | Hubs |
| `shell_hub_switch_confirm` | Auf Hub %1$s wechseln? Der aktuelle Hub-Workspace wird geschlossen. | Switch to hub %1$s? The current hub workspace will close. |
| `a11y_shell_hub_context` | Hub-Kontext: Fern-Betrieb auf %1$s | Hub context: remote session on %1$s |

*(Reuse: `remote_switch_transition`, `remote_relay_dropped`, `remote_context_operating`, `remote_e2e_indicator`,
`remote_trust_pinned`, `hubconnect_*`-Liste/Connect, `project_switcher_active`, `window_fit_action`.)*

## 14. Offene Entscheidungen

1. **Q1 — Hub-Kontext-Zeile: eigene erste Shell-Zeile (Default) vs. Sibling in `ProjectSwitcherBar`?** Empfehlung:
   **eigene Zeile** (Hub scoped über Projekt, saubere Hierarchie).
2. **Q2 — Mid-Session-Hub-Switch erreichbar?** Default: **ja** (aus `Hubs ▾`, voller Teardown §6). Bestätigen.
3. **Q3 — Hub-Switch-Bestätigung:** ein Confirm-Dialog vor dem Teardown (`shell_hub_switch_confirm`) — Default **ja**
   (Teardown ist konsequenzenreich), analog Projekt-Switch-Semantik. Bestätigen.
4. **Q4 — Lokal-Modus-Einstieg mit Hub-Layer:** wird der HubConnect-Flow (§4) auch für **rein lokale** Team-1-Builds
   gemountet (dann Hub-Liste mit einem lokalen Hub), oder bleibt Lokal der direkte `AgentShell` (kein Hub-Layer)?
   Empfehlung: Hub-Layer **einheitlich** mounten (ein Pfad), Lokal = Hub-Liste mit dem lokalen Hub, ① absent.

## 15. Nahtstellen (über den PO)

- **S-1 — CYP-429-Konsistenz:** Fern-Betrieb-Banner/Relay-Drop/TOFU/PoP-Töne + Copy **identisch** zu CYP-429; die Shell
  hostet sie, erfindet keine Varianten.
- **S-2 — CYP-443-Client-Zustände:** seam-konsistent zu `HubConnectUiState`, `LocalConnectFeed` (`ConnectProgress`/
  `ConnectCause`), `ControlPlaneClient` (`HubDescriptor`), `HubTransport.sessionToken()`, `ConnectionStatus`. CYP-443
  (Client-Transport/Session/PoP-Build, eigener Worktree) liefert die Laufzeit-Zustände; die Shell rendert sie, rät nicht.
- **S-3 — Transport-Re-Point:** Hub-Wechsel re-pointet `resolvedTransport` (`AgentShell.kt:301`) + reißt Windows ab
  (Q5-Teardown) — Dev-Verdrahtung; der `WindowManagerState` bekommt ein frisches `resetTo` je Hub.
- **S-4 — Ein-aktiver-Hub-Invariante:** Backend/Transport garantiert eine aktive Session; die Shell-UX setzt das um
  (kein Multiplex).
- **Drift-Hinweis:** neue `shell_*`-Keys + `shell.*`-Tags landen mit Devs Slice → Re-Sync Tester (CYP-7).

## 16. Acceptance-Teeth (für spätere §-QA)

1. **Scope-Hierarchie (H1):** Hub-Kontext ① über Projekt ② über Fenstern ④; aktiver Hub jederzeit legibel.
2. **Hub-Wechsel-Teardown (H2/Q5):** nach Wechsel **kein** Fenster/State des alten Hubs; Transition sichtbar; neuer
   Fenster-Satz frisch abgeleitet; in-flight am alten Hub ehrlich ungewiss; non-optimistisch (flip erst nach LIVE).
3. **Globale Relay-Drop-Fläche (H3):** **eine** Shell-Fläche ③, nicht N Chips; neutral (kein Alarm-Rot); Host darunter
   sichtbar mit ehrlicher Ungewissheit.
4. **Lokal/Remote-Parität (H4):** ① + ③ **absent im Lokal-Modus** (fail-closed); nie Modus-Bluten.
5. **Surfaces erben CYP-429 (H5):** eingebettete Fenster ohne neue Optimistik; Composer/ACL/confirmed wie CYP-429.
6. **App-vs-OS-Chrome (H6):** minimales OS-Fenster; Scope-Chrome app-eigen; kein vorgetäuschtes natives Menü.
7. **Reuse:** Hub-Flow = gemountete `connect/`-UX; Surfaces = bestehend; Töne/Copy = CYP-429/hubConnect; Fenster =
   CYP-26 — keine divergenten Einmal-Teile.
8. **Responsiv (CYP-26/389):** ① kompakt `<400`; kein Host-Floor-Bruch; `PhonePager` bleibt.
9. **Presence ≠ connected & Farbe nie allein (1.4.1):** durchgängig; Dark/Light über `maritimeColorScheme`.

---

*Design-Aufschlag, nichts gebaut, kein Bau vor Build-GO. Baut auf CYP-429; Naht-Konsistenz zu CYP-429 + CYP-443-Client-
Zuständen über den PO. Companion-Files (`desktop-shell-keys/-tags/-tokens`) nach Ratifikation der §14-Entscheidungen
eingefroren; vorher würden sie driften.*
