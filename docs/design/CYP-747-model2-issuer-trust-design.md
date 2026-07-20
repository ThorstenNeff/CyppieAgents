# CYP-747 — Modell 2: Aussteller-Vertrauen (Cross-Hub-Operator-Identität) — Trust-Modell-Design

> Status: **Design komplett, zum Gegenlesen durch den Project Lead — KEIN Bau vor Freigabe.**
> Autor: PO (Team-1, Coordinator). Grundlage: eigene read-only Objekt-Erhebung (`develop b5695165`, Survey) + Team-2-Client-Needs (UIUX2 `docs/design/model2-multihub-client-needs.md` / CYP-748 `5bc1835c`; Assist2-Grounding CYP-747-Kommentar).
> Verwandt: CYP-427 Ph2 (Relay-CODE-Lineage: 501/506/509/536), CYP-702 (deploybare-Relay-Paketierung, To-Do), [[architecture-two-principals-no-foreigners]].

Dieses Dokument beschreibt **was** das Trust-Modell ist und **welche Zähne** der spätere Bau tragen muss. Es ist kein Implementierungsplan; die Reihenfolge/Slices folgen nach PL-Gegenlesen.

---

## 0. Scope & gelockte Entscheidungs-Anker

- **Modell 2 = EINE Operator-Identität über mehrere Hubs DESSELBEN Operators**, via einen **Aussteller (= Relay)**. **NICHT** Cross-Operator-Föderation / Multi-Tenant / Delegation (kollidiert mit „2 Prinzipale, keine Fremden"). PL + Auftraggeber bestätigt.
- **Q1 = SEAT (n), entschieden (PL):** `pinnedOperatorId` + Device-PoP **IST** der Seat; Modell 2 generalisiert diesen Seat über die Hubs — **kein Menschen-Record**. Die „portable Menschen-Identität" (j) ist **hinter Unter-Weiche (b) aufgeschoben**, keine frische Frage.
- **one-active-Hub = ENTSCHIEDEN (PL §4a), reine Client-Konvention, NICHT server-erzwungen.** Security ist von one-active **vollständig entkoppelt** (siehe §6). Ein server-seitiges one-active-Gate ist eine **benannte, aufgeschobene** Option (kein Bau), kein Weichen-Punkt.
- **Drei offene Weichen (PL → Auftraggeber; NICHT solo entschieden):**
  - **(a) Widerruf:** kurzlebig-nur vs. aktiv/beobachtbar (§7).
  - **(b) Aussteller-Reichweite:** eigenes-Relay-Aussteller **jetzt** vs. BYOAuth-extern **später** hinter derselben Abstraktion.
  - **(j, unter b):** ob „eine Operator-Identität über Hubs" später eine **portable Menschen-/Prinzipal-Identität** meint (Aussteller = IdP für einen Menschen). Heute nein (Seat).

---

## 1. Ausgangslage — Modell 2 ist ~70% real, kein Greenfield

Aus der Objekt-Erhebung + Assist2-Grounding, jede Behauptung gegen den Code:

- **Das Issuer-Signatur-Primitive existiert bereits** — nur ist der **Aussteller heute die Control-Plane (CP), nicht das Relay:**
  - MINT: `controlplane/CpJwtMinter.kt` — CP signiert kompaktes **Ed25519-JWS** (alg-pinned `EdDSA`), claims `iss` / `aud==hubId` / `sub==operatorId` / `nbf`/`exp` / `cb==base64url(SHA-256(h‖hubId))`.
  - VERIFY: `auth/CpJwtVerifier.kt` + `auth/IdentityToken.kt` (`TokenPredicates.PHASE1`) — Hub verifiziert die Signatur gegen **einen gepinnten Aussteller-Pubkey per `kid`** + AND-verknüpfte Claim-Prädikate.
  - CHOKEPOINT: `transport/Rr3TunnelGate.kt` — nach dem Noise-Handshake, vor jedem HTTP-Byte: **Issuer-JWS-Signatur ∧ Operator-Device-PoP** (`Ed25519.sign(deviceKey, h ‖ hubId ‖ nonce ‖ "operator-auth")`, CYP-536), beide gegen den **live** Handshake-Hash `h`.
  - ANKER-SEAM (die eine Stelle): `transport/RemoteRelayWiring.kt:163-176` — heute **single** `expectedIssuer` / `cpPublicKey(kid)` / `pinnedOperatorId`, env-gefüttert, fail-closed INERT.
- **Das Relay ist heute ein dummer Transport-Rendezvous** (`relay/RelayServer.kt`, `RendezvousRelay.kt`): paart HUB↔CLIENT auf opaker Rendezvous-ID, forwardet opake Frames, linkt **kein Secret/Crypto**. Noise + Auth laufen end-to-end **über** das Relay.
- **Der Trust-Set ist bereits Multi-Hub:** `PinnedHubStore` ist eine **`hubId`-gekeyte Map** (`pin(hubId, hubStatic)`). Der durable Pin-Set hält **heute viele Hubs**; nur die **aktive Verbindung** ist singulär (`RemoteHubSession`, ein `of(hub)`). ⇒ **N1s „Liste"-Hälfte ist am Objekt schon live.**
- **Owner-Scoping ist Plattform-Default:** `GET /api/cp/hubs` (CYP-530) liefert nur Hubs mit `ownerId == cpOperatorId`, fail-closed auf blank. **Rule-2 (same-operator) ist bereits Default, nichts Hinzuzufügendes.**
- **Zwei Client-Identitäten — und der Split IST die Arbeit:**
  - **CMP / Remote-Pfad:** präsentiert bereits **per-Hub** `cpJwt + Device-PoP` über Noise — **kein ambientes Cross-Hub-Secret** (Noise_NK gibt dem Client keinen statischen Key). **⇒ erfüllt N2 heute.**
  - **web-ts / Browser-Pfad:** ambientes **same-origin** `ory_kratos_session`-Cookie (+ globaler Operator-Bearer) — **spannt strukturell nicht über Origins** (Hub B = anderer Origin). **⇒ das ist die eigentliche N2-Lücke** und der Haupt-Client-Delta von Modell 2 (§9).

---

## 2. Das Drei-Schritte-Modell

| Schritt | Was | Wo (Seam) | Risiko |
|---|---|---|---|
| **1. Aussteller CP→Relay repointen** | `expectedIssuer` + `cpPublicKey(kid)` zeigen auf den **Relay-Signaturschlüssel** statt CP | `RemoteRelayWiring.kt:169-171` | Anti-Seizure-Invariante (§3) |
| **2. Verifier-Anker generalisieren** | single `pinnedOperatorId` → „**die** vom Aussteller behauptete Operator-Identität, gültig über den owner-scoped Hub-Set" — **hub-seitig** | `IdentityToken.kt:75` (Subject-Pin-Prädikat) | Anti-Seizure-Invariante am **selben Locus** (§3) |
| **3. Issuer-Level-Widerruf** | NET-NEU im **Hub↔Relay-Protokoll** (existiert heute nicht) | Hub↔Relay-Protokoll + optional Cross-Hub-Session-Registry (C3) | Unter-Weiche (a), §7 |

**Wichtig:** die Cross-Hub-**Identität** ist issuer-seitig (`cpOperatorId` als CP-set `sub`, ein hub-agnostischer Device-Key) **weitgehend real**. Der Rest-Gap ist der **Repoint (1)** + die **hub-seitige Verifier-Generalisierung (2)** + der **Widerruf (3)** — plus der **Browser-Pfad-Delta (§9)**.

---

## 3. ★ Die tragende Invariante + Akzeptanzkriterium (PL-Gegenlese-Achse)

**Anti-Seizure MUSS Schritt 1 überleben.** Der Aussteller kann die Identität fälschen — das Device-PoP nicht. Das Vertrauen ruht auf dem **Device-PoP**, nicht auf dem Relay. Also: Relay-als-Aussteller bleibt **AND-gekoppelt ans Operator-Device-PoP**, sodass ein **kompromittiertes/böswilliges Relay den Operator NICHT imitieren kann.**

**Schritt 2 sitzt am SELBEN Locus wie die Invariante** (`RemoteRelayWiring`-Anker / `Rr3TunnelGate`). Die Generalisierung `single pinnedOperatorId → Operator-über-Hubs` darf die **Device-PoP-AND-Kopplung pro Hub NICHT lockern** — sonst reißt genau die Generalisierung das Seizure-Loch auf.

**Akzeptanzkriterium — non-vakuöses Differential-Paar, PRO Hub im Operator-Set, identisch in ALLEM außer dem PoP:**

```
(Issuer-JWS OHNE Device-PoP,  Hub_k, aud==hub_k, exp gültig)  → REJECT
(Issuer-JWS MIT gültigem PoP, Hub_k, aud==hub_k, exp gültig)  → ACCEPT
```

Die **Positiv-Kontrolle** (zweite Zeile) isoliert den Reject als **PoP-attribuierbar** — der einzige Delta zwischen beiden Requests ist der PoP, nicht Audience/Expiry/Hub-nicht-im-Set. Nur beide zusammen beweisen „**die AND bleibt AND, degradiert nie zu OR**" nicht-vakuos. Beide Assertions sind Akzeptanzkriterium **pro Hub**.

---

## 4. Token-/Proof-Format (Aussteller-signiert)

Die bestehende `CpJwt`-Form wird **beibehalten** (kein neues Krypto), nur der Aussteller wechselt:

- **Signatur:** Ed25519-JWS, header hart-gepinnt `alg:EdDSA` (keine `alg`-Verhandlung).
- **Claims:** `iss` = **Relay-Aussteller-Identität**; `aud == hubId` (die N2-Grenze); `sub == operatorId` (der Seat, CP/Aussteller-gesetzt — **der Client wählt `sub` nie**); `nbf`/`exp` **kurzlebig** (Minuten, siehe §7); `cb == base64url(SHA-256(h ‖ hubId))` (Channel-Binding an den live Noise-Handshake).
- **`kid` → Aussteller-Pubkey:** der Hub löst den Aussteller-Signaturschlüssel per `kid` auf; unbekannter `kid` → reject.
- **Device-PoP (unverändert):** `Ed25519.sign(deviceKey, h ‖ hubId ‖ nonce ‖ "operator-auth")` — der Anti-Seizure-Faktor, per Hub gegen live `h`.

**Portabilität (N2):** das Proof ist **explizit, je-Ziel-Hub-gebunden** (aud+cb), **kein ambientes Cookie**. Der Empfänger-Hub lehnt ein **wrong-audience**-Proof **strukturell** ab (test-beweisbar, §11).

---

## 5. Trust-Anker-Provisionierung (Schritt 1 + 2) & die zwei Vertrauens-Kanten

**Repoint (1):** `expectedIssuer` + `cpPublicKey(kid)` in `RemoteRelayWiring.kt:169-171` von der CP auf den **Relay-Aussteller-Key** umlegen. Fail-closed INERT bei jeder Lücke bleibt erhalten.

**Generalisierung (2):** `pinnedOperatorId` von einem Einzel-Literal auf „die vom Aussteller behauptete Operator-Identität" heben — **aber** das Subject-Pin-Prädikat bindet `sub` weiter an den **Operator-Seat**, und `aud` bindet weiter an **diesen** Hub. Die Generalisierung heißt: den issuer-signierten Operator an **jedem Hub im owned Set** akzeptieren, **ohne** die per-Hub-`AND(JWS, Device-PoP)` zu schwächen (§3).

**★ C2 — Konnektierbarkeit ist ein AND aus ZWEI unabhängigen Vertrauens-Kanten:**

1. **Operator besitzt Hub B** — CP-Owner-Check (`RegisteredHub.ownerId == operatorId`, `GET /api/cp/hubs`). ✓ existiert.
2. **Hub B vertraut dem Aussteller/Relay**, der die Operator-Credential ausgestellt hat — eine **separate** Hub↔Relay-Vertrauens-Kante (CYP-747, NEU).

Ein Hub, den der Operator **besitzt**, kann den Aussteller **noch nicht vertrauen**. **Konnektierbarkeit = Kante-1 ∧ Kante-2, fail-closed.** ⇒ **neuer, distinkter Zustand** „**owned-but-issuer-not-trusted**" (nicht „connectable", nicht generischer Fehler, nie stiller Hang). N3s Tabelle nahm an, der Hub **bewertet das Operator-Proof** — C2 ist der Hub, der **den Aussteller gar nicht vertraut.**

