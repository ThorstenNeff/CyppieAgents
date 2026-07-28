# CYP-827 (A5) — Connect-Progression-Status-Chrome: Zone-2 In-Flight States

> UX/UI-Placement/Visual-Spec. Für den **A5-Real-Socket-Flow** (Dev5 baut ihn gegen Stub) — der visuelle Spec der
> **Zone-2-in-flight-Progression** (dialing → handshake → trust-check → connected/reconnecting — **5 web-States**; s. Platform-Parität §0b), damit's
> beim **CYP-827-Mount** ready ist. Parität zu Compose `RemoteConnectingView` (`HubConnectSelection.kt`). Ergänzt
> **CYP-823** (Failure-View-Host / die terminal-negativen Ausgänge) + **CYP-838** (STALE-Token). Ich designe/spec/verifiziere.

## 0. Zoning (bestätigt, load-bearing)
Die Progression ist **Zone-2-*in-flight*** — **transient, per-Connect-Versuch** — und löst auf zu **CONNECTED** (positiv)
**oder** der **Failure-Region** (`failed(cause)`, terminal-negativ, CYP-823). Sie gehört **NIE** in die always-visible
**Zone-1**-Status-Chrome (Trust-Badge/Tier-Badge), sonst persistiert ein transienter Schritt als Dauer-Status. Der
„Progression vs terminal-failure"-Split ist **innerhalb Zone 2**, orthogonal zur Zone1(Status)/Zone2(Connect-Flow)-Trennung.
Zone-1-Badges **coexistieren** mit jedem Progression-State (der Hub trägt sein Trust-Badge, während er verbindet).

## 0b. ★ Platform-Parität — 6 States nativ / **5 States web** (`authenticating` ist Compose-only)
**Backend2 (Protokoll-Owner) am Objekt geruled + von mir am frozen Contract verifiziert:** die frozen web-ts
`RemoteConnState` (`state/remoteConnState.ts:85`) hat **KEINE `authenticating`-Phase** — `idle · dialing · handshake ·
trust-check · connected · reconnecting · failed · lost`. Also **5 Progression-States web** (dialing/handshake/trust-check/
connected/reconnecting) vs **6 nativ**. **Kein Bug/Drift** — eine **Auth-Architektur-Differenz:**
- **native (Compose):** zwei **getrennte** Beweise über den Tunnel — Hub-Identität (`trust-check`) **UND** Operator-PoP
  (distinct `AUTHENTICATING` = WebAuthn) → ein eigener post-trust-check-Schritt.
- **web-ts:** Operator-Autorität sitzt in der **Transport-Schicht** — der **httpOnly Session-Cookie** authentisiert den
  WSS-Handshake (CYP-454); der Operator ist **schon auth'd bei Socket-Open** → **kein** distinkter post-trust-check-Auth-
  Schritt, also **keine** `authenticating`-Phase.
- **Folge:** die nativen `AUTHENTICATING`-Failures (`DeviceNotEnrolled` / `UvFailed` / `EnrollTimedOut`) sind **native-only**
  und tauchen in der web-ts-Failure-Region **nicht** auf. Meine ursprüngliche 6-State-Liste spiegelte Compose; der
  **authoritative web-ts-Spec = 5 States** (matcht Dev5s M5-Build). Die §1-Tabelle unten ist entsprechend die **web-ts**-Sicht.

## 1. Die Progression-States (web-ts: 5 · Copy wortgleich zu Compose)
Der Connect-Flow des **aktiven** Hubs schaltet auf den Conn-State. Copy 1:1 aus Compose (`values/strings.xml`,
`remote_connect_*`); Tags Compose-Parität (`RemoteConnectTags`); **nicht** per-`hubId` (eine Verbindung zur Zeit).

