# Remote-Modus Operator-UX — Design-Spec (CYP-429, Epic CYP-427 „Phase 2: Remote-Modus")

> Status: **Spec-Closure — Q1–Q5 ratifiziert 2026-07-11** · docs-only, **kein Bau vor Ratifikation** · Owner: UX/UI
> Baut auf der **gebauten Phase-1-`hubConnect`-UX** (CYP-419, meine Spec CYP-395) auf — jetzt für **Remote**.
> **✅ Auth-Schritt = nativer Passkey/WebAuthn-PoP (Q1/RR2-B, §5); Ziel = Desktop-Native (Team-1 zuerst).**
> Eingefrorene Companion-Files (Haus-Konvention): `remote-operator-keys.md` · `-tags.md` · `-tokens.json`.
> Naht-Konsistenz (Keys/Tags/Auth-Flow, Passkey → Backend `OperatorAssertionVerifier`) über den PO.
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
- **H8 — Auth-Schritt = nativer Passkey/WebAuthn-PoP (Q1/RR2-B ratifiziert).** Zwischen zentralem Login und Hub-Liste
  ein **verpflichtender** PoP-Schritt (Härtung gegen CP-Operator-Seizure); der Passkey liegt **nativ, außerhalb der
  CP-Origin** (Desktop-Native), sodass ein kompromittiertes zentrales Login allein **nicht** genügt. Non-optimistisch,
  **fail-closed** bei PoP-Fehler (§5).

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
Passkey/WebAuthn-PoP  ← §5 (Q1/RR2-B ratifiziert): nativer Device-Key, non-optimistisch, fail-closed
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

## 5. Auth-Schritt — Passkey/WebAuthn-PoP (RR2-B, ratifiziert 2026-07-11)

**Q1 ratifiziert: JA.** Zwischen zentralem Login und Hub-Liste sitzt ein **verpflichtender** Passkey/WebAuthn-**Proof-of-Possession**-Schritt
(Härtung gegen **CP-Operator-Seizure**). Der Slot ist **nicht mehr offen** — er ist ein konkreter Screen.

- **Ziel-Frontend = Desktop-Native (Team-1 zuerst; Web-UI zieht Team-2 nach).** Der Passkey/Device-Key wird **nativ**
  gehalten — **außerhalb der CP-Origin** — sodass ein kompromittiertes zentrales Login **nicht** genügt, um remote auf
  Hubs zuzugreifen. Das trägt den **TOFU-Pin (§8.1)** und diesen PoP-Schritt voll.
- **Screen:** nach `AuthGate → Verified(OPERATOR)` erscheint der **PoP-Checkpoint**: „Bestätige mit deinem Passkey, um
  remote auf deine Hubs zuzugreifen." Ehrliche Rahmung — sagt **warum** (Remote-Operator ist höher-privilegiert), nicht
  nur *dass*. Native Passkey-Aufforderung (OS-Prompt); Erfolg → Hub-Liste.
- **Non-optimistisch, fail-closed:** die Hub-Liste erscheint **erst nach** erfolgreichem PoP; **PoP-Fehler/Abbruch →
  blockiert** (kein Durchreichen zur Hub-Liste), ehrlicher Fehler + Retry. Kein optimistisches Vorblenden.
- **Enroll / Recovery (Erst-Setup):** hat der Operator noch keinen Passkey registriert, führt ein **Enroll-Schritt**
  (native Passkey-Registrierung) davor. Recovery-Pfad (verlorener Device-Key) = **Sicherheits-sensibel** → Detail-Frage
  an das Threat-Model/Auftraggeber (nicht im Aufschlag ausgestaltet, aber der Pfad ist markiert, damit kein
  Aussperr-Sackgasse entsteht).
- **testTags:** `remote.authStep.popPrompt` · `remote.authStep.enroll` · `remote.authStep.error` (provisorisch bis Freeze).
- **Nahtstelle S-4:** knüpft an `HubTransport.sessionToken()` → **CP-issued, hub-scoped Ticket-JWT** (S-K); die PoP
  **bindet** das Ticket an den **nativen** Passkey (Besitznachweis). Backend/Threat-Model-Nahtstelle (§15).

---

## 6. Hub-Auswahl & „Auf Hub wechseln" (Remote)

Erweitert die Phase-1-`hubConnect`-Hub-Liste + Modus-Wahl.

