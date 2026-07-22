# CYP-807 — IssuerNotTrusted Placement im Connect-Flow (web-ts, Desktop-Parität)

> UX/UI-Placement-Deliverable. **Frage:** wo/wann erscheint der `IssuerNotTrustedBlock` (CYP-805), damit der Browser
> **Desktop-Parität** erreicht (user-visible bei `NOT_TRUSTED` / owned-but-no-issuer). **Cross-Team-Richtung (PL/a-po):**
> Compose rendert IssuerNotTrusted als **Arm in der schon-gemounteten `RemoteFailureView`**, nicht als losen Standalone —
> web-ts-Placement daran orientieren, so weit die Architektur es zulässt.

## 0. Kern-Befund zuerst (am Objekt verifiziert — beantwortet Dev5s Messung)
**web-ts hat KEINE `RemoteFailureView`, KEINEN Connect-Flow und KEINE Remote-Connection-State-Machine.** Grounding auf
`origin/develop`:
- Kein `RemoteConnState` / `RemoteFailure` / Connect-Flow in `web-ts/**` (nur die zwei CYP-805-Issuer-Dateien matchen).
- Kein State/Producer, der ein `issuerTrust`-Verdikt liefert — `issuerConnectDecision` wird **nirgends aufgerufen**,
  `<IssuerNotTrustedBlock>` **nirgends gemountet**.
- Auch `RemoteSecurityTierBadge` ist **standalone/ungemountet** — die `connector/`-Bausteine (tier/fidelity/issuer)
  sind **vor ihrem Host gebaut**.

**Konsequenz:** „Arm in der schon-gemounteten Failure-View" hat in web-ts eine **Voraussetzung** — die Failure-View
(+ die sie treibende Conn-/Verdikt-State) **existiert noch nicht**. Das ist **Architektur/State = Dev5/Backend-Wiring**,
nicht meine Design-Lane. Dieses Doc liefert das **host-agnostische Slot-Design**, damit der Block **als Arm** landet
(nicht Standalone), sobald der Host gebaut wird — und benennt die Voraussetzung ehrlich zur Koordination.

