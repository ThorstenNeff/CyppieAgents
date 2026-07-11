# Remote-Modus Operator-UX — Design-Spec (CYP-429, Epic CYP-427 „Phase 2: Remote-Modus")

> Status: **Design-Aufschlag** · docs-only, **kein Bau vor Ratifikation** · Owner: UX/UI
> Baut auf der **gebauten Phase-1-`hubConnect`-UX** (CYP-419, meine Spec CYP-395) auf — jetzt für **Remote**.
> **⚠ Auth-Schritt NICHT finalisiert** (RR2-B, offene Auftraggeber-Entscheidung — §5); Flow trägt einen **flexiblen
> Auth-Schritt-Seam**. Rest läuft unabhängig.
> Naht-Konsistenz (Keys/Tags/Auth-Flow) über den PO. Companion-Files (`remote-operator-keys/-tags/-tokens`) werden nach
> Ratifikation **+ Auth-Schritt-Entscheid** eingefroren (jetzt würden sie driften).
> Begleit-Konzept: `13-cyppie-hub-architektur.md` (Remote-Modus, Sequenz B REMOTE, E2E/Noise, Vertrauenszonen).

Der Operator loggt sich **zentral** ein, sieht seine registrierten Hubs, **„wechselt" auf einen** und bedient ihn **voll**
über die Control Plane als **E2E-verschlüsseltes Relay**. Diese Spec deckt: zentraler Login (+ flexibler Auth-Schritt) →
Hub-Auswahl/„auf Hub wechseln" → Remote-Verbindungszustand → Trust-Affordances → voller Operator-Surface remote. Maritim + M3.

---

## 1. Kern-Ehrlichkeit (die tragenden Entscheidungen)

Remote-Betrieb über ein Relay hat **erhöhte Disclosure-Einsätze** — was ist verschlüsselt, wem vertraue ich, ist meine
Aktion wirklich angekommen. Diese Punkte sind die Wirbelsäule; die Screens (§4–§10) setzen sie um, die Teeth (§16) prüfen sie.

- **H1 — Zwei getrennte Trust-Wahrheiten, nie konflatiert.** (a) **Transport-Vertraulichkeit** — „E2E via Relay": die
  Control Plane leitet nur **Ciphertext** weiter (Noise-Handshake, Konzept §Remote), garantiert durch Krypto. (b)
  **Hub-Authentizität** — spreche ich wirklich mit **meinem** Hub? Das leistet die **TOFU-Identität** (§8), nicht die
  Verschlüsselung. Ein „E2E"-Indikator darf **nie** als „Hub ist vertrauenswürdig" gelesen werden.
- **H2 — TOFU ist ehrlich über seine Grenzen.** **Erstverbindung** = Vertrauen auf Treu und Glauben (der Nutzer akzeptiert
  den öffentlichen Schlüssel des Hubs); die UI sagt das offen und lädt zur **Out-of-Band-Fingerprint-Prüfung** ein.
  **Spätere** Verbindungen verifizieren gegen den **gepinnten** Schlüssel. Ein **geänderter** Schlüssel ist ein
  **potenzielles MITM** → **harter WARN/Block**, **nie** still akzeptiert (§8).
- **H3 — „E2E via Relay"-Indikator = ehrliche Transport-Garantie, nicht mehr.** Aussage: „die Control Plane kann das
  nicht mitlesen" (wahr, per Architektur). **Keine** pauschale „alles sicher"-Aussage; die Hub-Vertrauensfrage bleibt bei
  H1/H2.
- **H4 — Remote ist fragiler: EIN globales Relay-Drop-Surface.** Fällt das Relay, ist das **eine** globale Tatsache —
  **nicht** N verstreute per-Agent-„reconnecting"-Chips (die heute existieren, §3), die einen lokalen Schluckauf
  vortäuschen. In-flight-Aktionen bei Disconnect werden **ehrlich als ungewiss** markiert, **nie** still als erledigt
  angenommen.