| State | Copy (DE, wortgleich) | Tag | Visual | Tone | a11y-live |
|---|---|---|---|---|---|
| dialing | Relay wird gewählt … | `remote.connect.relayDialing` | neutraler Spinner | neutral | polite/status |
| handshake | E2E-Handshake … | `remote.connect.e2eHandshake` | neutraler Spinner | neutral | polite/status |
| **trust-check** | Hub-Vertrauen wird geprüft … | `remote.connect.trustCheck` | Spinner **+ provisorisch-Disclosure** (§2.1) | neutral | polite/status |
| **reconnecting** | Verbindung unterbrochen — verbinde neu … | `remote.relayDrop` | neutraler Spinner (§2.2) | **neutral, nie Alarm** | polite/status |
| **connected** | Verbunden | `remote.connect.connected` | **`●` + primary** (LIVE-Idiom, §2.3) | LIVE | polite/status |

- **In-Progress = neutrale Spinner + Copy.** Kein Alarm-Ton in irgendeinem Progression-State (Alarm/terminal gehört in die
  Failure-Region, CYP-823). **Colour-never-sole:** Spinner-Form + Copy-Wort tragen die Bedeutung.
- **CONNECTED** trägt zusätzlich die Vorwärts-Aktion **„Loslegen"** (`remote.connect.toWorkspace`) + optional End-Session —
  kein Dead-End (Compose CYP-523-Parität). Und hier mountet die **Zone-1** Tier-Badge (CYP-823 §2b) + Trust-Badge bleibt.

## 2. Die honesty-kritischen States (approved)
### 2.1 trust-check = **provisorisch, NIE „verifiziert"**
Solange **echtes Pinnen** nicht passiert ist, darf der trust-check **nicht** „verified/vertraut" über-sagen. Sichtbare,
**always-visible** Disclosure (nicht tap-to-reveal): **„Vertrauensprüfung vorläufig — echtes Pinnen folgt."**
(`remote.connect.trustProvisional`, Compose CYP-475 §-QA① wortgleich). Register: neutral/`labelSmall`, kein Alarm.
- **Zwei Sub-Modi (seam-gated, Compose-Parität):** (a) **provisorischer Spinner + Disclosure** (INERT, bis die
  Pinning-Naht live ist) · (b) sobald echtes Pinnen wired → die **OOB-Fingerprint-Confirm**-Fläche (echter Fingerprint,
  mandatory approve/reject) — dann **retired** die provisorisch-Disclosure ([[forecast-vs-observed-disclosure]]: Beobachtung
  nur wenn wirklich beobachtet). Für A5 gegen Stub = Modus (a); (b) landet mit der Pinning-Naht.
- **★ Honesty-Kern:** dies ist die höchste Over-say-Gefahr der Progression — „Hub-Vertrauen wird geprüft" darf nie als
  „geprüft ✓/vertraut" lesen, bevor es das ist. Trust-check ist **in-flight**, nicht das Trust-**Verdikt** (das ist
  axis-a HubTrustState, Zone-1, separat).

### 2.2 reconnecting = **honestly-uncertain, retryable, NIE terminal/Alarm**
„Verbindung unterbrochen — verbinde neu …" ist **eine neutrale** Relay-Drop/Reconnect-Fläche (Compose H4). **Nie
Alarm-rot**, **nie fälschlich „connected"** — in-flight ist der Zustand ehrlich **unsicher**. **★ Distinkt von
terminal-`lost`/`failed`** (CYP-823): reconnecting = **retryable Transient** (bleibt in Zone-2-in-flight, polite), **kein**
Failure-Arm. Ein reconnectbares Drop darf **nicht** in die terminale Failure-Region routen (sonst liest ein retrybares
Drop als terminaler Stopp — [[terminal-block-tone-is-structural]] / die CYP-823-`lost`-Reconciliation).

### 2.3 CONNECTED = **non-optimistisch, „● live" NUR bei echtem CONNECTED**
Der **`●` + `primary`** LIVE-Marker wird **ausschließlich** im echten CONNECTED-State erreicht — **nie** vorher
(dialing/handshake/trust-check zeigen nie den Live-Marker). Kein optimistischer Vorgriff.
- **★ Abgrenzung (nicht konflatieren):** dieses `●`+`primary` ist das **LIVE-Verbindungs-Idiom** (wie
  `RemoteSecurityTierBadge` native = „LIVE ●+primary", CYP-676) — es ist **NICHT** die axis-a-Trust-Affirmation (die bleibt
  **neutral**, CYP-803: `trusted`≠primary). Verbindungs-**Liveness** (primary ok) ≠ Trust-**Verdikt** (neutral). Zwei Achsen.