- **Hub-Liste:** unverändert Phase-1 (`hubs()`, Presence advisory/H1, `lastSeen` relativ). Remote-Kontext ergänzt: eine
  Zeile ist **remote-erreichbar**, wenn die CP eine Relay-Verbindung vermitteln kann (Presence `online` ist **Hinweis**,
  nicht Garantie — H1; die Bodenwahrheit ist der Connect §7).
- **Modus-Wahl:** `mode.local | mode.remote` — **Remote jetzt AKTIV** (Phase-1-„kommt bald"/disabled entfällt für Remote-fähige
  Builds). Beide ehrlich: Lokal = „im selben Netz"; Remote = „über die Control Plane, E2E".
- **„Auf Hub X wechseln" = non-optimistisch + expliziter, sauberer Teardown (Q5 ratifiziert), EIN aktiver Hub.** Der
  Wechsel **reißt die aktuelle Noise-Session vollständig ab** und **räumt pending/in-flight-State** — **nichts wird über
  Hubs getragen** (kein stiller Zwei-Hub-Multiplex im MVP). Kurze Transition „**Trenne von Hub X … verbinde mit Hub Y**",
  dann **TOFU-Check für den neuen Hub** (§8.1). Der aktive Remote-Hub flippt **erst nach** etablierter neuer Session (§7),
  reject → bleib beim alten Zustand + ehrlicher Fehler. **In-flight-Aktionen am alten Hub zum Wechsel-Zeitpunkt = ehrlich
  ungewiss** (nie still als erledigt). Ein bereits aktiver Remote-Hub ist **kein** Wechselziel.
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

**Latenz (advisory, H7, Q3 ratifiziert = JA/subtil):** ein **kleiner neutraler** Latenz-Hinweis **im Kontext-Banner**
(§9) — **Hinweis**, keine Garantie; **kein** Dauer-Zahlenflackern (gedämpft/gerundet, nicht sekündlich springend). Hohe
Latenz **≠** getrennt; **kein Alarm**. **Nur bei echter Degradation** eskaliert der Banner in einen sichtbaren Zustand
**„Verbindung langsam/instabil"** (neutral `onSurfaceVariant`, kein Rot/Amber). Ehrlich: Latenz **nicht verstecken**, aber
**nicht alarmieren**.

---

## 8. Trust-Affordances (TOFU + „E2E via Relay")

Die **zwei getrennten Trust-Wahrheiten** (H1) bekommen **zwei getrennte** Affordances.

### 8.1 Hub-Authentizität — TOFU-Pin (H2)
- **Erstverbindung** zu einem Hub: **Trust-Prompt** + ehrliche Copy: „Du verbindest dich zum ersten Mal mit Hub X.
  Bestätige die Identität **über einen anderen Kanal**, wenn Sicherheit zählt, und pinne sie." Bestätigung **pinnt** den
  Schlüssel.
- **Fingerprint-Hilfe (Q4 ratifiziert = mehrschichtig):**
  - **Primär: menschen-vergleichbare Wort-/Emoji-Sequenz** (PGP-Wordlist-Stil) — vorlesbar/vergleichbar, macht den
    Out-of-Band-Abgleich **fehlerarm** (weniger übersprungene Verifikationen). Der Haupt-Abgleich-Pfad.
  - **Sekundär: volle Hex-Fingerprint** (kopierbar, lesbare Gruppen — kein roher Blob) **+ QR** für den starken Scan-Pfad.
  - **Ehrlich als TOFU gelabelt:** „über einen anderen Kanal bestätigen" — nie als „automatisch sicher" dargestellt.
- **Spätere** Verbindungen: **still verifiziert** gegen den Pin; ein kleiner **neutraler** „Identität gepinnt"-Indikator
  im Kontext-Strip (§9) genügt (kein Prompt).
