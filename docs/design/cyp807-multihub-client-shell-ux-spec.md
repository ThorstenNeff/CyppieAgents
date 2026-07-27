# CYP-807 (Option A, PL-Epic) — Multi-Hub-Client-Shell UX · Teil 1: Switcher / Hub-Liste

> UX/UI-Lead-Spec, **stub-first, gegen FROZEN Contracts** (baubar BEVOR CYP-832 ratifiziert). Nur die
> **Föderations-Nähte (real-dial)** bleiben Stub. Dieser Teil = **die Switcher/Hub-Listen-UX** (Deliverable 1);
> Deliverables 2–4 (N-Connection-Status, Cause-Anzeige, Zonen-Trennung) ziehen **CYP-827** (Progression-Chrome) +
> **CYP-823** (Failure-View-Host/Zonen) — beide **am frozen `remoteConnState` validiert** (s. §0). Ich designe/spec.

## 0. Frozen-Surface (am Objekt verifiziert) + Validierung meiner Vorspecs
| Frozen | Ort | Für |
|---|---|---|
| **HubDescriptor** (CYP-804) | `types/generated/contract.ts:638` | Hub-Liste (§1) |
| **RemoteConnState** (CYP-822/840/846) | `state/remoteConnState.ts:85` | Connection-Progression (CYP-827) |
| **ConnectFailureCause + Disposition** (CYP-824) | `state/remoteConnState.ts:35-51` | Cause-Anzeige (CYP-823 + §3) |
| **computeIssuerPreVerdict** (CYP-824) | `connector/issuerPreVerdict.ts:38` | axis-c Verdikt, client-konstruiert |

**HubDescriptor (frozen):** `{ hubId: string · name: string · online: boolean · defaultPort: number · lastSeen: number ·
dhPubKey: string · issuerTrust: HubIssuerTrust }`.
**RemoteConnState (frozen):** `{phase: 'idle'|'dialing'|'handshake'|'trust-check'|'connected'|'reconnecting'} |
{phase:'failed', cause} | {phase:'lost'}`.
**★ Validierung:** meine **CYP-827**-Progression (dialing→handshake→trust-check→connected/reconnecting) und die
**CYP-823**-`failed(cause)`≠`lost`-Trennung **matchen den frozen `remoteConnState` 1:1** — die Vorspecs tragen ohne
Änderung. **Neu im frozen Contract:** eine **dritte Disposition `actionable`** (§3).

## 1. Switcher / Hub-Liste (Deliverable 1 — der Kern hier)
**Daten:** `GET /api/cp/hubs` → `HubDescriptor[]`. **Q4 = ONE-ACTIVE-HUB** (Registry = Liste + aktiver-Pointer,
switch-first; CYP-748/PL). Reuse des **`ProjectSwitcher`-Verhaltens** (CYP-651: always-visible, aktiv folgt
**server-confirmed** `activeHubId`, non-optimistisch, switch-to-active = no-op) — **aber Listen-Render, NICHT `<select>`**
(Options tragen kein per-Hub-Badge+a11y; CYP-823 §2a).

**Jeder Hub-Eintrag trägt VIER distinkte Achsen — nie konflatiert** ([[reconcile-not-collapse-distinct-states]] /
[[scope-boundary-is-semantic-not-labeled]]):
| Achse | Quelle | Render im Eintrag | Register |
|---|---|---|---|
| **Name/Identität** | `name` + `hubId` | Label (hubId = stabiler Key) | — |
| **Erreichbarkeit** | `online: boolean` | Online/Offline-Marker | neutral (Erreichbarkeit ≠ Trust!) |
| **Hub-Key-Trust (axis a)** | client: `dhPubKey` vs pinned [TF] | **`HubTrustBadge`** (CYP-801/803, neutral) | Zone-1 Status |
| **Frische** | `lastSeen` | „zuletzt gesehen" (relativ/HH:MM) | neutral |

**Honesty-Kern:**
- **Erreichbarkeit ≠ Trust ≠ Tier ≠ Issuer** — vier Achsen, vier Marker. `online:false` heißt **unerreichbar**, **nicht**
  „untrusted"; ein Offline-Hub behält seinen letzten **beobachteten** Trust-Zustand **nur wenn frisch**, sonst `unknown`.
