# CYP-872 — Federation Arming-Prep Runbook (Epic CYP-832)

> **Status:** DRAFT v0.1 · **Author:** backend · **Owner + executor:** PL · **GO authority:** Auftraggeber
> Companion to `docs/design/CYP-832-multi-hub-federation-design.md` (esp. §6 admission gate, §9 preconditions).

---

## ⚠ NON-EXECUTION BANNER — read first

**This document DESCRIBES the arming sequence. It does NOT arm anything, and nothing in it is run as part of
authoring or reviewing it.**

- No step here flips the federation posture, dials a live relay, opens an off-loopback socket, or deploys.
- Every "action" below is for the **PL to own and execute on real target topology, after Auftraggeber GO** — never by
  an agent, never as a side effect of reading this file.
- The backend (author) does **not** initiate arming. Arming is PL/Auftraggeber territory (Epic §9). This runbook is
  the **operative checklist of the PL-ARMING-GATE**, drafted for the PL to review, correct, and own.
- The federation code shipped to date is **DARK by construction**: the CYP-858 sole-path guard (§1) holds the entire
  composed chain physically inert until the posture in §3.1 is deliberately flipped. This runbook exists so that flip
  is a controlled, reversible, evidence-gated step — not a discovery.

---

## 1. Verified building blocks (already MET — reference, do not re-prove)

These are the finished, gated substrate the arming sequence stands on. They are **preconditions that already hold**;
the runbook cites them, it does not re-litigate them.

| Block | State | What it guarantees |
|---|---|---|
| Dark batch: CYP-849/850/851/859/860/858/862/863/864 | **All merged to `develop` (`5eac2dc9`, the CYP-871-into-develop merge)** | The full federation build exists, DARK. |
| **CYP-858 sole-path guard** | **PL-verified** (MUT-A: `open` ignores gate → the 2 deny teeth red · MUT-B: ctor `private`→`internal` → `federationSessionConstructor_isPrivate` red · + no-off-gate construction scan) | `FederationSession` is constructible **only** via `FederationSession.open(gate, …)`, which is admission-gated. **This is the invariant the whole arm rests on** — there is no back door around the gate. |
| CYP-871 dark dress rehearsal | **Green + merged-clean (`c12b27ec`)** | The 6 pieces compose end-to-end vs a stub tunnel + real Ed25519. Central tooth `deniedAdmission_noSession_noFrame_assemblyInert` proves posture-OFF ⟹ no session ⟹ 0 frames = the assembly is inert. |
| §9.1 god-token loopback close | CYP-828 ✅ merged | An off-loopback connect can no longer inherit the operator god-token. |
| M2 §4b/§5 Weichen | **RATIFIED** (keyset-rotation binding) | The wire envelope + trust anchor/rotation/revoke contracts are frozen. Ratification unblocked **build**; it did **not** arm. |

---

## 2. The four arming PREREQS (all must independently hold, on REAL topology, fail-closed)

Arming is gated on four preconditions (three from Epic §9, plus the §5 keyset ruling). Each is an **AND** — any one
absent ⟹ **do not arm**. Each must be **verified with evidence on the real target topology**, not asserted and not
proven on loopback.

> **★ Gate — who may run §3, and when.** The prereqs holding is *necessary but not sufficient*. **No one runs the
> §3 swap sequence** without **(a) PL review of the prereq evidence _on real topology_** and **(b) an explicit
> Auftraggeber arming-GO.** This document arms nothing; even a reader who satisfies P1–P4 must still obtain the
> PL-review + Auftraggeber-GO before a single swap step is executed. Backend never runs any of it.

### P1 — Server-side re-auth verified
The operator identity used for the federated connect must be **re-authenticated server-side at arm time**, not carried
over from a prior loopback/session context. *Verify:* an explicit server-side auth challenge succeeds for the intended
operator on the target hub; a stale/absent credential fails closed. (Couples to §9.1 — off-loopback ≠ inherited
operator.)

### P2 — §9.3 tunnel↔credential runtime binding on REAL topology (G4 close / CYP-532)
Federation is **multi-operator by construction** — each hub carries its own operator identity, which removes the
single-operator floor that today makes the M2 **G4** scope-leak ("hub-A's tunnel carries hub-B's operator credential")
*unreachable*. Before any off-loopback arm, the RR3-tunnel `operatorId` **must be bound to the route-verified
credential** (the deferred G4 / CYP-532 binding). *Verify:* on real topology, a tunnel established for hub-B's
operator cannot be used to act as hub-A's operator (positive + negative control). **This is the precondition that
couples §5 trust-anchor to §6 admission; it is the one that must not be skipped.**