---

## 6. Verifikation (Rr3-Gate, per Hub, nebenläufigkeits-sicher)

Der `Rr3TunnelGate` verifiziert **pro Hub, unabhängig:** Issuer-JWS (Relay-signiert, `aud==hubId`, `cb`, `exp`) **∧** Device-PoP über live `h`.

**Nebenläufigkeits-Sicherheit by construction (kein Verlass auf one-active):** die CP **erlaubt** one-active, **erzwingt** es nicht — kein Cross-Hub-Session-Registry; per-Hub-disjunktes Keying (`rendezvousId = base64url(SHA-256(hubId ‖ epoch))`, epoch per `hubId`; per-Hub-Cap 24). Parallele Same-Operator-Sessions zu **verschiedenen** Hubs nutzen **disjunkte** Rendezvous-Räume + **unabhängige** Caps → keine Contention. **Jeder Hub verlangt eigenständig `JWS ∧ Device-PoP`, egal wie viele Sessions parallel laufen.** Die Sicherheit ruht daher auf der **per-Hub-AND-Kopplung**, nie auf „nur eine aktive Session".

---

## 7. Widerruf (Schritt 3 = NET-NEU) — Unter-Weiche (a)

**Ist-Stand (C3):** Widerruf ist heute **lazy, ≤TTL, kein Push, keine Registry** (`hubTicket`-TTL „Minuten"; `revokeOperator` droppt den live Tunnel `≤TTL`). Es gibt **nirgends** eine Stelle, die die **anderen** live Hub-Sessions des Operators aufzählt/killt.

**Die Weiche (a) — PL → Auftraggeber, ich entscheide sie NICHT solo:**

- **(a1) kurzlebig-nur:** Verlass auf **kurze TTL (Minuten) + Re-Mint**; Widerruf = der Aussteller **stellt nicht mehr aus**. Kein neues Registry (billig, kein C3-Bau). **STALE** tritt lazy ein (≤TTL) → **stale-lit-Fenster ≤ TTL**.
- **(a2) aktiv/beobachtbar:** Issuer-Level-Widerrufs-Signal **im Hub↔Relay-Protokoll** **+ Cross-Hub-Session-Sicht/Registry** (C3-Baukosten). Push-STALE/REJECTED, **ein Hebel** bei Kompromittierung. UIUX2-Präferenz (vermeidet stale-lit).

**Meine Empfehlung (Trade-off, offen für PL/Auftraggeber):** **a1 als MVP mit bewusst enger TTL** (stale-lit-Fenster = TTL, per Design klein), **a2 (aktiver Push + Registry, C3) als benannte Folge-Ausbaustufe** — außer der Auftraggeber wertet das Kompromittierungs-„ein-Hebel"-Szenario (C3) jetzt hoch, dann a2 sofort. Der **UIUX2-stale-lit-Einwand** ist real, wird aber durch die enge TTL in a1 auf ≤TTL begrenzt (nicht unbegrenzt). Die Entscheidung prägt N3-STALE-Observability + N4-Refresh-Pfad + die C3-Baukosten.

**★ C1 — Device-Recovery = Re-TOFU = Bulk-MITM-Fenster (Modell-2-spezifisch):** ein bloßer **Session-Refresh (gleiches Device)** MUSS die per-Hub-Pins **erhalten** (nur Credentials re-minten, wie zentrale Re-Auth heute); nur eine **echte Neu-Device-Recovery** re-walkt TOFU — und dieser Re-Walk MUSS **OOB-verifiziert pro Hub** bleiben, **nie** stiller Bulk-Auto-Accept über N Hubs.

---

## 8. Client-Needs → Vertrags-Pflichten (N1–N5, C1–C3)

Was der Trust-Kontrakt **exponieren** muss, damit der Client (CMP **und** web-ts) ehrlich rendern kann:

- **N1 (Registry statt Global):** stabile, kollisionsfreie `hubId` + owner-scoped Hub-Set (`GET /api/cp/hubs` ✓). Client keyt State/Endpoint/Credential/Zustand **je `hubId`**.
- **N2 (audience-gebundenes Proof):** per-Hub `aud==hubId`+`cb`+PoP; **Empfänger-Hub lehnt wrong-audience strukturell ab** (§11). Browser-Pfad = benannter Delta (§9).
- **N3 (per-Hub-Vertrauenszustand, fail-closed):** beobachtbares Affirmations-/Ablehnungs-Signal + Widerruf-/Ablauf-Signal; distinkte Zustände **UNKNOWN / PENDING / TRUSTED / REJECTED / STALE** **+ `owned-but-issuer-not-trusted` (C2)**, nie auf „grün" kollabiert, Default **NICHT-vertraut** ([[safe-but-silent-default-needs-own-state]]).
- **N4 (Ursache-ehrlich, hub-isoliert):** maschinen-lesbar unterscheidbare Reject-Codes: **Netz-unerreichbar vs. Trust-Reject vs. Issuer-Widerruf vs. `DeviceNotEnrolled`**. **§4a-Defekt am Objekt fixen:** heute kollabiert `DeviceNotEnrolled` in `remote_pop_rejected` („Vom Hub abgelehnt, bitte neu anmelden") — irreführend (Re-Login hilft nicht, Enroll schon) → distinkter, aktionierbarer Enroll-Zustand. **N4b (Isolation):** per-Hub-Authz/Trust-Fehler an Hub B darf die Hub-A-Sitzung **nicht** mitreißen (heute web-ts global `/api`-401 → whole-app-relogin = Browser-Gap); zentrale Session-Expiry ist legitim global.
- **N5 (aktiver Hub eindeutig, Rolle reist nicht):** Rolle/Tier **je Hub aus dessen whoami**, fail-closed least-privilege (`Operator@A ⇏ Operator@B`); kein fake-instant „verbunden" beim Wechsel.
- **C1/C2/C3:** siehe §5, §7 — je ein distinkter Zustand bzw. eine Baukosten-Eingabe zur Weiche (a).

---

## 9. Der Browser-Pfad-Delta (web-ts) — die eigentliche N2-Arbeit

Der Maschinen-Pfad erfüllt N2 heute; der Browser nutzt ein **ambientes same-origin-Cookie**, das strukturell **nicht über Origins spannt**. Der Trust-Kontrakt muss dem Browser ein **explizites, per-Hub, audience-gebundenes, portables** (issuer-signiertes) Credential geben, das an **jeden** Hub präsentiert wird. Folge (von Team-1/Security zu designen, **nicht** vom Client entschieden): die erlaubte **`connect-src`/Origin-Menge** über `'self'` hinaus weiten — **Relay-verankert**, eng, nicht offen. Hier landet der Großteil der client-sichtbaren Schritt-1/2-Arbeit.

---

## 10. Offene Weichen (PL → Auftraggeber; markiert, nicht solo)

- **(a) Widerruf:** a1 (kurzlebig, enge TTL) vs. a2 (aktiv/Registry, C3-Baukosten). Empfehlung: a1-MVP + a2-benannt-Folge (§7). UIUX2-stale-lit-Einwand notiert.
- **(b) Aussteller-Reichweite:** eigenes-Relay-Aussteller jetzt vs. BYOAuth-extern später hinter derselben Abstraktion. **(j)** portable-Menschen-Identität liegt **unter (b)** (heute Seat/n).

---

## 11. Akzeptanz-Zähne, die der Bau tragen MUSS (non-vakuös, mutations-bewiesen)

1. **Anti-Seizure-Differential-Paar pro Hub** (§3): `(JWS-only → reject) ∧ (JWS+PoP → accept)`, identisch außer PoP.
2. **Wrong-Audience-struktureller-Reject** (N2): Hub-A-Credential an Hub B → fällt an `aud≠hubId` **∧** `cb≠SHA256(h‖hubB)` **∧** PoP-über-fremdem-`h`.
3. **Per-Hub-Isolation** (N4b): induzierter Trust-/401-Verlust an Hub B **rötet nicht** die Hub-A-Sitzung.
4. **`owned-but-issuer-not-trusted` distinkt** (C2): Hub owned ∧ Issuer-nicht-vertraut → eigener Zustand, **nicht** connectable/generisch/hang.
5. **`DeviceNotEnrolled` ≠ rejected** (N4a): Enroll-fehlt → distinkter Enroll-Zustand, nicht „abgelehnt, neu anmelden".
6. **Nebenläufigkeits-Sicherheit** (§6): per-Hub-AND hält unter **parallelen** Same-Operator-Sessions (kein Verlass auf one-active).
7. **STALE tritt ein** (N3, an die Weiche-(a)-Entscheidung gebunden): nach Widerruf/Ablauf wird der Zustand **beobachtbar** STALE/REJECTED (a1: ≤TTL; a2: Push).

---

## 12. Non-Goals / aufgeschoben

- **Kein** server-erzwungenes one-active-Gate (entschieden, benannt-aufgeschoben).
- **Keine** Cross-Operator-Föderation / Multi-Tenant / Delegation.
- **Keine** portable-Menschen-Identität (j) bis Unter-Weiche (b).
- **Kein** Bau vor PL-Gegenlesen.

---

## 13. Provenance

Objekt-Erhebung (`develop b5695165`): `controlplane/CpJwtMinter.kt`, `auth/CpJwtVerifier.kt`, `auth/IdentityToken.kt`, `transport/Rr3TunnelGate.kt`, `transport/RemoteRelayWiring.kt`, `relay/RelayServer.kt`, `relay/RendezvousRelay.kt`, `routing/Auth.kt`, `crypto/HubIdentity.kt`.
Team-2-Grounding (Assist2, gegen `backend2`/`uiux2` `develop`): `HubDiscoveryRoutes.kt`, `CpOperatorGate.kt`, `RendezvousRoutes.kt`, `RestContract.kt:128-139`, `LiveRelayRendezvous.kt`/`RelayRendezvous.kt` (CYP-507/536), `RemoteConnectComponents.kt` + `PinnedHubStore.kt` (`:app:shared`), `core/.../HubDescriptorModel.kt`; Docs CYP-542/548/536/525/501/457/443, `device-enroll-ux`, `remote-trust-recovery-ux`, `remote-operator-ux`.
Client-Needs-Spine: UIUX2 `docs/design/model2-multihub-client-needs.md` (CYP-748).
Honesty-Invariante ⇔ ratifizierte §4a-Regel `unknown ≠ all-clear`.
