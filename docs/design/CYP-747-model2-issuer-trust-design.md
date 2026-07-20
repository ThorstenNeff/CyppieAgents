# CYP-747 — Modell 2: Aussteller-Vertrauen (Cross-Hub-Operator-Identität) — Trust-Modell-Design

> Status: **Design komplett (Rev. 2, adversarial gehärtet) — zum Gegenlesen durch den Project Lead. KEIN Bau vor Freigabe.**
> Autor: PO (Team-1, Coordinator). Grundlage: eigene read-only Objekt-Erhebung (`develop b5695165`) + Team-2-Client-Needs (UIUX2 `docs/design/model2-multihub-client-needs.md` / CYP-748 `5bc1835c`; Assist2-Grounding) + **Reviewer-Second-Opinion (PO-Assistent, Code-verifiziert gegen `a2192e06`)** + PL-Review-Punkte (Browser-Anti-Seizure + 4. Weiche).
> Verwandt: CYP-427 Ph2 (Relay-CODE-Lineage: 501/506/509/536), CYP-702 (deploybare-Relay-Paketierung, To-Do), [[architecture-two-principals-no-foreigners]].

Dieses Dokument beschreibt **was** das Trust-Modell ist und **welche Zähne** der spätere Bau tragen muss. Kein Implementierungsplan.

---

## 0. Scope & gelockte Entscheidungs-Anker

- **Modell 2 = EINE Operator-Identität über mehrere Hubs DESSELBEN Operators**, via einen **Aussteller (= Relay)**. **NICHT** Cross-Operator-Föderation / Multi-Tenant / Delegation. PL + Auftraggeber bestätigt.
- **Q1 = SEAT (n), entschieden (PL):** `pinnedOperatorId` + Device-PoP **IST** der Seat; über die Hubs generalisiert — **kein Menschen-Record**. „Portable Menschen-Identität" (j) **hinter Unter-Weiche (b) aufgeschoben**.
- **one-active-Hub = ENTSCHIEDEN (PL §4a):** reine **Client-Konvention**, NICHT server-erzwungen; Security davon **vollständig entkoppelt** (§6). Server-Gate = benannt-aufgeschoben, kein Weichen-Punkt.
- **Offene Weichen (PL → Auftraggeber; NICHT solo):**
  - **(a) Widerruf:** kurzlebig-nur vs. aktiv/beobachtbar — **als Widerrufs-WIRKSAMKEITS-Entscheidung**, nicht UX (§7).
  - **(b) Aussteller-Reichweite:** eigenes-Relay jetzt vs. BYOAuth-extern später hinter derselben Abstraktion.
  - **(j, unter b):** portable Menschen-/Prinzipal-Identität. Heute nein (Seat).
  - **(d, NEU — PL):** Browser ohne hub-lokalen zweiten Faktor = **benannte Degradation** vs. **fail-CLOSED** (kein Faktor → kein Browser-Operator-Zugang). PL-Lean: fail-closed. Auftraggeber-Posture (§9/§10).

---

## 1. Ausgangslage — Modell 2 ist ~70% real, kein Greenfield

Aus Objekt-Erhebung + Assist2-Grounding + Reviewer-Verifikation (jede Behauptung gegen den Code):

