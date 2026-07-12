# Desktop Remote-Operating-Surface UX — Konsolidierung · Grounding · Seam-Map (CYP-449, Epic CYP-427)

> Status: **Spec-Closure — als CYP-449-Referenz eingefroren (PO 2026-07-12)** · docs-only, **kein Bau** · Owner: UX/UI · Stand 2026-07-12
> Die UX für **nachdem** man remote „auf einem Hub" ist: Agenten/Projekte konfigurieren + mit Agenten chatten über den
> E2E-Tunnel, Verbindungs-/Reconnect-Zustände im Betrieb, Presence, Kohärenz lokal↔remote (dieselbe Logik, zwei Transporte).
> Gegen echten Code gegroundet (develop `1f90cba4`, read-only). Baut auf **CYP-449-Shell** (Rahmen), **CYP-429 §7/§9/§10**
> (Zustände/Banner/Surface-Reuse), **CYP-480** (Trust-Confirm/Recovery/Revoke = das „Reinkommen").
> **Konsolidierungs-/Referenz-Doc — 0 net-new Keys/Tags** (alles Reuse; Q1=global geruled → kein per-Aktion-Marker).

---

## 0. Headline-Befund (Grounding) — die Kohärenz ist schon gebaut

**Es gibt EINE Operator-Fläche (`AgentShell`), getrieben von einer Transport-Abstraktion (`HubTransport`) — mit NULL
remote-spezifischer Verzweigung.** Jede REST-Repo + WS-Live-Quelle liest `httpBaseUrl`/`wsBaseUrl`/`httpClient` vom
injizierten Transport (`AgentShell.kt:301-303`, :458/467/499/540). ⟹ **Die „dieselbe Logik, zwei Transport-Modi", die der
PO will, IST die gebaute Architektur** — die Operator-Fläche braucht **keine** neue Remote-UX, nur den **Transport
verdrahtet** + die schon-spezifizierte Remote-Chrome **gemountet**. Heute ist nur LOKAL verdrahtet:
`TransportModeResolver.defaultMode()=LOCAL` (`:17`), `RemoteHubTransport` = **fail-loud Stub** (`RemoteHubTransport.kt:15`).

### 0.1 Overlap-Notiz (Anti-Duplikat) — Konsolidierung, KEIN Neubau
Diese Fläche ist bereits **weitgehend spezifiziert** verteilt über: **CYP-449-Shell** (§5 Hub-Kontext-Zeile/§7
globale-Relay-Drop-Fläche/§8 eingebettete Surfaces/§9 Parität), **CYP-429** (§7 Verbindungszustände / §9 Fern-Betrieb-Banner /
§10 „Voller Operator-Surface remote" mit Optimistic-vs-confirmed) und **CYP-480** (Revoke-Control). **Ich re-spezifiziere das
NICHT** (das wäre das Divergente-Duplikat-Anti-Pattern). Dieser Pass = **(a)** Konsolidierung dieser Frozen-Stücke zu EINER
Desktop-„operating-remotely"-Karte, **(b)** Grounding gg. den gemergten Code (was gebaut/Reuse vs. Seam), **(c)** Gap-Fill der
3 dünn-spezifizierten Ehrlichkeits-Punkte (Hand-off, `inFlightUncertain`-Konsum, Revoke-Mount).

---

## 1. Kern-Ehrlichkeit (tragende Entscheidungen)

- **HA — Transport-agnostisch, nicht remote-dupliziert.** Die Operator-Fläche ist EINE (`AgentShell`); Remote ist ein
  **Transport-Swap** (`RemoteHubTransport` statt `LocalHubTransport`), keine zweite UI. Kein „Remote-Operator-Screen".
- **HB — Nichts vorzeichnen, das nicht wired ist (RR5).** Der reale Remote-Transport ist Stub (fail-loud); alle
  „operating-remotely"-Flächen sind **State-Modell + Frozen-Spec, Backing nicht gebaut**. Ich zeichne die Ziel-Mount-UX +
  flagge die 8 Wiring-Seams; kein Feature vorgetäuscht.
- **HC — Getting-in → operating = non-optimistischer Hand-off.** Der Remote-`CONNECTED`-Zustand mountet die Operator-Fläche
  **erst nach echtem LIVE** (nie vorher); heute existiert **kein** Hand-off (Seam, §3).
- **HD — In-flight ehrlich ungewiss (H4, echtes Feld).** `RemoteSessionState.inFlightUncertain` ist ein **realer** Feld
  (`RemoteSessionState.kt:43`, gesetzt bei Drop `RemoteHubSession.kt:74`) — **kein UI-Konsument heute**. Bei Relay-Drop
  markiert die Fläche laufende Aktionen ehrlich ungewiss, nie still als erledigt (§5).
- **HE — Presence ≠ connected (H1/H6).** Drei getrennte Wahrheiten, nie konflatiert: Registry-Presence (advisory,
  neutral, nie grün) · meine-Remote-Session (Bodenwahrheit, Kontext-Banner) · Agent-Status (per-Agent, transport-agnostisch).
- **HF — Lokal/Remote-Parität, Chrome konditional (CYP-449-Shell H4/Q4).** Remote **fügt** Kontext-Banner + Relay-Drop
  **hinzu**; Lokal hat beide nicht (fail-closed absent, kein Phantom). Kein Modus-Bluten.

---

## 2. Kohärenz-Spine: eine Fläche, zwei Transporte (Reuse, gegroundet)

- **`HubTransport`** (`net/hub/HubTransport.kt:17`, EXISTS) — `httpBaseUrl`/`wsBaseUrl`/`httpClient`/`sessionToken()`/
  `close()`; KDoc: „identical in Local and Remote mode". `LocalHubTransport` (`:19`, EXISTS) · `RemoteHubTransport`
  (`expect`, **fail-loud Stub**, jvm/android actuals) · `TransportModeResolver` (`defaultMode()=LOCAL`).
- **`AgentShell`** (transport-agnostisch, EXISTS): alle Panels/WS lesen `resolvedTransport` (`AgentShell.kt:301`). ⟹ **derselbe
  `WindowHost`/`AgentWindow`/`MessageComposer`/Config-Panels dienen Remote unverändert**, sobald ein echter
  `RemoteHubTransport` injiziert ist. **Das ist die Kohärenz** — kein separater Pfad.
- **Konsequenz für die UX:** die „operating-remotely"-Arbeit ist **Wiring + Mount + konditionale Remote-Chrome**, nicht
  neue Surfaces. Reuse: gesamtes Panel-Set (`AgentManagementPanel`/`ProjectManagementPanel`/`AclPanel`/`EventTailPanel`),
  `ProjectSwitcherBar.switchTo` (non-optimistisch, `ProjectViewModel.kt:122`), `ConnectionStatus`, `TonedHint`, `EventVisuals`.

---

## 3. Getting-in → Operating: der Hand-off (GAP-FILL, HC — Q3 geruled: Loading-Gate reicht)

Heute: `App` geht `AuthGate → AgentShell` direkt; `HubConnectFlow` hat **null Caller** (nie gemountet); auf
`RemoteConnState.CONNECTED` rendert `RemoteConnectingView` nur eine „● connected"-Zeile (`HubConnectSelection.kt:224-232`) —
**kein Hand-off in `AgentShell`** (`onEnterWorkspace` feuert nur aus dem Onboarding-`ReadyView`).
- **Ziel-UX (non-optimistisch, HC):** nach **echtem** `CONNECTED` (LIVE) → mountet die Operator-Fläche für den aktiven Hub;
  die bestehende **`AgentShell`-Loading-Gate** (`AgentShell.kt:377`) deckt „Workspace wird geladen" (**0 net-new Copy**, Q3).
  Der aktive Hub flippt **erst nach** LIVE (nie vorher); reject/drop vor LIVE → zurück zum Connect, ehrlicher Fehler.
- **Reuse:** CYP-449-Shell §4 (`AuthGate → PoP → HubConnect → AgentShell`-Verdrahtung); knüpft an die schon-live
  Shell-Verdrahtung (CYP-355/356/381) an — **dieser Pass macht den CONNECTED→Shell-Hand-off explizit** (der Teil, den
  CYP-449-Shell §4 skizziert, aber nicht als Zustandsübergang detailliert). **Seam S-3.**

---

## 4. In-Operation-Chrome (REUSE CYP-449-Shell §5/§7 + CYP-429 §9) — Mount-Map

Alles hier ist **frozen-spec'd, NOT-BUILT** → Mount-Seams, kein Neu-Design:
- **Fern-Betrieb-Kontext-Banner** (oberste Shell-Zeile, CYP-449-Shell §5 / CYP-429 §9): „● Fern-Betrieb: Hub X" · **E2E via
  Relay** (`remote_e2e_indicator`, nur Transport, nie grün, §8.2) · **Identität gepinnt** (`remote_trust_pinned`) · **subtile
  Latenz** (Daten existieren: `LatencyHint`/`RemoteHubSession.recordLatency:165`) · `[Hubs ▾]`-Switcher. **Absent im
  Lokal-Modus.** Tags `remote.context.*` (CYP-429) / `shell.hubContext` (CYP-449). NOT-BUILT (nur KDoc-Referenz
  `RemoteRevokeControl.kt:42`).
- **Globale Relay-Drop-Fläche** (Shell-Zeile über WindowHost, CYP-449-Shell §7 / CYP-429 §7-H4): **eine** neutrale Fläche
  „Verbindung zu Hub X unterbrochen — verbinde neu…" (`remote_connect_relay_dropped`/`RemoteConnectTags.RELAY_DROP`), **nicht**
  N per-Fenster-Chips. State existiert (`RemoteConnState.RECONNECTING`), **kein Shell-Konsument** (NOT-BUILT). Reconnect =
  Backoff + Cursor-Resume (gapless), `RemoteHubSession.kt:68-87`.
- **Revoke-Control-Mount** (CYP-480, Q2 geruled — Kontext-Zeile): `RemoteRevokeControl` (`connect/RemoteRevokeControl.kt:46`)
  ist **gebaut, render-tested, aber nicht live gemountet** — `onEndSession` soll an `backToHubList()`/`RemoteHubSession.close()`.
  **Mount-Ort: in der Hub-Kontext-Zeile** (die „Fern-Sitzung beenden"-Kontrolle gehört zur aktiven-Session-Chrome). Seam S-8.

---

## 5. In-Operation-Surface-Ehrlichkeit (REUSE CYP-429 §10, gegen echte Panels gegroundet)

Die eingebetteten Fenster laufen über den Transport (transport-agnostisch); ihre **Optimistic-vs-confirmed-Ehrlichkeit**
gilt unverändert (keine neue Optimistik). Gegroundet gegen die realen Panels:
- **Confirmed-Surfaces (relay-sicher):** Agent-CRUD (`AgentManagementPanel`, `AgentManagementViewModel`, non-optimistisch),
  Projekt-Config (`ProjectManagementPanel`), Projekt-Wechsel (`ProjectViewModel.switchTo:122`, nur `.onSuccess`), Lifecycle,
  Settings. Sie warten ohnehin auf Server-Confirm → über Relay nur „länger", nie vorgetäuscht. **Reuse unverändert.**
- **Composer (optimistisch, `AgentWindow.MessageComposer`/`AgentViewModel.onSend:312`):** „sendet…"-Zustand bleibt; **bei
  Relay-Drop** (echtes `inFlightUncertain`, HD) wird die pending-Nachricht **ehrlich ungewiss** markiert, beim Reconnect
  confirm-or-resend (Idempotenz via message-id) — nie doppelt-gesendet vorgetäuscht.
- **ACL (`AclPanel`, optimistic-visuell + Echo-Watchdog):** über Relay ist der Echo-Watchdog genau richtig — bleibt das Echo
  aus (Drop), **revert** die Zelle (kein vorgetäuschtes Grant). Bestehendes Muster, remote-tauglich.
- **`inFlightUncertain`-Konsum (Q1 geruled — global, 0 net-new):** das Feld hat **keinen UI-Konsumenten** heute
  (`RemoteSessionState.kt:43`). Der Konsum = die eigentliche Gap-Fill-Arbeit: die **globale Relay-Drop-Fläche** (§4) trägt das
  **Warum**; der bestehende Composer-„sendet…"-Zustand trägt die per-Aktion-Ungewissheit. **Kein per-Aktion-Marker im MVP**
  (H4 wird ehrlich vom globalen Banner getragen; per-Aktion-Granularität erst wenn nötig).

---

## 6. Presence-Ehrlichkeit (H1/HE) — drei getrennte Wahrheiten (gegroundet)

- **Registry-Presence** (advisory, Hinweis, **nie grün**): `PresenceRow` (`HubConnectSelection.kt:124-147`,
  `HubDescriptor.online`/`lastSeen`, Stub-CP bis S-J) — **nur im Pre-Connect-Hub-Picker**, nicht in-operation. Korrekt so.
- **Meine-Remote-Session** (Bodenwahrheit): der Kontext-Banner (§4) zeigt **meine** Session, nicht Registry-Presence.
- **Agent-Status** (per-Agent, transport-agnostisch, EXISTS): `AgentStatus{RUNNING/IDLE/WAITING_FOR_INPUT/ERROR/OFFLINE}`
  (`AgentStatus.kt:14`), `deriveStatus` (`AgentViewModel.kt:232`) — fließt über den Transport, **unverändert remote**. Das
  ist die „Presence-Anzeige" im Betrieb (welche Agenten laufen), **reuse**, kein net-new.
- **Ehrlichkeits-Regel:** die drei nie ineinander färben — Registry-online ≠ meine-Session-LIVE ≠ Agent-RUNNING.

---

## 7. Lokal/Remote-Parität (REUSE CYP-449-Shell §9)

Eine Shell, EIN Code-Pfad, zwei Modi (CYP-449-Shell Q4). Remote **fügt** Kontext-Banner (§4) + Relay-Drop-Fläche **hinzu**;
Lokal **hat beide nicht** (fail-closed absent, HF). Kein separates Layout, kein Modus-Bluten. **Unverändert übernommen** —
dieser Pass bestätigt die Parität gg. gemergten Code (transport-agnostisch, §0/§2), erfindet keine Variante.

---

## 8. Net-New-Delta = 0 (reine Konsolidierung)

**Alles Reuse — keine neuen Keys/Tags** (Q1=global geruled): Kontext-Banner (`remote_context_operating`/`remote_e2e_indicator`/
`remote_trust_pinned`, `remote.context.*`, CYP-429), Relay-Drop (`remote_connect_relay_dropped`/`RemoteConnectTags.RELAY_DROP`),
Hub-Switch (`shell_hub_*`, CYP-449), Revoke (`remote_revoke_*`/`RemoteRevokeTags`, CYP-480), Surfaces/Presence (bestehend,
transport-agnostisch), Hand-off-Loading (bestehende Shell-Loading-Gate). **Verworfener Kandidat:** der per-Aktion-„ungewiss"-
Marker (`remote_action_uncertain`) — **nicht** angelegt (Q1=global; globaler Banner reicht im MVP).

---

## 9. Entscheidungen (PO 2026-07-12) — geruled

- **Ticket/Overlap:** **Option A** — als **CYP-449-Referenz** eingefroren (Konsolidierung/Vervollständigung der
  CYP-449-Shell); PO merged + zieht CYP-449 auf Fertig (Design geliefert).
- **Q1 — In-flight-Granularität = global** (globale Relay-Drop-Fläche + Composer-Zustand; **kein** per-Aktion-Marker, 0 net-new).
- **Q2 — Revoke-Mount = Hub-Kontext-Zeile** (§4).
- **Q3 — Hand-off-Transition = bestehende Shell-Loading-Gate** (0 net-new, §3).

---

## 10. Seam-Map (Wiring-Seams, gegroundet — an Dev über PO, ride mit RR5/CYP-459+)

Alle „operating-remotely"-Flächen hängen an diesen **8 Seams** (RR5-live-gated):
1. **`RemoteHubTransport`-actual über Noise-Tunnel** + Ktor-over-Tunnel-Engine (`RemoteHubSession.tunnel`-Konsument) — heute
   fail-loud Stub. **Das eine, das die reused Shell remote funktionieren lässt.**
2. **`TransportModeResolver` Mode-Flip auf REMOTE** (S-J) — heute hard-LOCAL.
3. **Mount `HubConnectFlow` in `App`/AuthGate + CONNECTED→`AgentShell`-Hand-off** (heute null Caller, kein Hand-off, §3).
4. **In-Operation-Kontext-Banner** (`remote.context.*`) mounten (§4) — NOT-BUILT.
5. **E2E-Indikator in-operation** (`remote.trust.e2eIndicator`) — NOT-BUILT.
6. **Mid-Operation-Relay-Drop-Fläche** in der Shell, konsumiert `RECONNECTING` + `inFlightUncertain` (§4/§5) — State da,
   kein Konsument.
7. **In-Operation-Presence** (meine-Session vs Registry, in der Shell) — Registry-Presence nur im Pre-Connect-Picker (§6).
8. **`RemoteRevokeControl.onEndSession` live mounten** → `backToHubList()`/`RemoteHubSession.close()` (§4) — Control gebaut,
   Wiring absent.

*(Hinweis: **CYP-482T1 (S-A Fingerprint-Ableitung) ist bereits gemergt** in develop `1f90cba4` — die Trust-Confirm-Bytes
fließen; das ist der erste der RR5-nahen Seams, der landet.)*

---

## 11. Acceptance-Teeth (für spätere §-QA, wenn die Seams landen)

1. **Transport-agnostisch (HA):** dieselbe `AgentShell`/`WindowHost`/Panels remote wie lokal; keine remote-spezifische
   Surface-Variante; nur Transport-Swap.
2. **Hand-off non-optimistisch (HC):** Operator-Fläche mountet **erst nach echtem LIVE**; reject/drop davor → zurück, ehrlich.
3. **In-flight ehrlich ungewiss (HD):** globale Relay-Drop-Fläche + Composer-Ungewissheit; nichts still als erledigt.
4. **Presence 3-getrennt (HE):** Registry-advisory ≠ meine-Session ≠ Agent-Status; nie konflatiert, nie grün-als-Status.
5. **Parität (HF):** Kontext-Banner + Relay-Drop **absent im Lokal-Modus**; kein Modus-Bluten.
6. **Reuse (Anti-Duplikat):** Chrome = CYP-449-Shell/CYP-429; Revoke = CYP-480; Surfaces = bestehend; 0 net-new — keine
   divergenten Einmal-Teile.

---

*Spec-Closure — als CYP-449-Referenz eingefroren (PO 2026-07-12). Konsolidierung/Grounding/Gap-Fill, kein Neubau
(Anti-Duplikat, §0.1). 0 net-new Keys/Tags. Gegen echten Code gegroundet (develop `1f90cba4`, read-only, nichts angefasst).
Nichts gebaut — die 8 Seams (§10) reiten mit dem Remote-Transport-Wiring (RR5/CYP-459+). Alle Seams über den PO.*