### P3 — god-token✅ intact
CYP-828 loopback-close merged (§9.1) **and** CYP-829 getenv-compare confinement (§9.2, no `== getenv("OPERATOR_TOKEN")`
bypass) landed. *Verify:* no path grants operator off-loopback; the closure teeth (CYP-747 v3 SOURCE-keyed scan) hold.

### P4 — Keyset-rotation live-wiring landed, DARK (CYP-877) — the §5 ruling
**PL ruling (on §3.2): arm on the LIVE keyset, not the single pin.** The live trust anchor MUST resolve through the
ratified M2 §5 **keyset** shape — `IssuerKeyset {kid→pub}` + overlap window + old-key rotation attestation — **not**
today's single pin. Today `RemoteRelayWiring.resolveIssuerAnchor` (`:79`) returns a **single** `IssuerAnchor`; the
keyset DTOs (CYP-860) exist **dark** but are **not live-wired**. Arming on the single pin would **bake the rotation
gap into the hardest-to-change live layer** and force a guaranteed hard cutover later; wiring the keyset now
parallelizes with prereq/topology setup. So **CYP-877 (keyset-rotation live-wiring, DARK, arming-prereq) must land
before arming.** It stays dark until arming. *Verify:* the live path classifies trust via the keyset `accepts`-overlap
+ rotation attestation, not a single `kid`. *(Auftraggeber may override to single-pin — PL flags it; default =
CYP-877.)*

> **Ratification is a build-gate, not an arm-gate.** M2 §4b/§5 being RATIFIED unblocks building; **P1–P4** on real
> topology + PL-review + Auftraggeber-GO is what unblocks arming.

---

## 3. Stub → Live swap sequence (the arming steps — PL-owned, execute only after §2 + GO)

Each row swaps a DARK stub for its LIVE counterpart. Execute **top to bottom**; each step has a precondition and an
observable post-check. The rightmost column names the CYP-871 tooth that already models the intended behaviour, so the
live post-check is a known-good comparison, not a first observation.

### 3.1 Admission posture — the master switch
- **DARK:** `FederationAdmissionGate(federationEnabled = false)` — default fail-closed; `admit()` denies everything.
- **LIVE:** derive `federationEnabled = true` **from real config** (a resolvable issuer anchor **and**
  `CYPPIE_REMOTE_RELAY_URL` present), not a hardcoded flip.
- **Precondition:** §2 P1–P4 all verified on this topology.
- **Post-check:** `RemoteRelayWiring.classifyIssuerTrust(env)` == `ISSUER_TRUSTED` for the intended peer.
- **Models:** the central inert tooth (posture-OFF path) — flipping this is exactly what that tooth holds inert.

### 3.2 Trust anchor provisioning (OOB, no in-app grant) — arm on the live KEYSET (PL ruling)
- **DARK:** no anchor → `RemoteRelayWiring.resolveIssuerAnchor(env)` == `null` → `InertRelayConnector`.
- **LIVE (arm on the keyset, NOT the single pin — see §2 P4 / CYP-877):** provision the trusted issuer material
  **out-of-band** as the rotatable **`IssuerKeyset {kid→pub}`** (M2 §5, Model-2, issuer = Relay) via
  `CYPPIE_RELAY_ISSUER/KID/PUBKEY` (own relay, preferred) or the `CYPPIE_CP_*` fallback. The current single-pin
  `resolveIssuerAnchor` (`:79`) is **not** the arming target — CYP-877 replaces it at the live path with the keyset +
  `accepts`-overlap + rotation attestation. No in-app grant.