## 1. Das Compose-Muster, das wir spiegeln (`HubConnectSelection.kt`)
`RemoteConnectingView` = ein `HubCard`, der auf `remote.conn` schaltet:
`RELAY_DIALING → E2E_HANDSHAKE → TRUST_CHECK → AUTHENTICATING → RECONNECTING → CONNECTED → **LOST**`.
Bei **`LOST`** → **`RemoteFailureView(failure)`**, die auf den Failure-Typ schaltet
(`when(failure) { TrustChanged / AuthRejected / IssuerNotTrusted / … }`). **Regel (§7 Compose):** terminale Failures
(TrustChanged/AuthRejected/**IssuerNotTrusted**) = **kein Retry**; Transport-Failures = Retry.
→ IssuerNotTrusted ist **ein Arm einer einen Failure-Region**, erreicht **nur** im terminalen `LOST`-Zustand, **nie** als
frei schwebendes Element und **nie** mid-progress.

## 2. Placement-Design (host-agnostisch, Struktur-Parität)
**EINE Failure-Region, N Arme** — nicht N lose Blöcke. Der `IssuerNotTrustedBlock` rendert **innerhalb** dieser Region,
als ein Arm eines exhaustiven Switches, **nie** frei platziert. Das garantiert: **genau ein** Failure sichtbar, **eine**
konsistente Platzierung/a11y, **kein** Stapeln zweier Failure-Aussagen.

- **Slot:** eine web-ts `RemoteConnectFailure`-Region (das Äquivalent zu Compose `RemoteFailureView`), gemountet im
  Remote-Connection-/Hub-Connect-Bereich (dort, wo der Connect-Flow lebt / leben wird). Sie schaltet:
  | Verdikt/Failure | Arm | Ton (CYP-805-Sprache) | Retry? |
  |---|---|---|---|
  | `NOT_TRUSTED` (Issuer, Achse c) | `IssuerNotTrustedBlock` | **▲ WARN-amber** HARD BLOCK, OOB | **nein** (terminal) |
  | AuthRejected (Achse b) *(später portiert)* | Auth-Arm | **error-rot** (`HintTone.ERROR`) | nein |
  | TrustChanged (Achse a, Hub-Key) *(später)* | Trust-Arm | re-pin-Register | nein |
  | Transport/Netz *(später)* | Transport-Arm | **neutral**, kein Alarm | **ja** (Retry) |
- **Nur-ein-Arm / Ursache-Ehrlichkeit:** die Region **kollabiert nicht** distinkte Ursachen in ein generisches
  „Verbindung fehlgeschlagen" — jeder Arm trägt seine eigene Copy+Ton ([[reconcile-not-collapse-distinct-states]] /
  [[terminal-block-tone-is-structural]]). Issuer≠Auth≠Trust≠Transport, drei terminale + ein retrybarer.
- **Geteilter künftiger Host mit CYP-803 §2/§3:** der per-Hub-Trust-**Badge** (Achse a, always-visible **Status**) und
  diese Failure-**Region** (Achse c terminaler **Block**) wollen **denselben** Hub-Connection-Surface — aber in
  **verschiedenen Rollen**: Badge = dauerhafter Status im Strip/Switcher; Failure-Region = terminaler Block im
  Connect-Flow (`LOST`). **Nicht** vermischen. (Koordinations-Hinweis: das CYP-803-§2/§3-Placement-Slice und dieses
  teilen den Host — gemeinsam mit Dev5 planen.)

## 3. Übergang (proceed ↔ block)
- **`issuerConnectDecision(verdict) === 'proceed'`** (absent / `TRUSTED` / `REMOTE_NOT_CONFIGURED`): **kein Issuer-Arm**;
  gibt es keinen anderen Failure, **keine** Failure-Region — der Connect-Flow läuft weiter (bis `CONNECTED`). *(Absent →
  proceed ist sicher, weil der **Server** das Gate ist, nicht dieser Render — CYP-805-Modell; [[client-gate-is-not-the-boundary]].)*
- **`=== 'block'`** (`NOT_TRUSTED`): die Failure-Region mountet den **IssuerNotTrusted-Arm** im `LOST`-Zustand —
  **WARN-amber, assertive Ansage, kein Retry/Proceed**, OOB-Text-Hinweis. Der Flow **erreicht `CONNECTED` NICHT**.
- **Timing = terminal, nicht mid-progress:** der Block erscheint am **Verbindungs-Ausgang** (`LOST`/failed), nicht
  während `dialing/handshake` — sonst läse er als transienter Schritt statt als terminaler Stopp.

## 4. Cross-Surface-Konsistenz (Desktop + Browser gleiche user-visible-Semantik)
- **Copy wortgleich** (CYP-805 = Team-1 CYP-747 §5-C2) · **Tags Compose-Parität** (`remote.connect.error.issuerNotTrusted`
  / `remote.connect.issuerOob`, **nicht** per-`hubId` — eine Connection zur Zeit) · **terminal, no-retry** · **assertive**.
- **Gleiche Struktur-Rolle:** beidseitig ein **Arm in einer Failure-View am Verbindungs-Ausgang**, kein Standalone. So
  rendern Desktop und Browser **dieselbe** Failure-Semantik für dasselbe Verdikt.

## 5. Voraussetzung & Koordination (ehrlich)
Dieses Placement ist **build-ready als Slot-Spec**, aber **gated** auf zwei Dinge, die **nicht** meine Lane sind:
1. **Failure-View-Host** (das web-ts `RemoteConnectFailure`-Region-Äquivalent) — Dev5/Architektur.
2. **Conn-/Verdikt-State**, die das `issuerTrust`-Verdikt an die Region liefert (woher kommt `NOT_TRUSTED` im
   web-ts-Client?) — Backend-Wiring/State.
Ohne (1)+(2) bleibt der `IssuerNotTrustedBlock` ungemountet (heutiger Stand). **Mein Vorschlag:** Dev5 baut die
Failure-Region als **Arm-Switch** (nicht nur einen Einzelblock einhängen), damit AuthRejected/TrustChanged/Transport
später additiv als Arme landen — Struktur-Parität zu Compose von Anfang an. Ich richte das Placement an dem aus, was
Dev5s Messung ergibt; falls web-ts bewusst **keinen** Connect-Flow bekommt (Operator-Konsole ist schon server-lokal),
dann ist der ehrliche Slot der **Hub-Connection-Status-Bereich** (geteilt mit CYP-803 §2/§3) — dann reconcilen wir Rolle
(Status-Badge vs terminaler Block) dort.

## 6. Teeth (Tester2, sobald gemountet)
1. **Arm, nicht Standalone** — der Block rendert **innerhalb** der Failure-Region, nie frei; genau **ein** Failure-Arm
   sichtbar. *(Mutation: zwei Failure-Aussagen gleichzeitig / Block außerhalb der Region → RED.)*
2. **proceed → kein Block** — `absent`/`TRUSTED`/`REMOTE_NOT_CONFIGURED` rendert **keinen** Issuer-Arm.
   *(Mutation: Block bei proceed → RED.)*
3. **block → Block sichtbar, terminal** — `NOT_TRUSTED` → IssuerNotTrusted-Arm, **kein** Retry/Proceed/CONNECTED im DOM.
   *(Mutation: Retry-Control / CONNECTED erreichbar bei NOT_TRUSTED → RED = fail-closed verletzt.)*
4. **terminaler Ausgang, nicht mid-progress** — der Arm erscheint im `LOST`/failed-Zustand, nicht während dialing/handshake.
5. **Ursache-distinkt** — Issuer-Arm (WARN-amber) ≠ Auth-Arm (error-rot) ≠ Transport-Arm (neutral+Retry); keine
   Kollaps-Copy „Verbindung fehlgeschlagen". *(Mutation: generischer Failure-Text für NOT_TRUSTED → RED.)*
6. **Cross-Surface-Tag-Parität** — `remote.connect.error.issuerNotTrusted` beidseitig, nicht per-`hubId`.

## 7. Reuse-Ledger
- **Muster:** Compose `RemoteConnectingView`/`RemoteFailureView` (`HubConnectSelection.kt`) — conn-switch → LOST →
  failure-arm-switch; terminal=no-retry.
- **Block-Inhalt:** CYP-805 `IssuerNotTrustedBlock` + `issuerTrustModel` (unverändert — dies ist nur Placement).
- **Geteilter Host:** CYP-803 §2/§3 (per-Hub-Trust-Badge/Switcher) — Rollen-Trennung Status-Badge vs terminaler Block.
- **Kein Neubau:** keine neue Copy/Ton/Glyph (alles CYP-805); dieses Doc ist **reines Placement + Übergang + der
  Architektur-Befund**.
