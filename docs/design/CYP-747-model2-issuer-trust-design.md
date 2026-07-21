# CYP-747 — Modell 2: Aussteller-Vertrauen (Cross-Hub-Operator-Identität) — Trust-Modell-Design

> Status: **Design komplett (Rev. 3 — Auftraggeber-Weichen Q1/Q2/Q3 eingearbeitet, Seams A/B code-belegt aufgelöst, §9 Browser-Pfad exhaustiv am Chokepoint verankert) — zum adversarialen Gegenlesen durch den Project Lead. KEIN Bau vor Freigabe.**
> Autor: PO (Team-1, Coordinator). Grundlage: eigene read-only Objekt-Erhebung (`develop 7dd39b23`) + Team-2-Client-Needs (UIUX2 / CYP-748) + Reviewer-Second-Opinion (PO-Assistent, code-verifiziert) + PL-Review-Punkte (Rev.2) + Auftraggeber-Weichen (2026-07-21).
> Verwandt: CYP-427 Ph2 (Relay-CODE-Lineage), CYP-702 (deploybare-Relay-Paketierung), CYP-697 (Operator-Widerruf → Agenten verstummen; von Q2 abgedeckt), [[architecture-two-principals-no-foreigners]].
>
> **Δ zu Rev.2:** Q1=SEAT bestätigt · **Q2=aktive Widerruf-Propagierung (a2) ENTSCHIEDEN** (schließt CYP-697; a1-MVP-Empfehlung SUPERSEDED) · **Q3=eigenes-Relay-jetzt ENTSCHIEDEN** (CYP-702 auf dem Pfad) · Seam A (WebAuthn-Origin) + Seam B (OOB-Warming) code-belegt gefaltet · **§9 neu: exhaustive per-Kante-Enumeration am `resolvePrincipal`-Chokepoint (Eigenschaft, nicht Liste) + AAL2-Fix** (PL-0067-Abnahmekriterium).

Dieses Dokument beschreibt **was** das Trust-Modell ist und **welche Zähne** der spätere Bau tragen muss. Kein Implementierungsplan.

---

## 0. Scope & gelockte Entscheidungs-Anker (Auftraggeber-Weichen 2026-07-21 eingearbeitet)

- **Modell 2 = EINE Operator-Identität über mehrere Hubs DESSELBEN Operators**, via einen **Aussteller (= Relay)**. **NICHT** Cross-Operator-Föderation / Multi-Tenant / Delegation. PL + Auftraggeber bestätigt.
- **Q1 = SEAT (n), ENTSCHIEDEN:** `pinnedOperatorId` + Device-PoP **IST** der Seat; über die Hubs generalisiert — **kein Menschen-Record**. §5b/AuthMe bleiben draußen; **Team-2-Client-Needs sind seat-scoped zu klassifizieren.** Portable Menschen-Identität (j) bleibt aufgeschoben.
- **Q2 = AKTIVE Widerruf-Propagierung (a2), ENTSCHIEDEN:** Schritt 3 (Issuer-Widerruf) wird **VOLL gebaut** — aktive Propagierung im Hub↔Relay-Protokoll + Cross-Hub-Session-Registry (C3). **Schließt CYP-697** (Operator aus Verzeichnis entfernt → Agenten verstummen sofort). Die a1-„kurzlebig-nur"-MVP-Empfehlung aus Rev.2 ist **abgelöst** (Auftraggeber wählte die stärkere Haltung).
- **Q3 = EIGENES RELAY JETZT, ENTSCHIEDEN:** Aussteller = eigenes Relay; BYOAuth-extern später **hinter derselben Abstraktion**. **CYP-702** (`:relay`-Extraktion + deploybar) ist auf dem Pfad bestätigt.
- **one-active-Hub = reine Client-Konvention** (PL §4a), NICHT server-erzwungen; Security davon vollständig entkoppelt (§6).
- **(d) Browser-Posture = FAIL-CLOSED-mit-WebAuthn** (aus Seam A + §9-Exhaustiv): kein hub-lokaler zweiter Faktor → kein Browser-Operator-Zugang. Keine stille Degradation.