## 3. a11y-Register (= die CYP-825-Naht, nicht inflationär)
- **Alle Progression-States: `role=status` `aria-live=polite`.** In-flight-Schritte werden **ruhig** angesagt.
- **Assertive/`role=alert` bleibt reserviert** für: die **terminale Failure-Region** (`failed(cause)`, z. B.
  IssuerNotTrusted — CYP-805) **und** den **aktiv-Hub-Wechsel in rejected/stale** (CYP-755 §1) **und** den **terminalen
  Comm-Revoke** (CYP-825). Progression **nie** assertiv (sonst Announce-Storm auf jedem Verbindungs-Schritt →
  [[over-alarm-is-also-dishonest]]: wenn jeder Schritt unterbricht, unterbricht der echte terminale Ausgang nicht mehr).
- **colour-never-sole:** jeder State trägt Spinner-Form + Copy-Wort; Tone/Farbe ist Verstärkung.

## 4. Cross-Surface-Parität
- **Copy wortgleich** aus Compose (`remote_connect_relay_dialing/e2e_handshake/trust_check/trust_provisional/
  relay_dropped/connected`) — `authenticating` ist Compose-only (§0b) — keine divergente Zweitformulierung.
- **Tags Compose-Parität** (`remote.connect.*`), nicht per-`hubId`.
- **Struktur-Parität:** Compose `RemoteConnectingView` (conn-switch) — **dieselbe Struktur** (web-States = Compose-Subset ohne `authenticating`, §0b), dieselbe Auflösung zu CONNECTED | Failure-Region.

## 5. Teeth (Tester2, beim Mount)
1. **`●`+primary NUR bei CONNECTED** — kein Progression-State < CONNECTED zeigt den LIVE-Marker. *(Mutation: `●`+primary in
   dialing/handshake/trust-check → RED = optimistischer Vorgriff.)*
2. **trust-check-Disclosure präsent + „vorläufig", nie „verifiziert"** — der provisorisch-Text rendert im trust-check-State
   (Modus a); kein „geprüft ✓/vertraut" vor echtem Pinnen. *(Mutation: Disclosure fehlt / Copy sagt „verifiziert" → RED = over-say.)*
3. **reconnecting neutral + retryable, NICHT terminal** — reconnecting rendert neutral (kein Alarm-rot), bleibt in-flight,
   ist **kein** Failure-Arm. *(Mutation: reconnecting error-rot ODER in die terminale Failure-Region geroutet → RED.)*
4. **Progression polite, Failure/Revoke assertive** — Progression-States `aria-live=polite`; assertive bleibt bei
   terminal-failure/aktiv-Hub/Revoke. *(Mutation: Progression assertiv → RED = Announce-Storm.)*
5. **Zone-Trennung** — Progression rendert in Zone-2-in-flight (Connect-Card), **nie** in der always-visible Zone-1-Chrome;
   Zone-1-Badges coexistieren. *(Mutation: ein Progression-State in der always-visible Status-Chrome → RED = persistiert.)*
6. **Liveness ≠ Trust** — das CONNECTED-`●`+primary ist die Liveness-Achse, **nicht** axis-a-Trust (die bleibt neutral).

## 6. Reuse-Ledger
- **Struktur/Copy:** Compose `RemoteConnectingView` + `remote_connect_*`-Strings + `RemoteConnectTags` (`HubConnectSelection.kt`).
- **Provisorisch-Disclosure:** Compose CYP-475 (`remote_connect_trust_provisional`), seam-gated retire (CYP-505-Parität).
- **LIVE-Idiom `●`+primary:** `RemoteSecurityTierBadge` native (CYP-676) — Liveness, nicht Trust-Affirmation (CYP-803).
- **a11y-Naht:** CYP-825 (Revoke=assertive) + CYP-755 §1 (aktiv-Hub-Wechsel=assertive) + CYP-805 (terminal=assertive) —
  Progression=polite ergänzt dieselbe Naht.
- **Zonen:** CYP-823 (Zone1/Zone2, Failure-Region); **kein neuer Leaf/Copy** — reines Progression-Placement + honesty-Register.