- **Post-check:** trust classifies via the keyset `accepts`-overlap (a rotation-attested new key is accepted within
  the overlap window; an out-of-keyset key is DENY); the active `kid`/`pub` match the OOB-distributed keyset (compare,
  don't trust env echo).

### 3.3 Transport — stub tunnel → live Noise tunnel
- **DARK:** CYP-871 binds a `StubTunnel` (implements `ServerNoiseTunnel`); the live connect path is
  `InertRelayConnector` (default-deny).
- **LIVE:** the relay-dialed `NoiseRelayConnector` yields a live `ServerNoiseTunnel`, adapted to the federation
  transport by `FederationTunnelTransport` (CYP-864, byte-opaque, exposes the handshake hash `h` for the peer PoP).
- **Precondition (ALL present, else stays `InertRelayConnector` — fail-closed):** `CYPPIE_REMOTE_RELAY_URL`,
  `CYPPIE_OPERATOR_ID`, the issuer anchor (§3.2), `CYPPIE_OPERATOR_RP_ID`, `CYPPIE_CP_URL`,
  `CYPPIE_CP_OPERATOR_TOKEN`, and the hub identity / secret store / operator-device store all wired.
- **Post-check:** a live tunnel establishes and its `handshakeHash` is non-empty and stable across the handshake.
- **Models:** `Cyp864FederationTunnelTransportTest` (byte-exact carry both directions) + CYP-871
  `admittedAdmission_helloRoundTripsEndToEnd`.

### 3.4 Peer authentication + session open
- **LIVE:** at the live connect path, call `FederationSession.open(gate, remoteIssuerTrust, liveTransport)` where
  `remoteIssuerTrust = classifyIssuerTrust(env).toWire()`. The federation-peer PoP (purpose tag
  `"federation-peer"`, bound to the tunnel `h` via `operatorAuthChallenge`) authenticates the remote peer.
- **Post-check:** a trusted, PoP-valid peer yields a non-null session; an untrusted or PoP-invalid peer yields `null`
  (the guard) and **no frame is ever sent** — the CYP-871 central tooth's live analogue.

### 3.5 Version negotiation (first frame)
- **LIVE:** exchange `FederationHello{protoMin, protoMax}`; `negotiateVersion` picks the highest common version or
  **refuses** (out-of-range = fail-closed). *Post-check:* both peers converge on the same negotiated version, or the
  session closes.

**Overall arm success criterion:** a trusted peer admits + Hello round-trips (mirrors
`admittedAdmission_helloRoundTripsEndToEnd`); every untrusted/mis-provisioned/PoP-invalid peer stays DENY with zero
frames (mirrors `deniedAdmission_noSession_noFrame_assemblyInert`).

---

## 4. Rollback path (most powerful lever first)

Rollback is **config/flag only** — no data migration, no destructive operation. The CYP-858 guard does the structural
work.

1. **Master disarm — flip the posture back.** Set `federationEnabled = false` (or remove the config that derives it in
   §3.1). By the **CYP-858 sole-path guard + the CYP-871-proven inertia**, `FederationSession.open` then returns
   `null` on every peer ⟹ no session ⟹ no frame reaches any tunnel. **One switch re-inerts the entire composed
   chain.** This is the primary rollback and it is total.
2. **Drop the live transport.** Remove/blank `CYPPIE_REMOTE_RELAY_URL` (or any required live-connect env from §3.3) ⟹
   the connect path falls back to `InertRelayConnector` (default-deny) on the next resolve.
3. **Revoke a single peer without full disarm.** Emit an issuer-signed `FederationRevocationFrame` via
   `FederationRevocationFanout` (CYP-863) — it tears the named peer down over its own tunnel (real-Ed25519 verified;
   a forged signature fans out to nobody, per CYP-871 `forgedRevoke_underRealCrypto_fansOutToNobody`).
4. **Partition / uncertainty posture.** On loss of anchor reachability, trust classifies fail-closed →
   `NOT_TRUSTED` → DENY. The system **over-denies during a partition; it never over-admits** (§5-4).

*Rollback verification:* after step 1, re-run the arm success criterion — the trusted-peer path must now DENY (0
frames), confirming the disarm took.

---

## 5. Explicit scope boundary (what this runbook is NOT)

- It does **not** arm, flip the posture, provision anchors/keysets, dial a relay, open an off-loopback socket, or deploy.
- It does **not** authorize P1–P4 — it states how each is **verified**; the human/PL confirms them **out of band on
  real topology**.
- **No one runs the §3 sequence on the strength of this document.** Execution requires, in addition to P1–P4 holding:
  **(a) PL review of the prereq evidence on real topology**, and **(b) an explicit Auftraggeber arming-GO**. Absent
  either, nothing is armed.
- The backend **authored** this checklist. The **PL owns and executes** the arming sequence, under **Auftraggeber
  GO**. No agent runs any step herein.

---

*Grounded on: `FederationAdmissionGate` (CYP-850, `federationEnabled` default-false ∧ issuer-trust decider) ·
`FederationSession.open` sole-path guard (CYP-858, PL-verified) · `FederationTunnelTransport`/`ServerNoiseTunnel`
(CYP-864/457) · `RemoteRelayWiring.resolveIssuerAnchor`(`:79`, single-pin today)/`classifyIssuerTrust`/`InertRelayConnector`/`NoiseRelayConnector`
· `IssuerKeyset {kid→pub}`/`accepts`/rotation attestation (CYP-860 dark → CYP-877 live-wiring, arming-prereq)
· `operatorAuthChallenge` + `"federation-peer"` PoP (CYP-473/859) · `FederationRevocationFanout` (CYP-863) ·
Epic CYP-832 §6/§9. CYP-871 (`c12b27ec`) is the composition evidence.*