- **Trust-Frische folgt der CONNECTION-BEOBACHTUNG, nicht dem Aktiv-Pointer** (modell-agnostisch): ein Hub zeigt echten
  Trust **nur wenn er über eine live Verbindung frisch beobachtet** wurde; ohne frische Beobachtung → **nie** das letzte
  gecachte `trusted` weiterzeigen ([[forecast-vs-observed-disclosure]] / [[absence-reads-as-all-clear]]).
  **★ Kardinalität = switch-first / ONE-ACTIVE-HUB (ratifiziert, cyp755 §-Weichen):** nur der aktive Hub hält eine live
  Connection; **inaktive Hubs = Registry/last-known-State, KEINE Background-Connection** (resource-safe, CYP-611-Sizing).
  Die Trust-Degradation je nach **letzter beobachteter** Zustand:
  - **war `trusted`, jetzt nicht mehr frisch → `stale`** (nicht `unknown`!): der Hub war vertraut, ist aber **nicht mehr
    aktuell** — genau die **CYP-841 cause-agnostische STALE-Copy** („nicht mehr aktuell — erneut bestätigen") + CYP-838-
    Amber. „Nicht mehr frisch, weil weggeschaltet" ist eine Frische-Lapse-Form von *nicht-aktuell* (die CYP-841-Copy trägt
    sie, ohne eine Ursache zu behaupten). **Ehrlicher als `unknown`** — es verwirft die frühere Beobachtung nicht, sagt
    aber „re-connect zum Bestätigen". Das **Lapse-Timing** (sofort-bei-Switch vs Frische-Fenster) = **[TF]** (Team-1s
    Staleness-/Widerruf-Signal, cyp755 §1).
  - **nie beobachtet (frischer Registry-Eintrag) → `unknown`** (fail-closed Default; war nie `trusted` → nicht `stale`).
  - **inaktive Einträge tragen KEINE live Progression** — `RemoteConnState.phase` des inaktiven Hubs ist effektiv
    `idle`/nicht-verbunden; die Progression-Chrome (CYP-827) rendert **nur für den aktiven** Connect.