- **H5 — Optimistic vs. confirmed über das Relay.** Heute ist **nur der Composer** echt optimistisch (temp-id, „sendet…",
  drop-on-fail); **ACL** ist optimistic-visuell **+ Server-Echo + Watchdog** (revert bei fehlendem Echo); **alles
  andere** (Projekt-/Hub-Wechsel, Agent-CRUD, Connector, Settings, Lifecycle, Hand-off) ist **confirmed/re-fetch** —
  wartet auf den Server, also **relay-sicher**. Remote-Delta: die confirmed-Surfaces bleiben ehrlich; **Composer + ACL**
  brauchen bei Relay-Drop klare **Ungewissheits-Zustände** (§10).
- **H6 — „Fern-Betrieb: Hub X" ist eine ehrliche persistente Erinnerung** (Aktionen laufen übers Netz), **neutral/INFO**,
  **kein** Alarm — und folgt der Phase-1-Regel **Presence ≠ connected** (neutral, **nie** `tertiary`-Grün).
- **H7 — Latenz ist advisory.** Eine RTT-Anzeige ist ein **Hinweis**, keine Garantie; **hohe Latenz ≠ getrennt**; nicht
  alarmieren (kein Rot/Amber für „langsam"). Neutral, „nie grün vorzeitig".
- **H8 — Auth-Schritt-Seam offen (RR2-B).** Der Flow trägt einen **flexiblen Schritt** zwischen zentralem Login und
  Hub-Liste, der **ggf.** eine **Passkey/WebAuthn-PoP-Bestätigung** verlangt (Härtung gegen CP-Operator-Seizure). Details
  sind eine **offene Auftraggeber-Entscheidung** — die Spec hält den **Slot**, **finalisiert ihn nicht** (§5).

---

## 2. Scope

- **Phase 2 / Remote:** zentraler Login (+ Auth-Schritt-Seam) → Hub-Registry/-Liste → „auf Hub wechseln" (Remote) →
  Relay-Verbindung + Trust → voller Operator-Surface remote.
- **Baut auf Phase-1 `hubConnect`** (CYP-419): dieselbe Hub-Liste (`ControlPlaneClient.hubs()`), dieselbe Presence-/H1-Disziplin,
  dasselbe non-optimistische Flip-Prinzip. Remote ist die **Aktivierung** des heute deaktivierten `mode.remote` +
  Relay-/Trust-Schicht — **Erweiterung, kein Ersatz**.
- **Nicht enthalten:** die Krypto selbst (Noise/`RemoteHubTransport`, Backend/S-K), Hub-seitige Registrierung (Phase-1),
  Multi-Operator-Kollaboration am selben Hub, Cloud-Managed-Hubs (Deployment-Detail).

---

## 3. Reuse-Karte (gegen echten Code gegroundet @ develop `4466ca20`)

- **Phase-1 hubConnect (CYP-419) — die Basis:** `connect/HubConnectViewModel.kt` + `HubConnectUiState`
  (`Preparing/LoadingHubs/HubsUnreachable/Register/Credentials/Ready/HubList/ChoosingMode/Connecting`),
  `ControlPlaneClient.hubs()` = „die Hubs des angemeldeten Kontos" (`HubDescriptor(hubId,name,online,defaultPort,lastSeen)`,
  Presence advisory/H1), `HubConnectSelection.kt` (HubRow, ConnectingView `Attempting→Handshake→Connected(●+primary)→Failed(cause)`),
  `HubConnectModeChooser.kt` (`mode.remote` heute **disabled** „kommt bald" — Remote **aktiviert** ihn), Area `hubConnect`.
- **Remote-Transport-Seam (stub, fail-loud):** `net/hub/RemoteHubTransport.kt` — `expect class RemoteHubTransport`,
  `NotYetAvailableException` „Remote-Modus (Noise-E2E über die Control Plane) ist noch nicht verfügbar — Phase 2". Wird in
  Phase 2 belegt. `HubTransport.sessionToken()` — S-K entwickelt den Kratos-Token zum **CP-issued, hub-scoped Ticket-JWT**
  („Client präsentiert, Hub verifiziert") = die Auth-Naht.
- **„Wechseln" = non-optimistisch (Vorbild):** `ProjectViewModel.switchTo` (`project/ProjectViewModel.kt:122`) —
  Server-Re-Fetch, flippt `activeProjectId` **nur** `onSuccess`, „nicht-destruktiver Kontext-Re-Fetch, nichts wird
  gelöscht" (`ProjectSwitcherBar.kt:62`). **„Auf Hub wechseln" ist der größere Bruder.** Server-autoritativer
  `runtimeState` je Projekt (`BACKGROUND`/`SUSPENDED`/`HOT`, `TonedHint(INFO)`) = Vorbild für Remote-Hub-Zustand.
- **Optimistic-vs-confirmed-Karte:** Composer **optimistisch** (`comm/CommViewModel.kt:246`, temp-id/`pending`/confirm-or-drop);
  ACL **optimistic-visuell + Echo + Watchdog** (`acl/AclViewModel.kt:217`, revert bei fehlendem Echo); Agent-CRUD/Connector/
  Settings/Lifecycle/Hand-off **confirmed** (`reload()` bzw. `onSuccess`-only bzw. non-optimistic `requestMode`).
- **Reconnect + Verbindungszustand:** `net/Reconnect.kt` (`Backoff` 250→5000, `reconnecting()`, Cursor-Resume `lastSeq`
  gapless), `ConnectionStatus{CONNECTING,LIVE,DISCONNECTED}`. **Kein globales Loss-Chrome** — heute nur per-Surface
  (`CommPanel.ConnectionBanner` errorContainer; `ReconnectingChip` neutral per-Agent). ⚑ **Der globale Relay-Drop (§7/H4)
  ist genau die Lücke.**
- **Kontext-Strip:** Rollen-Indikator-Zeile `WorkspaceTags.ROLE_INDICATOR` (`ProjectSwitcherBar.kt:98`, „● Du bist
  Operator" + „Operator: %1$s", neutral `onSurfaceVariant` ●) — natürlicher Ort für „● Fern-Betrieb: Hub X" (§9).
- **Töne:** `ui/TonedHint.kt` `HintTone{EFFECT_DEFERRED,GATED,INFO,ERROR}` — **kein WARN/kein Grün**; INFO=`secondary`,
  GATED=`onSurfaceVariant` neutral. WARN-Amber nur aus `EventVisuals` (für den Trust-Alarm §8). Login/Auth = `AuthGate`
  (`UserTier{OPERATOR,MEMBER}`, `X-Session-Token`).

---

## 4. Flow (Remote-Operator-Reise)

```
Zentraler Login (OIDC, Operator-Account)
        │  [AuthGate → Verified(OPERATOR)]
        ▼
[Auth-Schritt-Seam]  ← §5, OFFEN (RR2-B): ggf. Passkey/WebAuthn-PoP-Bestätigung; sonst durchgereicht
        ▼
Hub-Liste (deine registrierten Hubs)  ← reuse ControlPlaneClient.hubs(), Presence advisory/H1
        │  Auswahl eines Hubs
        ▼
Modus: [Lokal | Remote]   ← Remote jetzt AKTIV (Phase-1 disabled → Phase-2 aktiviert)
        │  „Auf Hub X wechseln" (Remote)  ← non-optimistisch, §6
        ▼
Remote-Verbindung  ← §7: Relay → Noise-Handshake → TOFU-Check → LIVE
        │  (Erstverbindung: TOFU-Bestätigung §8)
        ▼
Remote-Session: voller Operator-Surface  ← §10, mit „Fern-Betrieb: Hub X"-Kontext-Banner §9
        │  Disconnect → globales Relay-Drop-Surface §7 → Reconnect
```

---

## 5. Auth-Schritt-Seam (OFFEN, RR2-B — nicht finalisiert)

Zwischen zentralem Login und Hub-Liste sitzt ein **flexibler Auth-Schritt**. Der Auftraggeber entscheidet (RR2-B, gegen
**CP-Operator-Seizure**), ob Remote-Operator-Login **zusätzlich** eine **Passkey/WebAuthn-PoP**-Bestätigung verlangt.

- **Design-Regel:** der Flow trägt einen **Slot** (`auth-step`), der **null-oder-eins** Bestätigungs-Screen aufnimmt.
  Ist er leer → Login reicht direkt zur Hub-Liste durch (kein Phantom-Screen). Ist er belegt → ein **Trust-Checkpoint**
  (Passkey-PoP) **vor** der Hub-Liste.
- **Ehrliche Rahmung (falls belegt):** der Schritt ist ein **Sicherheits-Checkpoint** für die höher-privilegierte
  Remote-Operator-Aktion — **kein** Reibungs-Gate ohne Grund. Copy sagt *warum* (z. B. „Bestätige mit deinem Passkey,
  um remote auf Hubs zuzugreifen"), nicht nur *dass*.
- **NICHT finalisiert:** Screen-Details, Pflicht-vs-optional, Enroll-Flow, Recovery — **deferred** bis Auftraggeber-Entscheid.
  Die Spec **fixiert nur den Seam** (der Slot existiert, ist non-optimistisch, und blockt fail-closed bei
  fehlgeschlagener PoP). testTag-Platzhalter `remote.authStep.*` (provisorisch, friert später).
- **Nahtstelle:** knüpft an `HubTransport.sessionToken()` → Ticket-JWT (S-K); eine PoP würde das Ticket an einen
  Passkey binden. Backend/Threat-Model-Nahtstelle (§15).

---

## 6. Hub-Auswahl & „Auf Hub wechseln" (Remote)

Erweitert die Phase-1-`hubConnect`-Hub-Liste + Modus-Wahl.

- **Hub-Liste:** unverändert Phase-1 (`hubs()`, Presence advisory/H1, `lastSeen` relativ). Remote-Kontext ergänzt: eine
  Zeile ist **remote-erreichbar**, wenn die CP eine Relay-Verbindung vermitteln kann (Presence `online` ist **Hinweis**,
  nicht Garantie — H1; die Bodenwahrheit ist der Connect §7).
- **Modus-Wahl:** `mode.local | mode.remote` — **Remote jetzt AKTIV** (Phase-1-„kommt bald"/disabled entfällt für Remote-fähige
  Builds). Beide ehrlich: Lokal = „im selben Netz"; Remote = „über die Control Plane, E2E".
- **„Auf Hub X wechseln" = non-optimistisch** (Vorbild `ProjectViewModel.switchTo`): der aktive Remote-Hub flippt **erst
  nach** etablierter Session (§7), reject → bleib beim aktuellen Zustand + ehrlicher Fehler. **Nicht-destruktiver
  Kontext-Wechsel** (wie Projekt-Switch: „nichts wird gelöscht"). Ein bereits aktiver Remote-Hub ist **kein** Wechselziel.
- **Laufender-Zustand:** wie `runtimeState` je Projekt — der Hub kann `online/offline/lastSeen` (Registry) tragen; der
  **eigene** Verbindungszustand (§7) ist getrennt (H1: Registry-Presence ≠ meine Session).

---

## 7. Remote-Verbindungszustand (Relay)

Erweitert das Phase-1-`ConnectingView`-Idiom (`Attempting→Handshake→Connected→Failed(cause)`) um die Relay-/Noise-Schicht.

**Connect-Progression (neutral, nie grün/LIVE vorzeitig — erbt Phase-1-Idiom):**
| Zustand | Bedeutung | Ton |
|---|---|---|
| `relayDialing` | Verbindung zur Control Plane / Relay-Vermittlung | neutral `onSurfaceVariant` |
| `e2eHandshake` | Noise-Handshake Frontend ⟷ Hub (CP sieht nur Ciphertext) | neutral |
| `trustCheck` | TOFU-Prüfung des Hub-Schlüssels (§8) — Erstverbindung ⇒ Bestätigung | neutral (bzw. Trust-Prompt §8) |
| `connected` (LIVE) | Remote-Session etabliert | `●` + `primary` (erst bei echtem LIVE) |
| Fehler ↓ | | errorContainer + **typisierte Ursache** |
| `relay_unreachable` | CP/Relay nicht erreichbar | errorContainer |
| `hub_offline` | Hub bei der CP nicht präsent | errorContainer |
| `handshake_failed` | Noise-Handshake gescheitert | errorContainer |
| `trust_changed` | **Hub-Schlüssel ≠ Pin** — potenzielles MITM | **WARN/Block §8** (nicht bloß Fehler) |

**Globales Relay-Drop-Surface (H4 — die Lücke):** fällt das Relay während der Session, erscheint **ein** globaler,
workspace-scoped Zustand („Remote-Verbindung zu Hub X unterbrochen — verbinde neu…", neutral `onSurfaceVariant`, im
Kontext-Strip §9) **statt** N per-Agent-Chips. Reconnect nutzt `Reconnect.kt`-Backoff + Cursor-Resume (gapless). Solange
getrennt: **in-flight-Aktionen ehrlich ungewiss** (§10), keine vorgetäuschte Kontinuität.

**Latenz (advisory, H7):** optionaler neutraler RTT-Hinweis im Kontext-Strip (§9) — **Hinweis**, keine Garantie; hohe
Latenz **≠** getrennt; kein Alarm-Ton. (Offene Entscheidung Q3, §14 — ob überhaupt sichtbar.)

---

## 8. Trust-Affordances (TOFU + „E2E via Relay")

Die **zwei getrennten Trust-Wahrheiten** (H1) bekommen **zwei getrennte** Affordances.

### 8.1 Hub-Authentizität — TOFU-Pin (H2)
- **Erstverbindung** zu einem Hub: **Trust-Prompt** mit dem **Fingerprint** des Hub-Schlüssels + ehrlicher Copy: „Du
  verbindest dich zum ersten Mal mit Hub X. Prüfe den Fingerprint, wenn Sicherheit zählt (out-of-band), und bestätige,
  um ihn zu pinnen." Bestätigung **pinnt** den Schlüssel. Fingerprint als lesbare Gruppen (nicht roher Hash-Blob).
- **Spätere** Verbindungen: **still verifiziert** gegen den Pin; ein kleiner **neutraler** „Identität gepinnt"-Indikator
  im Kontext-Strip (§9) genügt (kein Prompt).
- **Schlüssel-Änderung** (`trust_changed`, §7): **harter WARN/Block** — „Die Identität von Hub X hat sich geändert. Das
  kann ein Angriff (MITM) oder eine legitime Neuinstallation sein. **Nicht** fortfahren, bis geklärt." **Nie** still
  akzeptiert; Fortfahren nur nach expliziter, gewarnter Re-Pin-Bestätigung. WARN-Amber (`EventVisuals`), **nicht**
  `tertiary`-Grün. (Level Block-vs-warn = Q2, §14.)
- **Platzierung:** Fingerprint-Zeile im `HubRow` (unter `localhost:${defaultPort}`); Trust-Prompt im `trustCheck`-Zustand;
  Pin-Indikator + Änderungs-Alarm im Kontext-Strip.

### 8.2 Transport-Vertraulichkeit — „E2E via Relay"-Indikator (H3)
- Ein **neutraler** Indikator im Kontext-Strip: „E2E-verschlüsselt via Relay" — Aussage: **die Control Plane sieht nur
  Ciphertext**. **Nicht** grün-als-Erfolg, **nicht** als „Hub vertrauenswürdig" lesbar (das ist §8.1). Form+Label+a11y.
- **Ehrlichkeits-Grenze:** der Indikator beschreibt **nur den Transport**. Er verschwindet/ändert sich **nie** so, dass er
  Hub-Vertrauen impliziert.

---

## 9. „Fern-Betrieb: Hub X"-Kontext-Banner (H6)

Persistente, **neutrale** Erinnerung, dass die Session remote läuft.

- **Platzierung:** als Geschwister der Rollen-Indikator-Zeile (`WorkspaceTags.ROLE_INDICATOR`) im `ProjectSwitcherBar` —
  identitäts-/kontext-Ton, **kein** Alarm. „● Fern-Betrieb: Hub X" (neutral `onSurfaceVariant` ●, INFO-Register).
- **Inhalt (gestaffelt, alle neutral):** Hub-Name · „E2E via Relay" (§8.2) · „Identität gepinnt" (§8.1) · optional Latenz
  (§7/Q3) · bei Drop → „Verbindung unterbrochen — verbinde neu…" (§7).
- **Presence ≠ connected** (H6): der Strip zeigt **meine** Remote-Session (Bodenwahrheit), **nicht** Registry-Presence;
  nie `tertiary`-Grün.
- **Lokal-Modus:** der Strip ist **absent** (kein „Fern-Betrieb" wenn lokal) — fail-closed, kein Phantom.

---

## 10. Voller Operator-Surface remote

**Reuse der bestehenden Surfaces**, adaptiert für Remote — **kein** neues Surface:

- **Agentenfenster** (`AgentWindow`: Transcript + `MessageComposer`), **Comm/Timeline** (`CommPanel`), **Agenten/Projekte
  konfigurieren** (`AgentManagement`, `ConnectorPicker`, `AclMatrix`, `Settings`, `ProjectSwitcher`), **Lifecycle**
  (start/stop/restart) — alle laufen **über das Relay** in denselben Hub.
- **Remote-Adaption = die Optimistic-vs-confirmed-Ehrlichkeit (H5):**
  - **Confirmed-Surfaces** (Agent-CRUD, Connector, Settings, Lifecycle, Hand-off, Projekt-/Hub-Wechsel): **unverändert
    sicher** — sie warten ohnehin auf den Server-Confirm; über Relay heißt das nur „etwas länger", nie „vorgetäuscht".
    Die bestehenden transienten Affordances („Startet…", „wird umgeschaltet…") + Watchdogs greifen.
  - **Composer (optimistisch):** „sendet…"-Zustand bleibt; **bei Relay-Drop** wird die pending-Nachricht **ehrlich als
    ungewiss/nicht-bestätigt** markiert (nicht still als gesendet angenommen), und beim Reconnect confirm-or-resend —
    **nie** doppelt-gesendet vorgetäuscht (Idempotenz über message-id, `Reconnect.kt`-Contract).
  - **ACL (optimistic-visuell + Echo + Watchdog):** über Relay ist der **Echo-Watchdog** genau richtig — bleibt das Echo
    aus (Drop), **revert** die Zelle auf `pending`/zurück (kein vorgetäuschtes Grant). Bestehendes Muster, remote-tauglich.
- **Disconnect-Verhalten:** globales Relay-Drop-Surface (§7) statt N Chips; in-flight ungewiss; nach Reconnect
  Cursor-Resume (gapless), Duplikate über id entschärft.

---

## 11. Maritim + Material 3 — Farb-/Ton-Disziplin

- **Neutral** (`onSurfaceVariant`/INFO `secondary`) für Kontext-Strip, Connect-Progression, „E2E"/„gepinnt"-Indikatoren,
  Latenz — **nie** `tertiary`-Grün (Brand, nie Status), **nie** grün-als-„sicher/verbunden".
- **WARN-Amber** (`EventVisuals` `severityColor/severityContainer(WARN)`, Glyph `▲`) **nur** für den **Trust-Änderungs-Alarm**
  (§8.1) — ein echtes Sicherheits-Signal.
- **errorContainer-Rot** nur für harte Connect-Fehler (§7), nicht für „langsam"/„remote".
- **Farbe nie allein** (1.4.1): jeder Zustand Form+Label+a11y. Dark/Light über `maritimeColorScheme`.

---

## 12. testTag-Kontrakt (provisorisch — friert nach Ratifikation + Auth-Entscheid)

Erweitert Area `hubConnect` (Connect-Flow) + neue Area `remote` (aktive Remote-Session-Chrome). Provisorisch:
```
remote.authStep.<slot>            (§5, OFFEN)      remote.trust.fingerprint        (§8.1)
hubConnect.mode.remote            (aktiviert)      remote.trust.pinPrompt          (§8.1)
remote.connect.relayDialing       (§7)             remote.trust.changedAlarm       (§8.1, WARN)
remote.connect.e2eHandshake       (§7)             remote.trust.e2eIndicator       (§8.2)
remote.connect.trustCheck         (§7)             remote.context.banner           (§9)
remote.connect.connected          (§7)             remote.context.hub              (§9)
remote.connect.error.<cause>      (§7)             remote.context.latency          (§7/§9, opt)
remote.relayDrop                  (§7/H4)          remote.context.reconnecting     (§7)
```
**Fail-closed-Anker:** `remote.context.banner` absent im Lokal-Modus; `remote.connect.connected` nie vor echtem LIVE;
`remote.trust.changedAlarm` bei Schlüssel-Änderung (nie still); `remote.trust.e2eIndicator` nie grün/nie als Hub-Vertrauen.

## 13. Copy (provisorisch, DE-Default + EN — Auswahl)

Key-Familie `remote_*` / `a11y_remote_*` (greenfield, Kollision bei Freeze zu verifizieren).
| Key | DE | EN |
|---|---|---|
| `remote_context_operating` | Fern-Betrieb: %1$s | Remote session: %1$s |
| `remote_e2e_indicator` | E2E-verschlüsselt via Relay | E2E-encrypted via relay |
| `remote_trust_pinned` | Identität gepinnt | Identity pinned |
| `remote_trust_first_title` | Erstverbindung mit %1$s | First connection to %1$s |
| `remote_trust_first_body` | Prüfe den Fingerprint (out-of-band), wenn Sicherheit zählt, und bestätige, um ihn zu pinnen. | Verify the fingerprint (out-of-band) if security matters, then confirm to pin it. |
| `remote_trust_changed_title` | Identität von %1$s hat sich geändert | %1$s's identity has changed |
| `remote_trust_changed_body` | Das kann ein Angriff (MITM) oder eine legitime Neuinstallation sein. Nicht fortfahren, bis geklärt. | This could be an attack (MITM) or a legitimate reinstall. Don't proceed until you've confirmed. |
| `remote_connect_relay` | Verbinde über die Control Plane… | Connecting via the control plane… |
| `remote_connect_e2e` | Sichere Verbindung (E2E) wird aufgebaut… | Establishing a secure (E2E) connection… |
| `remote_connect_trustcheck` | Hub-Identität wird geprüft… | Verifying hub identity… |
| `remote_relay_dropped` | Remote-Verbindung zu %1$s unterbrochen — verbinde neu… | Remote connection to %1$s lost — reconnecting… |
| `remote_error_relay` | Control Plane / Relay nicht erreichbar. | Control plane / relay not reachable. |
| `a11y_remote_context` | Fern-Betrieb über Relay: Hub %1$s, E2E-verschlüsselt. | Remote session via relay: hub %1$s, E2E-encrypted. |
| `a11y_remote_trust_changed` | Warnung: Hub-Identität geändert — mögliches MITM, nicht fortfahren. | Warning: hub identity changed — possible MITM, do not proceed. |

*(Auth-Schritt-Copy §5 bewusst ausgelassen — nicht finalisiert. Latenz-Copy §7/Q3 bei Entscheid.)*

## 14. Offene Entscheidungen

1. **Q1 — Auth-Schritt (RR2-B, Auftraggeber):** Passkey/WebAuthn-PoP verpflichtend / optional / gar nicht; Enroll- +
   Recovery-Flow. **Spec hält nur den Seam** (§5), finalisiert nicht. → **PO/Auftraggeber**.
2. **Q2 — Trust-Änderung: harter Block vs. gewarnter Re-Pin?** Empfehlung: **Block mit expliziter, gewarnter
   Re-Pin-Bestätigung** (kein Ein-Klick-Weiter). Sicherheits-Abwägung → Auftraggeber/Threat-Model.
3. **Q3 — Latenz sichtbar?** RTT-Hinweis im Kontext-Strip anzeigen (advisory) oder weglassen? Empfehlung: dezent/optional,
   nur wenn hoch — nie Alarm.
4. **Q4 — Fingerprint-Verifikations-Hilfe:** nur Anzeige, oder ein Vergleichs-Wort/QR (out-of-band-Kanal)? Empfehlung:
   lesbare Fingerprint-Gruppen jetzt; QR/Vergleich später.
5. **Q5 — „Auf Hub wechseln" bei aktiver Remote-Session:** sauberer Teardown der alten Session vor der neuen (non-optimistisch)
   — Bestätigung des Übergangs-UX (analog Projekt-Switch „nichts wird gelöscht").

## 15. Nahtstellen zu Backend/Dev (über den PO)

- **S-1 — `RemoteHubTransport` belegen:** Noise-E2E über CP-Relay (heute fail-loud Stub). Liefert den Relay-Verbindungs-
  Zustand (`relayDialing→e2eHandshake→trustCheck→connected` + typisierte Fehler) als Feed — **Client rät nicht**.
- **S-2 — TOFU-Pin-Store + Handshake-Outcome:** Hub-Public-Key-Fingerprint + `pinned/first-use/changed`-Signal; Pin-Store
  im sicheren Client-Storage (`SecureSessionStore`-Linie, CYP-413). Client zeigt, Backend/Krypto entscheidet „changed".
- **S-3 — Globaler Relay-Verbindungs-Zustand:** ein workspace-scoped „Remote-Link up/down/reconnecting"-Signal (H4) —
  neu ggü. den per-Stream-`ConnectionStatus`. Speist §7/§9.
- **S-4 — Auth-Schritt / Ticket-JWT (S-K, RR2-B):** `HubTransport.sessionToken()` → CP-issued hub-scoped Ticket-JWT;
  optionale Passkey-PoP-Bindung. **Nicht finalisiert** — Threat-Model-Nahtstelle.
- **S-5 — Latenz-Quelle (opt, Q3):** RTT aus dem Relay-Transport, falls sichtbar gemacht.
- **Drift-Hinweis:** neue `remote_*`-Keys + `remote.*`/`hubConnect.*`-Tags landen mit Devs Slice → Re-Sync Tester (CYP-7).

## 16. Acceptance-Teeth (für spätere §-QA)

1. **Zwei Trust-Wahrheiten (H1/H3):** „E2E via Relay" beschreibt **nur** Transport (CP=Ciphertext), nie als
   Hub-Vertrauen lesbar; getrennt vom TOFU-Pin.
2. **TOFU ehrlich (H2):** Erstverbindung = offen deklariert + Fingerprint + Pin-Bestätigung; spätere = still verifiziert;
   **Schlüssel-Änderung = harter WARN/Block, nie still akzeptiert**.
3. **Globaler Relay-Drop (H4):** **ein** globales Surface, nicht N per-Agent-Chips; in-flight-Aktionen ehrlich ungewiss,
   nie still als erledigt.
4. **Optimistic-vs-confirmed über Relay (H5):** confirmed-Surfaces warten (sicher); Composer pending→ungewiss-bei-Drop→
   confirm/resend ohne Doppelsenden; ACL echo+watchdog revertet bei fehlendem Echo.
5. **Kontext-Banner (H6):** persistent, neutral/INFO, „Fern-Betrieb: Hub X", nie `tertiary`-Grün, absent im Lokal-Modus;
   zeigt **meine** Session nicht Registry-Presence.
6. **Latenz advisory (H7):** wenn sichtbar, neutral, kein Alarm; hohe Latenz ≠ getrennt.
7. **Auth-Schritt-Seam (H8):** Slot existiert, non-optimistisch, fail-closed bei PoP-Fehler; **Details nicht
   vorweggenommen** (kein finalisierter Screen bis Auftraggeber-Entscheid).
8. **Reuse:** Login=`AuthGate`, Hub-Liste=`ControlPlaneClient.hubs()`, Wechsel=`switchTo`-Muster, Surfaces=bestehend,
   Töne=`TonedHint`/`EventVisuals` — keine divergenten Einmal-Teile.
9. **Presence ≠ connected & Farbe nie allein (1.4.1):** durchgängig; Dark/Light über `maritimeColorScheme`.

---

*Design-Aufschlag, nichts gebaut. **Auth-Schritt (§5, RR2-B) bewusst nicht finalisiert** — Seam gehalten. Companion-Files
(`remote-operator-keys/-tags/-tokens`) werden nach Ratifikation **+ Auth-Schritt-Entscheid** eingefroren; vorher würden
Keys/Tags driften. Naht-Konsistenz (Keys/Tags/Auth-Flow) über den PO; Backend-Nahtstellen §15 relay über den PO.*