---

## 1. Ausgangslage — Modell 2 ist ~70% real, kein Greenfield

Aus Objekt-Erhebung + Reviewer-Verifikation (jede Behauptung gegen den Code):

- **Issuer-Signatur-Primitive existiert** — Aussteller heute = Control-Plane (CP), nicht Relay: CP mintet Ed25519-JWS (`CpJwtMinter.kt`, alg-pinned `EdDSA`; `iss`/`aud==hubId`/`sub==operatorId`/`nbf`/`exp`/`cb==base64url(SHA-256(h‖hubId))`). Hub verifiziert gegen gepinnten Aussteller-Pubkey per `kid` (`CpJwtVerifier.kt` + `IdentityToken.kt` PHASE1).
- **CHOKEPOINT `Rr3TunnelGate.kt`:** nach Noise-Handshake, vor jedem HTTP-Byte: **Issuer-JWS-Signatur ∧ Operator-Device-PoP** (`:118-161`, `sub==pinnedOperatorId` + `cb`-Bindung + Device-PoP gegen hub-lokalen `deviceStore.enrolled()`-Anker). JWS trägt **keinen** Device-Key ⟹ Aussteller kann via JWS **keinen** Device injizieren.
- **ANKER-SEAM:** `RemoteRelayWiring.kt:169-176` — heute single `expectedIssuer`/`cpPublicKey(kid)`/`pinnedOperatorId`, env-gefüttert, fail-closed INERT.
- **Relay heute = dummer Transport-Rendezvous** (`RelayServer.kt`, `RendezvousRelay.kt`), NULL Identität.
- **Trust-Set ist bereits Multi-Hub:** `PinnedHubStore` = `hubId`-gekeyte Map; nur die aktive Verbindung ist singulär.
- **Owner-Scoping = Default:** `GET /api/cp/hubs` (CYP-530) nur Hubs mit `ownerId == cpOperatorId`.
- **Zwei Client-Identitäten:** CMP/Remote präsentiert per-Hub `cpJwt + Device-PoP` über Noise (erfüllt N2). web-ts/Browser nutzt ambientes same-origin-Cookie (Kratos) — spannt nicht über Origins ⟹ die N2-Lücke (§9).

---

## 2. Das Drei-Schritte-Modell (Q3: Aussteller = eigenes Relay)

| Schritt | Was | Wo (Seam) | Risiko |
|---|---|---|---|
| **1. Aussteller CP→Relay repointen** | `expectedIssuer` + `cpPublicKey(kid)` → **Relay-Signaturschlüssel** | `RemoteRelayWiring.kt:169-171` | §3-Invariante |
| **2. Verifier-Anker generalisieren** | single `pinnedOperatorId` → „Operator über owned Hub-Set", **hub-seitig** | `IdentityToken.kt:75` | §3-Invariante am selben Locus |
| **3. Issuer-Level-Widerruf (Q2=a2, VOLL)** | aktive Propagierung im Hub↔Relay-Protokoll + Cross-Hub-Session-Registry | Hub↔Relay + Registry (C3) | §7 |

Cross-Hub-Identität issuer-seitig (`cpOperatorId`) weitgehend real; Rest-Gap = Repoint (1) + hub-seitige Verifier-Generalisierung (2) + **aktiver Widerruf (3, Q2)** + First-Enroll/Browser-Anker (§3/§9).

---

## 3. ★ Die tragende Invariante — als oberflächen-agnostische EIGENSCHAFT