- **axis-c (`issuerTrust`) ist GAR NICHT im Switcher — GANZE Achse, keine Ausnahme (M2-Ruling: Option a, Zone-clean).**
  `issuerTrust` ist **EIN Feld** (`{TRUSTED, NOT_TRUSTED, REMOTE_NOT_CONFIGURED}`); **einen** Wert (`remote-not-configured`)
  in den Switcher zu heben, während der terminale (`NOT_TRUSTED`) nur in der Failure-Region lebt, würde **axis-c über zwei
  Zonen splitten** = inkohärent (Nutzer: „warum ist ein Issuer-Zustand in der Liste, der andere erst beim Connect?").
  → **kein** `issuerTrust`-Wert im Switcher; die **ganze** Achse c ist der **Zone-2-Connect-Verdikt** (M4/Failure-Region):
  `NOT_TRUSTED` → terminal-Block, `REMOTE_NOT_CONFIGURED` → actionable-Arm (§3), beide **beim Verbinden**, nicht als
  Switcher-Status. *(Falls proaktive Setup-Sichtbarkeit je ein echter Bedarf wird: als **separater Readiness-Indikator**
  — wie `online` —, NICHT als axis-c-Trust-Verdikt, der in den Switcher leakt. Nicht jetzt.)*
- **Empty ≠ Load-Error:** `GET /api/cp/hubs` fehlgeschlagen → Error+Retry (CYP-288, `LoadErrorRetry`), **nicht** leere
  Liste („keine Hubs"). Genuin leer (0 Hubs) → ehrlicher Empty-State. Fail-closed sichtbar.

**Aktiver-Hub-Pointer:** der aktive Hub ist markiert; Switch ist **non-optimistisch** (folgt server-confirmed `activeHubId`,
Liste disabled während pending); switch-to-active = no-op. Der **Teardown/Setup-Flow** (Rolle/Tier/Trust des neuen aktiven
Hubs **fail-closed neu aufgelöst**, nichts reist vom alten) = **CYP-755 §3** (unverändert).

## 2. Deliverables 2–4 → CYP-827 + CYP-823 (validiert, kein Neubau)
- **(2) N-Connection-Status:** pro aktivem Connect eine **`RemoteConnState`-Progression** = **CYP-827-Chrome**
  (dialing→…→connected/reconnecting, honesty-kritische States: trust-check-provisorisch, reconnecting-uncertain,
  CONNECTED-non-optimistisch, a11y polite). **One-active** → eine Progression zur Zeit (Zone-2-in-flight).
- **(3) Connection-State-UX + Cause:** die **Failure-Region** = **CYP-823** (1-Region/N-Arme, `failed(cause)`
  arm-switch). Die geparkten Leaf-Renders finden hier ihren **Host**: `HubTrustBadge` (Zone-1-Switcher, §1) ·
  `RemoteSecurityTierBadge` (Zone-1-aktiv-Header) · `IssuerNotTrustedBlock` (Zone-2-Failure-Arm, `failed(issuer-not-trusted)`).
- **(4) axis/Zonen-Trennung:** **CYP-823 §0** — Zone-1 always-visible Status (Switcher-Trust-Badge + aktiv-Header-Tier)
  vs Zone-2 Connect-Flow/Failure-Region. Ein Hub kann Switcher-`trusted` (Zone-1) UND `failed(issuer-not-trusted)`
  (Zone-2) gleichzeitig sein — verschiedene Trust-Fragen, nie im selben Element.

### 2b. ★ Mount-Host (M4) — Pre-Arming render-honesty (PL-Verfeinerung, load-bearing)
Der CYP-827-A3-Mount kommt **nach vorn** (props-driven, jetzt buildbar) — **aber** die gemounteten Trust-Leaves werden
**pre-arming NUR neutral-default gespeist**: `trust = null` → **UNKNOWN = advisory-neutral** (fail-closed, CYP-824/CYP-805-
Disziplin). Sie werden **NIE** an eine Live-Trust-**Decision** verdrahtet, bevor die **Arming-Naht** die frisch beobachteten
Daten liefert.
- **Warum (render-honesty):** ein Mount **vor** dem Gate/der Beobachtung, der an irgendeine optimistische Quelle hinge,
  würde **unverifiziert als „vertraut"** rendern = **render ≠ Autorität**-Bruch. **Der Server bleibt die Grenze**
  ([[client-gate-is-not-the-boundary]]); der Render ist advisory, nie die Entscheidung. Deckt sich mit §1
  („Frische folgt Beobachtung") und dem fail-closed Default UNKNOWN (CYP-803/755 §1).
- **Regel:** **Zustand advisory-neutral bis Arming; Live-Trust-Daten = die Arming-Naht.** Vor Arming: `HubTrustBadge`
  `trust=null`→UNKNOWN · `RemoteSecurityTierBadge` `tier` unset→`unknown` · Issuer-Verdikt aus `computeIssuerPreVerdict`
  ist **advisory** (client-konstruiert aus dem gehaltenen Descriptor, `issuerPreVerdict.ts` „no oracle"), **nicht** die
  Autorität. *(Tooth: ein gemounteter-aber-nicht-armed Leaf rendert **nie** `trusted`/`native`/proceed — Mutation:
  Mount-vorm-Arming zeigt `trusted` → RED = render-honesty-Bruch.)*

## 3. ★ NEU: die dritte Disposition `actionable` (frozen, CYP-824) — CYP-823-Failure-Region-Ergänzung
`RemoteFailureDisposition` = **`terminal` | `retryable` | `actionable`**. Meine CYP-823-Arme deckten terminal (issuer/auth)
+ retryable (transport). **Neu: `actionable`** = `remote-not-configured` — **KEIN Reject**: der Operator kann es **in-app
lösen** (Aussteller-Vertrauen OOB etablieren), dann **reconnecten** (Parität mit DeviceNotEnrolled). Distinktes Register:
| Disposition | Cause(s) | Arm-Register | Affordance |
|---|---|---|---|
| **terminal** | `issuer-not-trusted` · `security-tier` | WARN-amber HARD BLOCK (CYP-805) | **kein** Retry |
| **retryable** | `connect-refused` · `handshake-fail` | neutral, transient | **Retry** (re-dial) |
| **actionable** | `remote-not-configured` | **neutral-actionable** (nicht Alarm, nicht terminal-block) | **„Erneut verbinden"** — NACH OOB-Schritt; + Hinweis „Remote am Hub einrichten (OOB), dann verbinden" |

- **Honesty:** `actionable` ist **weder** terminal (nicht „geht nie", du KANNST es lösen) **noch** ein plain-retry
  (Retry ohne OOB-Schritt hilft nicht). Die Copy trägt den **OOB-Schritt vor** dem Reconnect (nicht „nochmal versuchen"
  ins Leere). Ton **neutral-actionable**, kein WARN-amber-Block (es ist kein Trust-Refusal), kein error-rot.
- **★ `issuer-revoked` ist OPEN** (Team-1-Input, `remoteConnState.ts:30`) — slottet als 4. Cause additiv in `failed`
  ein; ich spec die Register-Regel vorab: falls Widerruf **terminal** → CYP-805-Block-Register; falls **actionable** →
  dieser §3-Arm. **[TF]-markiert bis Team-1.**

## 4. Teeth (Tester2, beim Mount)
1. **4 Achsen distinkt** — Erreichbarkeit(`online`) ≠ Trust(axis-a-Badge) ≠ Tier ≠ Issuer; kein Marker konflatiert einen
   anderen. *(Mutation: `online:false` rendert als Trust-`rejected`/`unknown` statt als Offline-Marker → RED.)*
2. **Inaktive Hubs degradieren korrekt, nie cached-`trusted`** — war-`trusted`+nicht-mehr-frisch → **`stale`** (CYP-841-Copy),
   nie-beobachtet → **`unknown`**; **nie** das letzte gecachte `trusted`. *(Mutation: inaktiver war-trusted-Hub bleibt
   `trusted` → RED = cached-trust; ODER nie-beobachtet zeigt `stale` → RED = erfundene frühere Beobachtung.)*
3. **axis-c GAR NICHT im Switcher** — **kein** `issuerTrust`-Wert (auch nicht `remote-not-configured`) erscheint im
   Switcher-Eintrag; die ganze Achse c ist Zone-2/M4. *(Mutation: irgendein issuerTrust-Wert als Switcher-Indikator → RED = Zone-Verletzung/Achsen-Split.)*
4. **Empty ≠ Load-Error** — `/api/cp/hubs`-Fehler → Error+Retry, nicht leere Liste.
5. **Switch non-optimistisch** — aktiv folgt server-confirmed `activeHubId`, nie dem Klick.
6. **`actionable` ≠ terminal ≠ retryable** — `remote-not-configured` rendert einen Reconnect-nach-OOB-Arm (kein
   no-retry-Block, kein plain-Retry). *(Mutation: remote-not-configured als terminal-block ODER als plain-retry → RED.)*

## 5. Reuse-Ledger
- **Switch-Verhalten:** `ProjectSwitcher`/CYP-651 (non-optimistisch, server-confirmed) — Verhalten, Render als Liste.
- **State:** `hubRegistry` (CYP-800), `AuthGate.activeHubId`, frozen `HubDescriptor`/`RemoteConnState`/`issuerPreVerdict`.
- **Vorspecs (validiert am frozen Contract):** CYP-827 (Progression), CYP-823 (Zonen/Failure-Region/Mount-Slots),
  CYP-755 §2/§3 (Switcher/Teardown-Setup), CYP-838 (STALE-Token), CYP-841 (STALE-Copy), CYP-805 (Issuer-Block).
- **Leaf-Renders (Host gefunden):** `HubTrustBadge` (CYP-801), `RemoteSecurityTierBadge` (CYP-676), `IssuerNotTrustedBlock`
  (CYP-805) — **kein neuer Leaf**; dies ist Shell/Placement + die neue `actionable`-Register-Regel.
- **Stub:** nur die real-dial-Föderations-Naht (PO misst die exakte Grenze); alles UI/State gegen frozen-surface baubar.
