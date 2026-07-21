# CYP-747 — Modell 2: Aussteller-Vertrauen (Cross-Hub-Operator-Identität) — Trust-Modell-Design

> Status: **Design komplett (Rev. 3 — Auftraggeber-Weichen Q1/Q2/Q3 eingearbeitet, Seams A/B code-belegt aufgelöst, §9 Browser-Pfad exhaustiv am Chokepoint verankert) — zum adversarialen Gegenlesen durch den Project Lead. KEIN Bau vor Freigabe.**
> Autor: PO (Team-1, Coordinator). Grundlage: eigene read-only Objekt-Erhebung (`develop 7dd39b23`) + Team-2-Client-Needs (UIUX2 / CYP-748) + Reviewer-Second-Opinion (PO-Assistent, code-verifiziert) + PL-Review-Punkte (Rev.2) + Auftraggeber-Weichen (2026-07-21).
> Verwandt: CYP-427 Ph2 (Relay-CODE-Lineage), CYP-702 (deploybare-Relay-Paketierung), CYP-697 (Operator-Widerruf → Agenten verstummen; von Q2 abgedeckt), [[architecture-two-principals-no-foreigners]].
>
> **Δ zu Rev.2:** Q1=SEAT bestätigt · **Q2=aktive Widerruf-Propagierung (a2) ENTSCHIEDEN** (schließt CYP-697; a1-MVP-Empfehlung SUPERSEDED) · **Q3=eigenes-Relay-jetzt ENTSCHIEDEN** (CYP-702 auf dem Pfad) · Seam B (OOB-Warming) code-belegt gefaltet (`OobFingerprintConfirmer`) · **§9 nach Reviewer-Re-Pass überarbeitet (PL-0067):** der Seam-A-Claim (WebAuthn-Challenge schließt Browser-per-Hub-AND) ist **GESTRICHEN** (tunnel-`h` browser-unerreichbar) → §9.3 Chokepoint-AAL2 als per-Kante-Eigenschaft (schließt nur AAL1→AAL2) · §9.4 browser-remote HEUTE unmöglich (Relay=Noise-Rendezvous) · §9.5 local/remote-Grenze code-belegt non-forgeable (Tunnel=einzige-Brücke ∧ `config.hub.host`-loopback ∧ kein-3.-Ingress) · §9.6 browser-remote-multi-hub-Zukunft = per-Hub-Origins (distinkte Hostnamen + host-only Cookies, simpler+sicherer als per-Aktion-WebAuthn), NICHT das ambiente Cookie (die „Prämisse" WAR die Verwundbarkeit).

Dieses Dokument beschreibt **was** das Trust-Modell ist und **welche Zähne** der spätere Bau tragen muss. Kein Implementierungsplan.

---

## 0. Scope & gelockte Entscheidungs-Anker (Auftraggeber-Weichen 2026-07-21 eingearbeitet)

- **Modell 2 = EINE Operator-Identität über mehrere Hubs DESSELBEN Operators**, via einen **Aussteller (= Relay)**. **NICHT** Cross-Operator-Föderation / Multi-Tenant / Delegation. PL + Auftraggeber bestätigt.
- **Q1 = SEAT (n), ENTSCHIEDEN:** `pinnedOperatorId` + Device-PoP **IST** der Seat; über die Hubs generalisiert — **kein Menschen-Record**. §5b/AuthMe bleiben draußen; **Team-2-Client-Needs sind seat-scoped zu klassifizieren.** Portable Menschen-Identität (j) bleibt aufgeschoben.
- **Q2 = AKTIVE Widerruf-Propagierung (a2), ENTSCHIEDEN:** Schritt 3 (Issuer-Widerruf) wird **VOLL gebaut** — aktive Propagierung im Hub↔Relay-Protokoll + Cross-Hub-Session-Registry (C3). **Schließt CYP-697** (Operator aus Verzeichnis entfernt → Agenten verstummen sofort). Die a1-„kurzlebig-nur"-MVP-Empfehlung aus Rev.2 ist **abgelöst** (Auftraggeber wählte die stärkere Haltung).
- **Q3 = EIGENES RELAY JETZT, ENTSCHIEDEN:** Aussteller = eigenes Relay; BYOAuth-extern später **hinter derselben Abstraktion**. **CYP-702** (`:relay`-Extraktion + deploybar) ist auf dem Pfad bestätigt.
- **one-active-Hub = reine Client-Konvention** (PL §4a), NICHT server-erzwungen; Security davon vollständig entkoppelt (§6).
- **(d) Browser-Posture = abgestuft (§9.4–9.6):** local-direct adäquat (Chokepoint-AAL2); browser-**remote HEUTE unmöglich** (Relay=Noise-Rendezvous, kein HTTP-Proxy); browser-remote-multi-hub = **benannte Zukunft via per-Hub-Origins** (distinkte Hostnamen + host-only Cookies), NICHT das eine ambiente Cookie. Der frühere „Seam-A-schließt-per-Hub"-Claim ist **gestrichen** (tunnel-`h` browser-unerreichbar, Reviewer-Fund).

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

### 9.3 Der Teil-Fix — Chokepoint-AAL2 schliesst AAL1→AAL2, NICHT die per-Hub-Replay-Achse (Reviewer-Korrektur, ehrlich)

**Notwendig, nicht hinreichend — AAL2-Gate AM Chokepoint:** die OPERATOR-Rolle wird NUR einer **WebAuthn/AAL2-gebackten** Kratos-Session zuerkannt — AAL1 (password-only) → OPERATOR VERWEIGERN (fail-closed). Weil `resolvePrincipal`(Human→OPERATOR) der **einzige** Cookie-Chokepoint ist, erben ALLE ~20 /api + WS + Terminal Kanten den Faktor als **EIGENSCHAFT** (auch die 21., künftige). Das hebt die Latte fürs ERLANGEN der Session (Passkey statt Passwort) und schliesst §9.1s AAL1-Loch. Der Fix-Locus ist richtig.

**★ ABER (Reviewer, code-belegt): das schliesst die per-Hub-Anti-Seizure NICHT — es verengt sie nur.** Die in der Rev.3-Erstfassung behauptete Bindung via `operatorAuthChallenge(h, hubId, nonce)` ist **browser-unerreichbar**: die Challenge ist über die Noise-`h` definiert (`:core OperatorAuthChallenge.kt`), ALLE Aufrufer liegen auf dem Tunnel-Pfad (`app/shared/net/hub/operator/*`, `OperatorAuth:103`), **kein Browser-Aufrufer** — der Browser-`/api`-Pfad baut keinen Noise-Tunnel → kein `h` → kann die Challenge nicht erzeugen. Was der Chokepoint-AAL2 liefert ist **Session-Level** → ein **Bearer-Cookie**, NICHT der per-Verbindungs-, per-Hub-, transport-unfälschbare Device-PoP, den der Tunnel der Token-Achse gibt. Auf einem **geteilten Origin für N Hubs** (Subdomain-per-Hub verworfen → etwas frontet die N Hubs, nach Q3 plausibel das eigene Relay als Reverse-Proxy) sieht der Origin-TLS-Terminator (Relay) das AAL2-Cookie und kann es an JEDEN ko-gehosteten Hub **replayen** → Seizure. **AAL2-at-Login ≠ per-Hub-relay-unfälschbar.** ⟹ **Der „AND überlebt geteilten Origin"-Claim ist GESTRICHEN.** (Dieselbe Krankheit wie der Cold-Anchor: ein Faktor auf der FALSCHEN Ebene — Origin/Login statt Hub/Verbindung.)

### 9.4 (d) Browser-Posture — local-direct adäquat; browser-remote HEUTE UNMÖGLICH (stärker als fail-closed)

Die §3-Invariante verlangt „hub-lokal, per Verbindung bewiesen". Session-AAL2 ist „IdP/Origin, per Login" — nicht hub-lokal. Daher abgestuft:

- **Browser LOCAL-DIRECT** (Auftraggeber-Box, same-origin, kein Relay im Pfad): das AAL2-Cookie ist nicht relay-replaybar (kein Proxy terminiert den TLS zwischen Browser und Hub). ⟹ Chokepoint-AAL2 (§9.3) ist **adäquat** — der MVP-Browser-Operator-Pfad.
- **Browser REMOTE** (relay-vermittelt): **HEUTE ARCHITEKTONISCH UNMÖGLICH, nicht bloss fail-closed** — das Relay ist ein **Noise-Frame-Rendezvous, KEIN HTTP-Reverse-Proxy** (`RelayServer` Noise-only; ein Browser kann kein Noise). Ein Browser erreicht einen Remote-Hub übers Relay gar nicht; er landet immer nur auf dem lokalen public-Connector. Remote-Operator geht über die **CMP/Token-Achse** (Noise-Tunnel + `Rr3TunnelGate` Device-PoP, per-Hub, relay-unfälschbar).
- **★ BRUCHBEDINGUNG (explizit):** die Unmöglichkeit hält NUR, solange das Relay ein Noise-Rendezvous bleibt. Führt man je einen **Relay-als-HTTP-Reverse-Proxy** ein (um N Hub-Web-UIs auf EINEM Origin zu servieren), wird browser-remote real, das Relay terminiert TLS → sieht+replayt das Cookie → Seizure zurück. **MVP-Grenze: Relay bleibt Noise-Rendezvous; kein HTTP-Proxying des Browsers.** §9.6 ist Voraussetzung, BEVOR je ein shared-origin-Proxy-Relay shippt.

### 9.5 ★ Warum die local/remote-Grenze NICHT-FÄLSCHBAR ist (code-belegt — Eigenschaft, nicht Behauptung)

„local-direct adäquat / remote via Tunnel-Device-PoP" trägt nur, wenn die Klassifikation server-seitig unfälschbar ist (sonst kommt Seizure verkleidet zurück). Drei Invarianten, alle code-belegt:

1. **Tunnel = die EINZIGE Relay→`/api`-Brücke, Device-PoP-Pflicht:** `LoopbackBridge` (Noise-terminiert, `Rr3TunnelGate`-gegatet) ist der einzige Relay→`/api`-Pfad und bridget auf `127.0.0.1` (`LoopbackBridge:37/54`, `MuxBridge:32`, `RelayConnector` — alle `127.0.0.1`). Ein bare Cookie kann den Tunnel nicht traversieren (kein Device-PoP) ⟹ **ein bare-cookie-OPERATOR ist beweisbar local-direct.**
2. **`/api` loopback-bind (RUNTIME-fail-closed, NICHT Build-Default — Reviewer-Schärfung):** `Application.kt:52` = EIN `embeddedServer(Netty)`, ZWEI Connectors: `:56` public `host=config.hub.host` (default 127.0.0.1), `:57` tunnel-scoped `"127.0.0.1"` hardcoded. **★ `config.hub.host` ist ein RUNTIME-Wert** (`HubConfig.host`, env `HUB_HOST`; `PlatformConfig.kt:120`-KDoc dokumentiert Off-Loopback als Operator-Opt-in) — ein Build-Zeit-Default-Check pinnt nur den loopback-Default, stoppt KEINE Runtime-Override. Ein Deploy `HUB_HOST=0.0.0.0` bräche §9.4/§9.5 **still** (Default-Test bleibt grün) = die **Default-sicher≠Deploy-sicher / Write-Seam-vs-Enforcement**-Lücke. **FIX (Präzedenz `LoopbackBridge` T3): RUNTIME fail-closed — ist `config.hub.host` nicht-loopback, wird die Browser-AAL2-OPERATOR-Posture DEAKTIVIERT** (Operator geht dann Tunnel/Token-Achse). Das **RECONCILET das Off-Loopback-Opt-in** (bleibt für Agenten/LAN) mit §9.5 (Browser-Operator NUR bei loopback) — „gaten statt entfernen", statt Boot zu verweigern (das risse den legitimen off-loopback-Nutzen mit). **★ Die „ist loopback?"-Prüfung MUSS `InetAddress.getByName(config.hub.host).isLoopbackAddress` spiegeln (wie T3), NICHT String-`=="127.0.0.1"`** — sonst wird `"localhost"` (löst loopback, IST es) fälschlich disabled ODER eine nicht-loopback-Schreibweise durchgelassen (EINE Wahrheit über loopback, keine 2. driftende Definition). **Distinkt von CYP-702-§4.3** (RELAY-Bind `CYPPIE_RELAY_HOST`, Noise — exponiert `/api` NICHT; entwertet §9.5 nicht).
3. **Kein dritter Ingress:** EIN `embeddedServer` (2 loopback-Connectors); alle anderen `.start()`s = interne Worker (Scanner/Warden/…), KEINE HTTP-Listener; MCP = Route am selben Server; Relay separat (Noise). Build-Zeit-Meta-Test (wie Zahn 8): „genau diese 2 Connectors, beide loopback; kein bare-cookie-fähiger Nebeneingang zu `/api`".

⟹ **§9.5 belegt den nicht-fälschbaren fail-closed als EIGENSCHAFT** (Tunnel=einzige-Brücke ∧ `/api`-loopback ∧ kein-3.-Ingress). Fällt eine der drei, kippt die Klassifikation → Seizure verkleidet → daher als Invarianten geführt, nicht als Annahme.

### 9.6 Benannte Zukunft: browser-remote-multi-hub = PER-HUB-ORIGINS (NICHT das eine ambiente Cookie)

**★ Umkehr (Reviewer): die „ein ambientes same-origin-Cookie über N Hubs"-Prämisse IST die Verwundbarkeit**, nicht etwas zu Bewahrendes — dieses eine Cookie lässt EINE Session ALLE ko-gehosteten Hubs autorisieren = der Cross-Hub-Replay/Seizure-Vektor. **N5 (`Operator@A ⇏ Operator@B`) sagt gerade, dass die Rolle NICHT reisen darf** → Re-Auth-pro-Hub ist das KORREKTE Verhalten, keine „Kost".

**Mechanismus (gratis, browser-nativ):** **per-Hub-Origins** → Same-Origin-Policy + Cookie-Origin-Scoping verhindern, dass ein Cookie für Hub A je an Hub B gesendet wird — Cross-Hub-Replay vom Browser unterbunden, ohne Server-Krypto, ohne per-Aktion-Ceremony. Löst zusätzlich §9.4s Bruchbedingung auf (keine geteilte Origin → kein fronting-Proxy → kein Relay-TLS-Seizure).

**★ BAU-VORSCHRIFT (nicht bloss „nimm Subdomains" — der stille Trap):** Isolation braucht distinkte **HOSTNAMEN, NICHT Ports** — **Cookies ignorieren den Port** (`localhost:A` teilt das Cookie mit `localhost:B`) → per-Port isoliert NICHT. Per-Hub-**Host**: Subdomain (`hub-a.…`/`hub-b.…`) ODER distinkte Loopback-IP/-Alias (`127.0.0.1` vs `127.0.0.2` = getrennte Cookie-Jars). **UND host-only Cookies** (KEIN breites `Domain=.parent` — re-teilt still über Subdomains). Falsch (per-Port / breites `Domain`) = **stilles Re-Sharing = Seizure zurück, ohne Warnung.**

**Alternative (schwerer):** per-Hub-per-Aktion WebAuthn-Assertion über eine HUB-ausgestellte Challenge (früher „Pfad 1") — transport-unfälschbar, aber per-Request-Ceremony. Per-Hub-Origins sind der leichtere, browser-native Verschluss + die empfohlene Zukunft.

**⟹ (d)-Auflösung final:** Browser-Operator = **local-direct adäquat (AAL2), remote HEUTE unmöglich (Relay=Noise)**; browser-remote-multi-hub = benannte Zukunft via **per-Hub-Origins (distinkte Hostnamen + host-only Cookies)**, NICHT das eine ambiente Cookie. Kein Universal-„AND überlebt geteilten Origin" — die geteilte Origin ist gerade das, was vermieden wird. Folge: `connect-src`/Origin eng, Relay-verankert.

---

## 10. Weichen-Status (alle Auftraggeber-entschieden — keine offenen mehr für den Bau)

- **(a) Widerruf-Wirksamkeit → a2 AKTIV** (Q2). Kein a1-MVP mehr.
- **(b) Aussteller-Reichweite → eigenes-Relay jetzt** (Q3); (j) portable-Menschen-Identität aufgeschoben.
- **(d) Browser-Posture → abgestuft (§9.4–9.6):** local-direct adäquat (AAL2) · remote HEUTE unmöglich (Relay=Noise) · browser-remote-multi-hub-Zukunft = per-Hub-Origins (distinkte Hostnamen + host-only Cookies, simpler+sicherer als per-Aktion-WebAuthn). „AND überlebt Origin"-Claim gestrichen.
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
8. **★★ Browser-AAL2-Chokepoint-EIGENSCHAFT + Nicht-Fälschbarkeit der local/remote-Grenze** (§9.3/§9.5): (a) eine synthetische **Nicht-AAL2**-Operator-Cookie-Session wird an JEDER operator-autoritativen Cookie-Kante abgewiesen — **strukturell** via Route-Tabellen-Meta-Test (jede operator-Tier-Route löst über `authenticatedApi`/`resolvePrincipal` auf; KEINE Hand-Liste — sonst ist die Vollständigkeit selbst die Aufrufer-Krankheit). (b) **Loopback-Bind RUNTIME-Zahn (nicht Build-Default):** Boot/Init mit `HUB_HOST=0.0.0.0` → Browser-AAL2-OPERATOR-Posture DISABLED (fail-closed), via `InetAddress.getByName(host).isLoopbackAddress` (T3-Auflösung, NICHT String-`==127.0.0.1`); pinnt BEIDE — `host="localhost"` → ENABLED (löst loopback) UND `host="0.0.0.0"` → DISABLED — nicht nur den `127.0.0.1`-Fall. (c) **Kein-3.-Ingress-Meta-Test:** genau die 2 loopback-Connectors, kein bare-cookie-fähiger Nebeneingang zu `/api`. Non-vakuos: Mutation „AAL1 erlaubt" / „off-loopback → Browser-OPERATOR trotzdem an" / „String-`==127.0.0.1` statt InetAddress-Auflösung" / „3. HTTP-Connector" rötet.
9. **Per-Hub-Isolations-Zahn (benannte Zukunft §9.6, WENN browser-remote-multi-hub gebaut wird):** ein Operator-Cookie für Hub A wird vom Browser NIE an Hub B gesendet — via distinkte HOSTNAMEN (nicht Ports; Cookies ignorieren Ports) + host-only Cookies (kein `Domain=.parent`). Non-vakuos: Mutation „per-Port statt per-Host" / „breites `Domain`" → Cross-Hub-Cookie-Leak rötet. **(Ersetzt das alte tunnel-`h`-`operatorAuthChallenge`-Differential-Paar der Rev.3-Erstfassung, das browser-unerreichbar war — Reviewer-Fund.)**
10. **Cap gegen auth-Sessions** (§6): Relay-Junk-Rendezvous erschöpft die Cap nicht.
11. **Wahrheitstabellen-Vollständigkeit** (§8): jede `(…, enrolled?)`-Zeile in einem definierten nicht-grün-außer-verdient Zustand.

---

## 12. Non-Goals / aufgeschoben

Kein server-erzwungenes one-active-Gate · keine Cross-Operator-Föderation · keine portable-Menschen-Identität (j) · **kein Bau vor PL-Gegenlesen + Auftraggeber-Bau-GO.**

---

## 13. Provenance

Objekt-Erhebung (`7dd39b23`) + Reviewer-Verifikation: `CpJwtMinter.kt`, `CpJwtVerifier.kt`, `IdentityToken.kt:64-82`, `Rr3TunnelGate.kt:118-161`, `RemoteRelayWiring.kt:150-176`, `OperatorAssertionVerifier.kt:90/93/125/149`, `OobFingerprintConfirmer`/`OobFingerprintConfirmScreen` (CYP-482), **`Principal.kt:95/105/112/121/142/157/169/190` (resolvePrincipal-Chokepoint, kein AAL)**, **`RoleStore.kt:70-71/124`**, **`Auth.kt:33` (isOperator-Token-Achse)**, **`AgentSocket.kt:65/68`, `TerminalAccess.kt:173/177`**, **die ~20 `authenticatedApi(OPERATOR)`-Routen**, `Csrf.kt` (same-origin-Cookie nicht device-bound), `PinnedHubStore.kt`, `RelayServer.kt`, `RendezvousRelay.kt`.
Auftraggeber-Weichen 2026-07-21: Q1-Seat / Q2-a2-aktiv / Q3-eigenes-Relay. PL-Punkte: §9 Browser-Anti-Seizure per-Kante-Exhaustivität (PL-0067). Honesty-Invariante ⇔ ratifizierte §4a-Regel `unknown ≠ all-clear`.