- **Issuer-Signatur-Primitive existiert** — Aussteller heute = **Control-Plane (CP)**, nicht Relay: CP mintet Ed25519-JWS (`CpJwtMinter.kt`, alg-pinned `EdDSA`; `iss`/`aud==hubId`/`sub==operatorId`/`nbf`/`exp`/`cb==base64url(SHA-256(h‖hubId))`). Hub verifiziert gegen **gepinnten Aussteller-Pubkey per `kid`** (`CpJwtVerifier.kt` + `IdentityToken.kt` `TokenPredicates.PHASE1`).
- **CHOKEPOINT `Rr3TunnelGate.kt`:** nach Noise-Handshake, vor jedem HTTP-Byte: **Issuer-JWS-Signatur ∧ Operator-Device-PoP** (`Ed25519.sign(deviceKey, h ‖ hubId ‖ nonce ‖ "operator-auth")`), beide gegen live `h`. **Reviewer-verifiziert:** `:145` liest Anker aus **hub-lokalem** `deviceStore.enrolled()`; `:157` prüft PoP gegen diesen Anker — nichts aus dem Token. Die JWS trägt **keinen** Device-Key (`IdentityToken:64-82`). ⟹ Aussteller kann via JWS **keinen** Device injizieren.
- **ANKER-SEAM:** `RemoteRelayWiring.kt:163-176` — heute **single** `expectedIssuer` / `cpPublicKey(kid)` / `pinnedOperatorId`, env-gefüttert, fail-closed INERT.
- **Relay heute = dummer Transport-Rendezvous** (`RelayServer.kt`, `RendezvousRelay.kt`), NULL Identität.
- **Trust-Set ist bereits Multi-Hub:** `PinnedHubStore` = `hubId`-gekeyte Map; durabler Pin-Set hält heute viele Hubs, nur die **aktive Verbindung** ist singulär. **N1s „Liste"-Hälfte ist live.**
- **Owner-Scoping = Default:** `GET /api/cp/hubs` (CYP-530) nur Hubs mit `ownerId == cpOperatorId`. Rule-2 ist Default.
- **Zwei Client-Identitäten:** CMP/Remote präsentiert per-Hub `cpJwt + Device-PoP` über Noise (**erfüllt N2**). web-ts/Browser nutzt ambientes **same-origin**-Cookie — **spannt nicht über Origins** ⟹ die eigentliche N2-Lücke (§9).

---

## 2. Das Drei-Schritte-Modell

| Schritt | Was | Wo (Seam) | Risiko |
|---|---|---|---|
| **1. Aussteller CP→Relay repointen** | `expectedIssuer` + `cpPublicKey(kid)` → **Relay-Signaturschlüssel** | `RemoteRelayWiring.kt:169-171` | §3-Invariante |
| **2. Verifier-Anker generalisieren** | single `pinnedOperatorId` → „Operator über owned Hub-Set", **hub-seitig** | `IdentityToken.kt:75` | §3-Invariante am **selben Locus** |
| **3. Issuer-Level-Widerruf** | NET-NEU im **Hub↔Relay-Protokoll** | Hub↔Relay + ggf. Cross-Hub-Registry (C3) | Weiche (a), §7 |

Cross-Hub-**Identität** issuer-seitig (`cpOperatorId`) weitgehend real; Rest-Gap = Repoint (1) + hub-seitige Verifier-Generalisierung (2) + Widerruf (3) + **First-Enroll/Browser-Anker (§3/§9)**.

---

## 3. ★ Die tragende Invariante — als oberflächen-agnostische EIGENSCHAFT