- **Schlüssel-Änderung** (`trust_changed`, §7): **harter Block** (Q2 vorgezeichnet, Threat-Model RR6/RR7) — „Die
  Identität von Hub X hat sich geändert. Das kann ein Angriff (MITM) oder eine legitime Neuinstallation sein. **Nicht**
  fortfahren, bis geklärt." **Nie** still akzeptiert; Fortfahren **nur** nach **explizitem Out-of-Band-Re-Pin** (der neue
  Fingerprint muss über einen **anderen Kanal** bestätigt werden — kein Ein-Klick-Weiter). WARN-Amber (`EventVisuals`),
  **nicht** `tertiary`-Grün. (Formales Q2-Ruling folgt, Richtung bestätigt.)
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
- **Inhalt (gestaffelt, alle neutral):** Hub-Name · „E2E via Relay" (§8.2) · „Identität gepinnt" (§8.1) · **subtiler
  Latenz-Hinweis** (§7/Q3, gedämpft, kein Zahlenflackern) · bei echter Degradation → **„Verbindung langsam/instabil"**
  (neutral, kein Alarm) · bei Drop → „Verbindung unterbrochen — verbinde neu…" (§7).
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

## 12. testTag-Kontrakt (Übersicht — maßgeblich: `remote-operator-tags.md`)

Neue Area `remote` (aktive Remote-Session-Chrome) + Aktivierung `hubConnect.mode.remote`. Diese Übersicht spiegelt den
**eingefrorenen** `remote-operator-tags.md`:
```
Auth (§5):     remote.authStep.popPrompt · .enroll · .error
Connect (§7):  remote.connect.{relayDialing,e2eHandshake,trustCheck,connected} · .error.<cause> · remote.relayDrop
Trust (§8):    remote.trust.{fingerprint,wordlist,hex,qr} · .pinPrompt · .changedAlarm(WARN) · .e2eIndicator
Switch (§6):   remote.switch.transition
Kontext (§9):  remote.context.{banner,hub,latency,degraded,reconnecting}
hubConnect:    hubConnect.mode.remote  (Phase-1 disabled → Remote aktiviert)
```
**Fail-closed-Anker:** `remote.context.banner` absent im Lokal-Modus; `remote.connect.connected` nie vor echtem LIVE;
`remote.trust.changedAlarm` bei Schlüssel-Änderung (nie still); `remote.trust.e2eIndicator` nie grün/nie als Hub-Vertrauen.

## 13. Copy (Übersicht — maßgeblich: `remote-operator-keys.md`)

Key-Familie `remote_*` / `a11y_remote_*` (greenfield, 0 Kollision verifiziert). Diese Tabelle spiegelt den
**eingefrorenen** `remote-operator-keys.md` (Auszug der Kern-Copy):
| Key | DE | EN |
|---|---|---|
| `remote_authstep_title` | Bestätige mit deinem Passkey | Confirm with your passkey |
| `remote_authstep_body` | Bestätige mit deinem Passkey, um remote auf deine Hubs zuzugreifen. | Confirm with your passkey to access your hubs remotely. |
| `remote_authstep_error` | Passkey-Bestätigung fehlgeschlagen. Erneut versuchen. | Passkey confirmation failed. Try again. |
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
| `remote_conn_degraded` | Verbindung langsam/instabil | Connection slow/unstable |
| `remote_trust_wordlist_label` | Vergleichs-Wörter | Comparison words |
| `remote_trust_hex_label` | Fingerprint (Hex) | Fingerprint (hex) |
| `remote_trust_qr_label` | QR scannen | Scan QR |
| `remote_trust_oob` | Über einen anderen Kanal bestätigen | Confirm via another channel |
| `remote_switch_transition` | Trenne von %1$s … verbinde mit %2$s | Disconnecting from %1$s … connecting to %2$s |
| `remote_relay_dropped` | Remote-Verbindung zu %1$s unterbrochen — verbinde neu… | Remote connection to %1$s lost — reconnecting… |
| `remote_error_relay` | Control Plane / Relay nicht erreichbar. | Control plane / relay not reachable. |
| `a11y_remote_context` | Fern-Betrieb über Relay: Hub %1$s, E2E-verschlüsselt. | Remote session via relay: hub %1$s, E2E-encrypted. |
| `a11y_remote_trust_changed` | Warnung: Hub-Identität geändert — mögliches MITM, nicht fortfahren. | Warning: hub identity changed — possible MITM, do not proceed. |

*(Auth-Schritt-Copy §5 jetzt drin — Q1 ratifiziert. Latenz-Copy §7/Q3 bei Entscheid.)*

## 14. Ratifizierte Entscheidungen (Q1–Q5, PO 2026-07-11) → Spec-Closure

1. **Q1 — Auth-Schritt = verpflichtender nativer Passkey/WebAuthn-PoP** (RR2-B). Desktop-Native (Team-1), Device-Key
   nativ/außerhalb CP-Origin; non-optimistisch, fail-closed; Enroll davor; Recovery = Threat-Model-Detail (§5).