> **INVARIANTE (property, nicht Flächen-Aufzählung):** *Jede Trust-Fläche verlangt einen **hub-lokal verankerten zweiten Faktor** (Device-PoP / WebAuthn gegen den am Hub gepinnten Anker). Wo ein solcher Anker existiert („gewärmter Hub"), ruht das Vertrauen auf ihm, nicht auf dem Aussteller/Relay. Wo keiner existiert, ruht das Vertrauen zwangsläufig auf dem Aussteller — erlaubt **nur** bis zum per-Hub-OOB-Warming, nie als Dauerzustand.*

**Steady-State (gewärmter Hub) — GO, code-belegt:** Relay-als-Aussteller bleibt **AND-gekoppelt ans Operator-Device-PoP** (`Rr3TunnelGate` gegen hub-lokalen Anker; JWS ohne Device-Key). Schritt 2 fasst das PoP-Gate strukturell nicht an. Kompromittiertes Relay kann den Operator **nicht** imitieren.

**Zwei ungewärmte Flächen (der Gap):**
1. **Kalter owned-Hub, First-Enroll (Maschine):** leerer Anker → `firstEnrollThenGrant` verifiziert PoP gegen den PRÄSENTIERTEN Key und ankert ihn. ⟹ Relay-JWS ist das EINZIGE Operator-Gate; ein kompromittiertes Relay mintet JWS an einen kalten Hub, präsentiert seinen eigenen Key, wird Anker.
2. **Browser (immer):** kein Device-PoP existiert je — dieselbe Krankheit permanent (§9).

**★ Seam B (OOB-Warming) — code-belegt GELÖST:** Die OOB-Warming-Mechanik existiert bereits — **`OobFingerprintConfirmer` / `OobFingerprintConfirmScreen` (CYP-482):** der Mensch vergleicht den Hub-DH-Fingerprint (PGP-11-Wort) **gegen die Hub-Konsole**, mandatory-blocking; ein MITM-Relay kann den Fingerprint nicht fälschen ⟹ die Vertrauenswurzel beim First-Enroll ist **Konsolen-/physischer Zugang, relay-unabhängig**. „needs-OOB-warming" (§3-c, §8-Bug-Zeile) koppelt an genau diesen Confirmer.

**Akzeptanzkriterium (§11-1) — DREI Arme, non-vakuöses Differential-Paar PRO Hub, identisch außer PoP:**
```
(a) (Issuer-JWS OHNE Device-PoP,  gewärmter Hub_k)                      → REJECT
(b) (Issuer-JWS MIT gültigem PoP über GEANKERTEN Key, gewärmter Hub_k)  → ACCEPT   [Positiv-Kontrolle]
(c) (Relay-JWS, LEERER Anker an Hub_k, PoP über ANGREIFER-Key)          → needs-OOB-warming (via OobFingerprintConfirmer), NICHT still granten
```
Arm (a)+(b) sind necessary-but-not-sufficient (nur Steady-State); Arm (c) schließt den First-Enroll-Seizure. Frictionless-cold-enroll = stiller Bulk-Auto-Accept über N Hubs.

---

## 4. Token-/Proof-Format (Aussteller-signiert)

`CpJwt`-Form beibehalten (kein neues Krypto), nur der Aussteller wechselt (Q3: Relay): Ed25519-JWS, header hart `alg:EdDSA`; `iss`=Relay-Aussteller; `aud==hubId` (N2-Grenze); `sub==operatorId` (Seat, Aussteller-gesetzt — Client wählt `sub` nie); `nbf`/`exp` **kurzlebig** (Minuten); `cb==base64url(SHA-256(h‖hubId))`. `kid`→Aussteller-Pubkey (unbekannt → reject). Device-PoP unverändert (der Anti-Seizure-Faktor, §3).

---

## 5. Trust-Anker (Schritt 1+2) & die zwei Vertrauens-Kanten (C2)

**Repoint (1):** `expectedIssuer`+`cpPublicKey(kid)` → Relay-Aussteller-Key, fail-closed INERT bleibt.
**Generalisierung (2):** `pinnedOperatorId` → „vom Aussteller behauptete Operator-Identität", `sub` weiter an den Seat, `aud` weiter an DIESEN Hub — ohne die per-Hub-`AND(JWS, Device-PoP)` zu schwächen.

**★ C2 — Konnektierbarkeit = AND aus ZWEI unabhängigen Kanten, fail-closed:** (1) Operator besitzt Hub B (CP-Owner-Check). (2) Hub B vertraut dem Aussteller/Relay (separate Hub↔Relay-Kante, NEU). Ein owned Hub kann den Aussteller **noch nicht** vertrauen → distinkter Zustand `owned-but-issuer-not-trusted` (nicht connectable, nie stiller Hang).

**★ Zwei Nähte (Reviewer):**
- **(a) N4a-Distinktheit ist CLIENT-seitig:** der Tunnel-Reject ist uniform/no-oracle; der Client leitet `Netz / Trust-Reject / Widerruf / DeviceNotEnrolled` aus eigenem Wissen ab, nicht aus einem Server-Code.
- **(b) Kein INERT-Verschwinden:** C2 muss einen beobachtbaren typisierten Zustand emittieren, nicht in INERT-Stille verschwinden.

---

## 6. Verifikation (Rr3-Gate, per Hub, nebenläufigkeits-sicher)

Der Gate verifiziert **pro Hub, unabhängig:** Issuer-JWS (Relay-signiert, `aud==hubId`, `cb`, `exp`) ∧ Device-PoP über live `h` gegen den hub-lokalen Anker. Nebenläufigkeits-Sicherheit by construction (per-Hub-instanziierter Gate, per-Hub `firstEnrollLock`, per-Hub-disjunktes Keying). Sicherheit ruht auf per-Hub-AND, nie auf one-active.

**★ Cap-24-Naht (Liveness/DoS):** die per-Hub-Cap zählt **authentifizierte Sessions**, nicht Relay-vermittelte Rendezvous-Versuche — sonst kann ein böswilliges Relay die Cap mit Junk erschöpfen und den Operator aussperren.

---

## 7. Widerruf (Schritt 3 = Q2 = a2 AKTIV, VOLL gebaut) — schließt CYP-697

**Ist-Stand (C3):** Widerruf heute lazy, ≤TTL, kein Push, keine Registry; nichts zählt/killt die anderen live Hub-Sessions des Operators.

**★ Reframe (Reviewer):** Widerruf ist eine WIRKSAMKEIT, keine UX: im ≤TTL-Fenster verifiziert und VERBINDET ein widerrufenes Credential weiter. „Lazy ≤TTL" heißt „Widerruf ist für ≤TTL wirkungslos — null Hebel bei Kompromittierung im Fenster."

**ENTSCHEIDUNG (Q2 = a2, Auftraggeber):** **aktiver Issuer-Widerruf im Hub↔Relay-Protokoll + Cross-Hub-Session-Registry.** Push-STALE/REJECTED über alle live Hub-Sessions des Operators — **ein echter Hebel** bei Kompromittierung, sofort wirksam. **Schließt CYP-697:** Operator aus dem Verzeichnis entfernt → aktive Propagierung → Agenten/Sessions verstummen **sofort**, nicht erst nach TTL-Ablauf. Die a1-„kurzlebig-nur"-Empfehlung aus Rev.2 ist damit abgelöst.

**★ C1 — Device-Recovery = Re-TOFU = Bulk-MITM-Fenster:** Session-Refresh (gleiches Device) MUSS per-Hub-Pins **erhalten** (nur Credentials re-minten); echte Neu-Device-Recovery re-walkt TOFU **OOB pro Hub** (via `OobFingerprintConfirmer`), nie stiller Bulk-Auto-Accept. Gilt symmetrisch für First-Enroll (§3-Arm c).

---

## 8. Client-Needs → Vertrags-Pflichten (N1–N5, C1–C3) + Wahrheitstabelle

- **N1:** stabile `hubId` + owner-scoped Set (`/api/cp/hubs`). Client keyt je `hubId`.
- **N2:** per-Hub `aud`+`cb`+PoP; Empfänger-Hub lehnt wrong-audience strukturell ab. Browser = Delta (§9).
- **N3 (fail-closed):** distinkte Zustände **UNKNOWN / PENDING / TRUSTED / REJECTED / STALE / `owned-but-issuer-not-trusted` / `needs-OOB-warming`**, nie auf „grün" kollabiert, Default NICHT-vertraut.
- **N4:** client-seitig unterscheidbare Ursachen (Netz / Trust-Reject / Widerruf / `DeviceNotEnrolled`) — client-abgeleitet. **§4a-Defekt:** `DeviceNotEnrolled` heute in `remote_pop_rejected` kollabiert → distinkter Zustand. **N4b:** per-Hub-Fehler an Hub B reißt Hub-A nicht mit.
- **N5:** Rolle/Tier je Hub aus dessen whoami, fail-closed least-privilege (`Operator@A ⇏ Operator@B`); kein fake-instant „verbunden".

**★ Vollständigkeits-Pflicht (Anti-Vakuität):** der Bau MUSS die volle Wahrheitstabelle über `(owned?, issuer-trusted?, jws-valid?, pop-valid?, expired?, ENROLLED?)` enumerieren — `enrolled?` ist eine eigene Spalte — und behaupten, dass jede Zeile in einen definierten, nicht-grün-außer-verdient Zustand fällt. Bug-Zeile explizit:
```
owned=ja ∧ issuer-trusted=ja ∧ jws=gültig ∧ pop=gültig-über-PRÄSENTIERTEN-Key ∧ enrolled=NEIN
        → needs-OOB-warming (via OobFingerprintConfirmer)   (NICHT TRUSTED/connectable)
```

---

## 9. ★★ Der Browser-Pfad-Delta (web-ts) — AAL2-Faktor am Chokepoint erzwungen (Eigenschaft, nicht Liste)

**PL-0067-Abnahmekriterium:** kein Pfad darf das ambient Cookie eine Operator-Aktion **ohne** hub-lokalen zweiten Faktor autorisieren lassen. Nicht „an der Haupt-Challenge korrekt" — sondern an **JEDER** operator-autoritativen Kante, per **Eigenschaft am Chokepoint**, nicht per Liste (sonst die Aufrufer-Krankheit eine Ebene höher: eine 21., ungelistete Kante reißt das Loch wieder auf).

### 9.1 Der Befund am Objekt — das ECHTE Loch heute

- **~20 operator-autoritative `/api`-Routen** (`authenticatedApi(deps, AuthRole.OPERATOR)` — AdminMetrics, AgentMgmt, ChannelShare, CommRoutes (ACL-PUT / read-state / messages), Compact, Config, Connector, HubAdmission, HubDiscovery, HubTicket, Lifecycle, Mode, ParticipantToken, Project, Rendezvous, Report, ReprovisionPreview, TerminalGrant) + **WS** (`AgentSocket`) + **Terminal** (`TerminalAccess`) autorisieren via den **OPERATOR-Principal**, NICHT via eine per-Route-PoP.
- **Device-PoP / WebAuthn wird NUR an der Tunnel-Kante geprüft** (`Rr3TunnelGate`, `RemoteRelayWiring`) — auf **KEINER** `/api`-Route. Der Browser-same-origin-`/api`-Pfad traversiert den Tunnel nicht.
- **Session→OPERATOR-Chokepoint = `resolvePrincipal` → `RoleStore.ensureAssigned`** (`Principal.kt:95/121`, `RoleStore.kt:70`): OPERATOR bei `verified==true` (RC1) + pinned + slot-free. **Prüft KEIN AAL / keine WebAuthn-Backing** (grep aal|assurance|amr|acr|webauthn|mfa im Auth-Pfad = 0 für Session-AAL).
- ⟹ **Eine password-only (AAL1) Kratos-Session für den pinned-Operator autorisiert volle Operator-Autorität über alle ~20 Browser-`/api`-Kanten OHNE WebAuthn-PoP. Das Anti-Seizure-AND FEHLT auf den Browser-`/api`-Kanten.** DAS ist das Browser-Seizure-Loch (nicht das Cookie „an sich").

### 9.2 Zwei Operator-Auth-Achsen (verhindert falsches „ein Chokepoint deckt alles")

- **Achse COOKIE (Browser):** Kratos-Session → `resolvePrincipal` → `Human(OPERATOR)`. `authenticatedApi`/`requirePrincipal` (Principal.kt:157/169/190) rufen ALLE `resolvePrincipal`; WS `AgentSocket:68` + Terminal `TerminalAccess:177` fallen ebenfalls auf `resolvePrincipal` für die Cookie-Achse. ⟹ **EIN Chokepoint für die Cookie-Achse.**
- **Achse TOKEN (statischer Operator-Bearer, `isOperator(token)`=`token==operatorToken`, `Auth.kt:33`):** direkt geprüft in `AgentSocket:65` + `TerminalAccess:173`. Das ist die **CMP/native/LOKAL**-Operator-Credential, NICHT der Browser (ein Browser hat den statischen `operatorToken` nicht). Anti-Seizure der Token-Achse = Tunnel-Device-PoP (remote) / Lokal-Box-Trust (local). **Kein Browser-Seizure-Vektor** — aber in Rev.3 benannt, damit sie nicht mit der Cookie-Achse vermischt wird.

### 9.3 Der Fix — AAL2/WebAuthn am `resolvePrincipal`-Chokepoint (fail-closed, hubId-channel-bound)

- **AAL2-Gate AM Chokepoint:** die OPERATOR-Rolle wird NUR einer **WebAuthn/AAL2-gebackten** Kratos-Session zuerkannt — AAL1 → OPERATOR VERWEIGERN (fail-closed). Weil `resolvePrincipal`(Human→OPERATOR) der **einzige** Cookie-Chokepoint ist, **erben ALLE ~20 /api + WS + Terminal Kanten den Faktor als EIGENSCHAFT** (auch die 21., künftige — solange sie `authenticatedApi`/`resolvePrincipal` nutzen). Kein per-Route-Gate.
- **★ Seam A (WebAuthn-Origin) — code-belegt GELÖST:** WebAuthn bindet per RP-ID an den **Origin**, nicht an `hubId` → bei GETEILTER Origin (die §9s same-origin-Cookie überhaupt nutzt) kollabierte per-Hub-`AND` auf per-Origin. **Aufgelöst:** die WebAuthn-**Challenge** ist bereits hub-gebunden — `operatorAuthChallenge(h, hubId, nonce)` (`OperatorAssertionVerifier:93/125`) → der Browser muss `hubId` in die Challenge spiegeln (via dieselbe `cb=SHA-256(h‖hubId)`-Mechanik). Die per-Hub-Bindung sitzt in der **Challenge**, nicht in der RP-ID ⟹ **das AND überlebt geteilten Origin.** **Subdomain-per-Hub ist FALSCH** (andere Origin → bricht §9s Cookie-Prämisse).
- **Server prüft AAL selbst** (defense-in-depth), verlässt sich nicht nur auf Kratos-Config.
- **Kein Fallback:** kein password-only OPERATOR; Session-Resumption erhält die AAL2-Backing; die Token-Achse (Achse 2) ist separat + nicht browser-erreichbar.

### 9.4 (d) Browser-Posture = FAIL-CLOSED

Ohne hub-lokalen Browser-Faktor (WebAuthn) ist der Browser-Teil nicht anti-seizure → **fail-CLOSED**: kein Faktor → kein Browser-Operator-Zugang. Keine benannte Degradation (Auftraggeber/PL). Folge (Team-1/Security): erlaubte `connect-src`/Origin-Menge Relay-verankert eng weiten.

---

## 10. Weichen-Status (alle Auftraggeber-entschieden — keine offenen mehr für den Bau)

- **(a) Widerruf-Wirksamkeit → a2 AKTIV** (Q2). Kein a1-MVP mehr.
- **(b) Aussteller-Reichweite → eigenes-Relay jetzt** (Q3); (j) portable-Menschen-Identität aufgeschoben.
- **(d) Browser-Posture → fail-CLOSED-mit-WebAuthn** (aus Seam A + §9).
- Verbleibend NICHT-Weiche, sondern Bau-Detail: die Cross-Hub-Session-Registry-Form (C3) für a2 — konventionell, PO/Backend.

---

## 11. Akzeptanz-Zähne, die der Bau tragen MUSS (non-vakuös, mutations-bewiesen)

1. **Anti-Seizure-Differential-TRIPEL pro Hub** (§3): (a) JWS-only→reject ∧ (b) JWS+PoP-über-Anker→accept ∧ (c) JWS+leerer-Anker+Angreifer-PoP→needs-OOB-warming.
2. **Wrong-Audience-struktureller-Reject** (N2): Hub-A-Credential an Hub B → `aud≠hubId` ∧ `cb≠SHA256(h‖hubB)` ∧ PoP-über-fremdem-`h`.
3. **Per-Hub-Isolation** (N4b): Trust-/401-Verlust an Hub B rötet nicht Hub A.
4. **`owned-but-issuer-not-trusted` distinkt + beobachtbar** (C2): eigener Zustand, nicht INERT-Stille.
5. **`DeviceNotEnrolled` ≠ rejected** (N4a), client-abgeleitet.
6. **Nebenläufigkeits-Sicherheit** (§6): per-Hub-AND hält unter parallelen Same-Operator-Sessions.
7. **★ Aktiver-Widerruf-Wirksamkeit (Q2/a2)** (§7): nach Widerruf → **Push-STALE/REJECTED über ALLE live Hub-Sessions SOFORT** (nicht ≤TTL); CYP-697-Fall (Operator entfernt → Agenten verstummen) explizit.
8. **★★ Browser-AAL2-Chokepoint-EIGENSCHAFT** (§9): eine synthetische **Nicht-AAL2**-Operator-Cookie-Session wird an **JEDER** operator-autoritativen Cookie-Kante abgewiesen — property-test am `resolvePrincipal`-Chokepoint, NICHT Stichprobe. **+ Zusatz-Guard: KEIN neuer operator-`/api`-Pfad umgeht `resolvePrincipal`/`authenticatedApi`** (die 21.-Kante-Prüfung; heute sind `AgentSocket:65`/`TerminalAccess:173` die separate Token-Achse, kein Cookie-Bypass). Non-vakuos: Mutation „AAL1 erlaubt" rötet an jeder Kante.
9. **Browser-Differential-Paar** (§9.3): JWS+Cookie-only→reject ∧ JWS+Browser-PoP-über-hubId-channel-bound-Challenge→accept, pro Hub.
10. **Cap gegen auth-Sessions** (§6): Relay-Junk-Rendezvous erschöpft die Cap nicht.
11. **Wahrheitstabellen-Vollständigkeit** (§8): jede `(…, enrolled?)`-Zeile in einem definierten nicht-grün-außer-verdient Zustand.

---

## 12. Non-Goals / aufgeschoben

Kein server-erzwungenes one-active-Gate · keine Cross-Operator-Föderation · keine portable-Menschen-Identität (j) · **kein Bau vor PL-Gegenlesen + Auftraggeber-Bau-GO.**

---

## 13. Provenance

Objekt-Erhebung (`7dd39b23`) + Reviewer-Verifikation: `CpJwtMinter.kt`, `CpJwtVerifier.kt`, `IdentityToken.kt:64-82`, `Rr3TunnelGate.kt:118-161`, `RemoteRelayWiring.kt:150-176`, `OperatorAssertionVerifier.kt:90/93/125/149`, `OobFingerprintConfirmer`/`OobFingerprintConfirmScreen` (CYP-482), **`Principal.kt:95/105/112/121/142/157/169/190` (resolvePrincipal-Chokepoint, kein AAL)**, **`RoleStore.kt:70-71/124`**, **`Auth.kt:33` (isOperator-Token-Achse)**, **`AgentSocket.kt:65/68`, `TerminalAccess.kt:173/177`**, **die ~20 `authenticatedApi(OPERATOR)`-Routen**, `Csrf.kt` (same-origin-Cookie nicht device-bound), `PinnedHubStore.kt`, `RelayServer.kt`, `RendezvousRelay.kt`.
Auftraggeber-Weichen 2026-07-21: Q1-Seat / Q2-a2-aktiv / Q3-eigenes-Relay. PL-Punkte: §9 Browser-Anti-Seizure per-Kante-Exhaustivität (PL-0067). Honesty-Invariante ⇔ ratifizierte §4a-Regel `unknown ≠ all-clear`.