> **INVARIANTE (property, nicht Flächen-Aufzählung):** *Jede Trust-Fläche verlangt einen **hub-lokal verankerten zweiten Faktor** (Device-PoP gegen den am Hub gepinnten Anker). Wo ein solcher Anker existiert („**gewärmter Hub**"), ruht das Vertrauen auf ihm, **nicht** auf dem Aussteller/Relay. Wo **keiner** existiert, ruht das Vertrauen zwangsläufig auf dem Aussteller — und das ist erlaubt **nur** bis zum **per-Hub-OOB-Warming**, nie als Dauerzustand.*

Formuliert als Eigenschaft (Anker-vorhanden?), damit keine **künftige dritte Client-Art** ohne Anker das Loch wieder aufreißt — sonst erbt die Härtung die per-Aufrufer-statt-Chokepoint-Krankheit eine Ebene höher (Reviewer ①).

**Steady-State (gewärmter Hub) — GO, code-belegt:** Relay-als-Aussteller bleibt **AND-gekoppelt ans Operator-Device-PoP** (`Rr3TunnelGate:157` gegen hub-lokalen Anker; JWS ohne Device-Key). Schritt 2 (`SUBJECT_PIN:75`) fasst das PoP-Gate strukturell nicht an. Kompromittiertes Relay kann den Operator **nicht** imitieren.

**Zwei ungewärmte Flächen (Reviewer-Fund, code-belegt) — der Gap:**
1. **Kalter owned-Hub, First-Enroll (Maschine):** `Rr3TunnelGate:145-151` leerer Anker → `firstEnrollThenGrant` verifiziert PoP gegen den **präsentierten** Key (`:179/:241`) und ankert ihn. ⟹ Relay-JWS ist das **EINZIGE** Operator-Gate; ein kompromittiertes Relay mintet JWS an einen kalten Hub, präsentiert seinen **eigenen** Key, wird Anker.
2. **Browser (immer):** kein Device-PoP existiert je — dieselbe Krankheit permanent (§9).

**Akzeptanzkriterium (§11-1) — DREI Arme, non-vakuöses Differential-Paar PRO Hub, identisch außer PoP:**
```
(a) (Issuer-JWS OHNE Device-PoP,  gewärmter Hub_k)                      → REJECT
(b) (Issuer-JWS MIT gültigem PoP über GEANKERTEN Key, gewärmter Hub_k)  → ACCEPT   [Positiv-Kontrolle: Reject aus (a) ist PoP-attribuierbar]
(c) (Relay-JWS, LEERER Anker an Hub_k, PoP über ANGREIFER-Key)          → NICHT still enrollen/granten → needs-OOB-warming
```
Arm (a)+(b) allein sind **necessary-but-not-sufficient** (Reviewer): sie beweisen „AND bleibt AND" nur im Steady State; Arm (c) schließt den First-Enroll-Seizure. **Richtung:** C1 (§7) explizit auf **First-Enroll (kalt) ausdehnen** — per-Hub-OOB beim Erstkontakt, auch Maschine — analog zum Recovery-Re-TOFU. Frictionless-cold-enroll = stiller Bulk-Auto-Accept über N Hubs.

---

## 4. Token-/Proof-Format (Aussteller-signiert)

`CpJwt`-Form beibehalten (kein neues Krypto), nur der Aussteller wechselt: Ed25519-JWS, header hart `alg:EdDSA`; `iss`=Relay-Aussteller; `aud==hubId` (N2-Grenze); `sub==operatorId` (Seat, Aussteller-gesetzt — Client wählt `sub` nie); `nbf`/`exp` **kurzlebig** (Minuten, §7); `cb==base64url(SHA-256(h‖hubId))`. `kid`→Aussteller-Pubkey (unbekannt → reject). Device-PoP unverändert (der Anti-Seizure-Faktor, §3).

---

## 5. Trust-Anker (Schritt 1+2) & die zwei Vertrauens-Kanten (C2)

**Repoint (1):** `expectedIssuer`+`cpPublicKey(kid)` → Relay-Aussteller-Key (`RemoteRelayWiring.kt:169-171`), fail-closed INERT bleibt.
**Generalisierung (2):** `pinnedOperatorId` → „vom Aussteller behauptete Operator-Identität", `sub` weiter an den Seat, `aud` weiter an **diesen** Hub gebunden — **ohne** die per-Hub-`AND(JWS, Device-PoP)` zu schwächen (§3).

**★ C2 — Konnektierbarkeit = AND aus ZWEI unabhängigen Kanten, fail-closed:**
1. **Operator besitzt Hub B** (CP-Owner-Check, existiert).
2. **Hub B vertraut dem Aussteller/Relay** (separate Hub↔Relay-Kante, NEU).

Ein owned Hub kann den Aussteller **noch nicht** vertrauen → **distinkter Zustand `owned-but-issuer-not-trusted`** (nicht connectable, nicht generisch, **nie stiller Hang**).

**★ Zwei Nähte (Reviewer):**
- **(a) N4a-Distinktheit ist CLIENT-seitig, nicht Server-Oracle:** der Tunnel-Reject ist absichtlich **uniform/no-oracle** (`Rr3TunnelGate:273` — gleich für bad-h/malformed/bad-JWS/bad-PoP). Also muss der Client `Netz-unerreichbar / Trust-Reject / Widerruf / DeviceNotEnrolled` aus **eigenem Wissen** ableiten (owned-Set aus `/api/cp/hubs`, lokaler Enroll-Zustand, Verbindungs-Ausgang) — **nicht** aus einem Server-Reject-Code (sonst bricht no-oracle).
- **(b) Kein INERT-Verschwinden:** die Wiring failt an jeder Lücke mit `?: InertRelayConnector` (`RemoteRelayWiring:150-158`) → **kein** Connector, kein Frame, keine typed state = **Abwesenheit statt benanntem Zustand**. C2 muss einen **beobachtbaren typisierten** Zustand emittieren, nicht in INERT-Stille verschwinden.

---

## 6. Verifikation (Rr3-Gate, per Hub, nebenläufigkeits-sicher)

Der Gate verifiziert **pro Hub, unabhängig:** Issuer-JWS (Relay-signiert, `aud==hubId`, `cb`, `exp`) ∧ Device-PoP über live `h` gegen den hub-lokalen Anker.

**Nebenläufigkeits-Sicherheit by construction (Reviewer GO):** per-Hub-instanziierter Gate, `firstEnrollLock` per-Hub (`:66-67`); per-Hub-disjunktes Keying (`rendezvousId = base64url(SHA-256(hubId‖epoch))`). Sicherheit ruht auf per-Hub-AND, **nie** auf one-active.

**★ Cap-24-Naht (Reviewer, Liveness/DoS — kein Security-Loch, vor Bau klären):** die per-Hub-Cap 24 **zählt authentifizierte Sessions**, **nicht** Relay-vermittelte Rendezvous-Versuche — sonst kann ein **böswilliges Relay** die Cap mit Junk erschöpfen und den Operator **aussperren** (DoS). Design-Festlegung: **Cap gegen authentifizierte Sessions.**

---

## 7. Widerruf (Schritt 3 = NET-NEU) — Weiche (a) als WIRKSAMKEITS-Entscheidung

**Ist-Stand (C3):** Widerruf heute **lazy, ≤TTL, kein Push, keine Registry**; nichts zählt/killt die **anderen** live Hub-Sessions des Operators.

**★ Reframe (Reviewer):** Weiche (a) ist **keine UX-Präferenz, sondern eine Widerrufs-WIRKSAMKEIT:** im ≤TTL-Fenster **verifiziert und VERBINDET ein widerrufenes Credential weiter** (JWS gültig bis `exp`, `NOT_EXPIRED` hält). a1 heißt „Widerruf ist für ≤TTL **wirkungslos** — null Hebel bei Kompromittierung im Fenster."

- **(a1) kurzlebig-nur:** kurze TTL + Re-Mint; Widerruf = Aussteller stellt nicht mehr aus. Kein neues Registry. **STALE ≤TTL; im Fenster keine Eindämmung.**
- **(a2) aktiv:** Issuer-Widerruf im Hub↔Relay-Protokoll + Cross-Hub-Session-Registry (C3-Baukosten). Push-STALE/REJECTED, **ein Hebel** bei Kompromittierung.

**Empfehlung (Trade-off, offen für PL/Auftraggeber):** a1-MVP mit **bewusst enger TTL** (Fenster = TTL, klein per Design) + a2 als benannte Folge-Ausbaustufe — **außer** das Credential-Kompromittierungs-Bedrohungsmodell wird jetzt hoch gewertet, dann a2 sofort. **Dem Auftraggeber als Security-Tradeoff vorlegen, nicht als Komfort.**

**★ C1 — Device-Recovery = Re-TOFU = Bulk-MITM-Fenster:** Session-Refresh (gleiches Device) MUSS per-Hub-Pins **erhalten** (nur Credentials re-minten); **echte** Neu-Device-Recovery re-walkt TOFU **OOB pro Hub**, nie stiller Bulk-Auto-Accept. **Gilt symmetrisch für First-Enroll (§3-Arm c).**

---

## 8. Client-Needs → Vertrags-Pflichten (N1–N5, C1–C3) + Wahrheitstabelle

- **N1:** stabile `hubId` + owner-scoped Set (`/api/cp/hubs`). Client keyt je `hubId`.
- **N2:** per-Hub `aud`+`cb`+PoP; Empfänger-Hub lehnt wrong-audience strukturell ab (§11-2). Browser = Delta (§9).
- **N3 (fail-closed):** distinkte Zustände **UNKNOWN / PENDING / TRUSTED / REJECTED / STALE / `owned-but-issuer-not-trusted` (C2) / `needs-OOB-warming` (§3-c)**, nie auf „grün" kollabiert, Default **NICHT-vertraut** ([[safe-but-silent-default-needs-own-state]]).
- **N4:** client-seitig unterscheidbare Ursachen (Netz / Trust-Reject / Widerruf / `DeviceNotEnrolled`) — **client-abgeleitet**, nicht Server-Oracle (§5a). **§4a-Defekt fixen:** `DeviceNotEnrolled` heute in `remote_pop_rejected` kollabiert → distinkter Enroll-Zustand. **N4b:** per-Hub-Fehler an Hub B reißt Hub-A **nicht** mit (zentrale Session-Expiry legitim global).
- **N5:** Rolle/Tier je Hub aus dessen whoami, fail-closed least-privilege (`Operator@A ⇏ Operator@B`); kein fake-instant „verbunden".

**★ Vollständigkeits-Pflicht (Anti-Vakuität, Reviewer ②):** Der Bau MUSS die **volle Wahrheitstabelle** über die Achsen `(owned?, issuer-trusted?, jws-valid?, pop-valid?, expired?, ENROLLED?)` enumerieren — **`enrolled?` ist eine eigene Spalte**, kein Detail — und behaupten, dass **jede** Zeile in einen definierten, **nicht-grün-außer-verdient** Zustand fällt. Die Bug-Zeile EXPLIZIT:
```
owned=ja ∧ issuer-trusted=ja ∧ jws=gültig ∧ pop=gültig-über-PRÄSENTIERTEN-Key ∧ enrolled=NEIN(leerer Anker)
        → needs-OOB-warming   (NICHT TRUSTED/connectable — heute der stille Grant Rr3TunnelGate:145-151)
```
Eine Enumeration ohne die `enrolled=NEIN`-Achse **re-versteckt** den Fund.

---

## 9. Der Browser-Pfad-Delta (web-ts) — hub-lokaler zweiter Faktor Pflicht

Der Maschinen-Pfad erfüllt N2; der Browser nutzt ein ambientes same-origin-Cookie, das **nicht über Origins spannt**. Der Trust-Kontrakt muss dem Browser ein **explizites, per-Hub, audience-gebundenes, issuer-signiertes** Credential geben — **UND** (PL + Reviewer) einen **hub-lokal verankerten zweiten Faktor**, der das Device-PoP **ersetzt**:

- **Das Cookie ist EXPLIZIT NICHT der Anti-Seizure-Faktor** (nicht device-gebunden, relay/origin-kontrolliert). Ein issuer-signiertes Credential **allein** ist per Konstruktion Issuer-JWS-only = §3-Seizure permanent.
- **Zweiter Faktor:** **per-Hub-Passkey/WebAuthn** (der bestehende `auth/operator/OperatorAssertion*.kt`-FIDO2-Pfad) mit **hub-lokal verankertem** Credential — dasselbe Differential-Paar wie CMP: `(JWS+Cookie-only → reject) ∧ (JWS+gültigem Browser-PoP über hub-lokalen Anker → accept)`, pro Hub.
- **Ohne** hub-lokalen Browser-Faktor ist der Browser-Teil **nicht anti-seizure** → **Weiche (d):** benannte Degradation (dokumentierte Posture, kein stiller Kollaps) **vs. fail-CLOSED** (kein Faktor → kein Browser-Operator-Zugang; PL-Lean).
- **Folge (Team-1/Security, nicht Client):** erlaubte `connect-src`/Origin-Menge über `'self'` hinaus weiten — **Relay-verankert**, eng.

---

## 10. Offene Weichen (PL → Auftraggeber; markiert, nicht solo)

- **(a) Widerruf-WIRKSAMKEIT:** a1 (kurzlebig, enge TTL, ≤TTL wirkungslos) vs. a2 (aktiv/Registry, C3-Baukosten). Empfehlung a1-MVP + a2-benannt; **als Security-Tradeoff**.
- **(b) Aussteller-Reichweite:** eigenes-Relay jetzt vs. BYOAuth-extern später. **(j)** portable-Menschen-Identität unter (b).
- **(d, NEU) Browser-Posture:** benannte Degradation vs. fail-CLOSED (PL-Lean fail-closed).

---

## 11. Akzeptanz-Zähne, die der Bau tragen MUSS (non-vakuös, mutations-bewiesen)

1. **Anti-Seizure-Differential-TRIPEL pro Hub** (§3): (a) JWS-only→reject ∧ (b) JWS+PoP-über-Anker→accept ∧ **(c) JWS+leerer-Anker+Angreifer-PoP→needs-OOB-warming (NICHT grant)**.
2. **Wrong-Audience-struktureller-Reject** (N2): Hub-A-Credential an Hub B → `aud≠hubId` ∧ `cb≠SHA256(h‖hubB)` ∧ PoP-über-fremdem-`h`.
3. **Per-Hub-Isolation** (N4b): Trust-/401-Verlust an Hub B **rötet nicht** Hub A.
4. **`owned-but-issuer-not-trusted` distinkt + beobachtbar** (C2): eigener Zustand, **nicht** INERT-Stille (§5b).
5. **`DeviceNotEnrolled` ≠ rejected** (N4a), **client-abgeleitet** (nicht Server-Oracle, §5a).
6. **Nebenläufigkeits-Sicherheit** (§6): per-Hub-AND hält unter parallelen Same-Operator-Sessions.
7. **STALE-Wirksamkeit** (N3/§7): nach Widerruf/Ablauf → beobachtbar STALE/REJECTED (a1: ≤TTL; a2: Push).
8. **Browser-Differential-Paar** (§9): JWS+Cookie-only→reject ∧ JWS+Browser-PoP-über-hub-lokalen-Anker→accept, pro Hub.
9. **Cap gegen auth-Sessions** (§6): Relay-Junk-Rendezvous erschöpft die Cap **nicht** (kein Aussperren).
10. **Wahrheitstabellen-Vollständigkeit** (§8): jede `(…, enrolled?)`-Zeile in einem definierten nicht-grün-außer-verdient Zustand; Bug-Zeile → needs-OOB-warming.

---

## 12. Non-Goals / aufgeschoben

Kein server-erzwungenes one-active-Gate · keine Cross-Operator-Föderation · keine portable-Menschen-Identität (j) bis (b) · **kein Bau vor PL-Gegenlesen**.

---

## 13. Provenance

Objekt-Erhebung (`b5695165`) + Reviewer-Verifikation (`a2192e06`): `CpJwtMinter.kt`, `CpJwtVerifier.kt`, `IdentityToken.kt:64-82`, `Rr3TunnelGate.kt:145-151/157/179/241/273`, `RemoteRelayWiring.kt:150-176`, `RelayServer.kt`, `RendezvousRelay.kt`, `crypto/HubIdentity.kt`, `PinnedHubStore.kt`, `HubDiscoveryRoutes.kt`.
Client-Needs-Spine: UIUX2 `docs/design/model2-multihub-client-needs.md` (CYP-748). Reviewer-Second-Opinion: PO-Assistent (`CYP-747-SecondOpinion`). PL-Punkte: Browser-Anti-Seizure + 4. Weiche (d).
Honesty-Invariante ⇔ ratifizierte §4a-Regel `unknown ≠ all-clear`.