2. **Q2 — Trust-Änderung = harter Block + expliziter Out-of-Band-Re-Pin, nie still** (Threat-Model RR6/RR7; §8.1).
3. **Q3 — Latenz sichtbar = JA, advisory/subtil** im Kontext-Banner (kein Zahlenflackern); Eskalation auf sichtbar
   „langsam/instabil" **nur bei echter Degradation**, nie Alarm (§7/§9).
4. **Q4 — Fingerprint-Hilfe = mehrschichtig:** primär menschen-vergleichbare Wort-/Emoji-Sequenz (PGP-Wordlist-Stil),
   sekundär volle Hex (kopierbar) + QR; ehrlich als TOFU/OOB gelabelt (§8.1).
5. **Q5 — Hub-Wechsel = expliziter, sauberer Teardown, EIN aktiver Hub:** volle Noise-Session-Teardown + pending/in-flight
   räumen (nichts über Hubs getragen), Transition „Trenne von X … verbinde mit Y", TOFU-Check für neuen Hub, in-flight am
   alten Hub ehrlich ungewiss, kein Zwei-Hub-Multiplex im MVP (§6).

## 15. Nahtstellen zu Backend/Dev (über den PO)

- **S-1 — `RemoteHubTransport` belegen:** Noise-E2E über CP-Relay (heute fail-loud Stub). Liefert den Relay-Verbindungs-
  Zustand (`relayDialing→e2eHandshake→trustCheck→connected` + typisierte Fehler) als Feed — **Client rät nicht**.
- **S-2 — TOFU-Pin-Store + Handshake-Outcome:** Hub-Public-Key-Fingerprint + `pinned/first-use/changed`-Signal; Pin-Store
  im sicheren Client-Storage (`SecureSessionStore`-Linie, CYP-413). Client zeigt, Backend/Krypto entscheidet „changed".
- **S-3 — Globaler Relay-Verbindungs-Zustand:** ein workspace-scoped „Remote-Link up/down/reconnecting"-Signal (H4) —
  neu ggü. den per-Stream-`ConnectionStatus`. Speist §7/§9.
- **S-4 — Auth-Schritt / Ticket-JWT (S-K, RR2-B ratifiziert):** `HubTransport.sessionToken()` → CP-issued hub-scoped
  Ticket-JWT, **gebunden an einen nativen Passkey/WebAuthn-PoP** (Besitznachweis, außerhalb CP-Origin); Backend-Verifikation
  über **`OperatorAssertionVerifier`** (PO-Nahtstelle). Enroll- + Recovery-Pfad = Threat-Model-Detail.
- **S-6 — Fingerprint-Darstellung (Q4):** Backend/Krypto liefert den Hub-Schlüssel-Fingerprint; Client leitet daraus die
  **Wort-/Emoji-Sequenz** (deterministische Wordlist-Abbildung) + Hex + QR ab. Latenz-Quelle (Q3) = RTT aus dem Relay-Transport.
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
7. **Auth-Schritt (H8, Q1 ratifiziert):** verpflichtender nativer Passkey/WebAuthn-PoP **vor** der Hub-Liste;
   non-optimistisch, **fail-closed** bei PoP-Fehler (kein Durchreichen); Device-Key nativ/außerhalb CP-Origin.
8. **Reuse:** Login=`AuthGate`, Hub-Liste=`ControlPlaneClient.hubs()`, Wechsel=`switchTo`-Muster, Surfaces=bestehend,
   Töne=`TonedHint`/`EventVisuals` — keine divergenten Einmal-Teile.
9. **Presence ≠ connected & Farbe nie allein (1.4.1):** durchgängig; Dark/Light über `maritimeColorScheme`.

---

*Spec-Closure (Q1–Q5 ratifiziert 2026-07-11), nichts gebaut. Die eingefrorenen Companion-Files
(`remote-operator-keys.md` / `-tags.md` / `-tokens.json`) sind die UI-Vorlage; §12/§13 sind Übersicht — maßgeblich sind
die Companion-Files. Naht-Konsistenz (Keys/Tags/Auth-Flow, Passkey → Backend `OperatorAssertionVerifier`) über den PO;
Backend-Nahtstellen §15 relay über den PO.*
