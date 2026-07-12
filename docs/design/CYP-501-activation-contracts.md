# CYP-501 — Phase-2 Activation Contracts (Relay/Rendezvous + CP hubTicket-Mint)

> Status: **Design (conventional finalization of ratified decisions — NO new ratification)** · Epic CYP-427 Phase-2 Remote · Backend lane
> Base: develop `eb84fe1b` (server-side auth chain ①→②→③ complete + merged) · Wire contract: `CYP-443-tunnel-framing-spec.md` (LOCKED)
> Client counterparts: CYP-494 (`RelayDialer`) · CYP-496 (`CpJwtProvider`) · the merged RR3 gate consumes the CpJwt this mint produces
>
> **★ Live activation is Auftraggeber-GO** (relay-server operation + `CYPPIE_REMOTE_RELAY_URL` flag-on + deploy). This doc +
> the INERT-compiling seams are the ungated deliverable. **Any relay-server hosting/access-posture decision → escalate to
> the PO** (risk/access, not a Backend call).

## 0. What this is
The two **Control-Plane-side** contracts the remote client needs to reach a hub — the counterparts of the hub-side auth
chain already on develop. They **implement ratified decisions** (RR4 opaque rendezvous · RR5 outbound-only · R1-A offline
verification · S-E channel-binding) — this is conventional finalization, not a new envelope. Built **INERT** (no live relay
dial, no live mint) until the Phase-2-Remote-GO.

## 1. The client flow spans both contracts (why one doc)
```
CYP-494 RelayDialer.dial(hubId) ─┐
                                 ├─▶ CP resolves hubId → opaque rendezvous → relay WS (contract ①)
hub WebSocketRelayDialer ────────┘        │
                                          ▼
                        Noise_NK handshake over the relay (hub = responder on its dhKey static)
                                          │  yields the live handshake hash h
                                          ▼
CYP-496 CpJwtProvider.provide(hubId, cb=base64url(SHA-256(h‖hubId)))  ─▶ CP mints the hubTicket (contract ②)
                                          │
                                          ▼
             client sends {cpJwt=hubTicket, pop, nonce} over the tunnel (CYP-459 :core TunnelAuthRequest)
                                          ▼
             hub RR3 gate: CpJwtVerifier (cb == SHA-256(live-h‖hubId)) ∧ operator PoP (over live h) → grant
```
The `cb` **cannot** be minted before `h` exists (`h` is per-session, from the Noise handshake), so the mint request
carries the client-computed `cb`; the hub re-derives and checks it against its OWN live `h` (anti-replay). Hence: dial →
handshake → **then** mint → present. The two contracts are one linear flow; one doc.

## 2. Contract ① — Relay / Rendezvous server (RR4 opaque, RR5 outbound-only)
**Role.** An **untrusted** WebSocket relay that pairs a hub with a client and forwards opaque binary frames between
them; it sees only the opaque rendezvous id + ciphertext + sizes/timing (RR4) — never hub identity or payload.

**Two roles on the relay:**
- **Hub** (already built, `WebSocketRelayDialer` / `NoiseRelayConnector`): dials the relay **OUT** (RR5 — no inbound
  listener for the tunnel) and **registers** under an opaque rendezvous id derived from its `hubId` (see below).
- **Client** (CYP-494 `RelayDialer.dial(hubId)`): asks the CP for the hub's current rendezvous, then dials the relay to
  that rendezvous.

**Rendezvous id (RR4 opaque).** The id MUST NOT be the `hubId` (that would leak hub identity to the relay). Finalized
scheme: **`rendezvousId = base64url(SHA-256(hubId ‖ rendezvousEpoch))`**, where `rendezvousEpoch` is a CP-issued,
**per-registration** random 16-byte value the CP hands to BOTH ends (the hub at register, the client at resolve). The
relay sees only the hash, never `hubId`; the CP is the sole `hubId`↔rendezvous resolver. A **new registration** rotates
the epoch → a fresh id: **unlinkable across REGISTRATIONS**. Within a single registration the id is **stable**, so the
relay can correlate that hub's sessions to one another — an **accepted RR4 metadata residual** (the relay already sees
pairing/timing/sizes, §5), NOT a per-session-unlinkability requirement (which RR4 does not mandate; no per-session epoch
rotation).

**Framing (LOCKED, CYP-443 §4.3).** Message-preserving WS: **1 binary frame = 1 Noise message, NO length prefix**. The
relay forwards frames verbatim between the paired sockets. Ordered + reliable (the WS gives that); a socket close on
either leg closes the pair (fail-closed → the tunnel resets, §4 truncation-guard, already enforced hub-side).

**Registration/pairing protocol (minimal, provisional→final).** Hub connects `GET /relay?role=hub&rzv=<id>` (opaque);
client connects `GET /relay?role=client&rzv=<id>`; the relay pairs the first hub+client on a matching `rzv` and pipes
frames. One hub + one client per rendezvous (1 L2 per tunnel, §5). No auth AT the relay (it is untrusted; auth is the
end-to-end Noise + RR3 — the relay is a dumb pipe, exactly like the loopback bridge is dumb hub-side). Backpressure ≤8
in-flight (§3), no mux.

**INERT seam (this ticket):** `RelayRendezvous` — the CP-side hubId→rendezvous registry/resolver interface + the
opaque-id derivation; `InertRelayRendezvous` stub (no live relay). The relay SERVER itself (a process) is **not** built
here — its operation is the Auftraggeber-GO activation.

## 3. Contract ② — CP hubTicket-Mint (S-E channel-binding, R1-A offline verify)
**Role.** The CP mints the **`hubTicket`** (an EdDSA CpJwt) the client presents to the hub; the hub's merged S-E
`CpJwtVerifier` verifies it OFFLINE (R1-A — no CP round-trip at the hub at verify time).

**Request (client CYP-496 `CpJwtProvider` → CP), after the Noise handshake:**
```
HubTicketRequest { hubId: String, cb: String }   // cb = base64url(SHA-256(h ‖ hubId)) from the LIVE session h
```
Bearing an operator session/device-code auth to the CP (the CP authenticates WHO the operator is — see DeviceCodeFlow).

**CP mint (builds on the merged `CpJwtMinter` + `HubRegistrar`, CYP-451):**
1. Authenticate the operator (CP session / device-code) → the authenticated `operatorId`. **The client never chooses
   `sub`** — the CP sets it from the authenticated identity.
2. `hubId` must be a hub the operator owns in `HubRegistrar` (`RegisteredHub.ownerId == operatorId`) — else reject.
3. Mint `CpJwtMinter.mint(hubId, operatorId, channelBinding = cb, issuedAtMs = now, ttlMs = short)` →
   `{ iss=CP, aud=hubId, sub=operatorId, nbf, exp, cb }` EdDSA/Ed25519 (the CP's own signing key, kid-pinned at the hub).

**Short TTL = the Op-Session-TTL (CYP-484).** The hubTicket `exp` is minutes (Decision 4); the hub also caps the tunnel
session TTL — min of the two. A revocation drops the live tunnel immediately (CYP-484 `revokeOperator`).

**★ Security (why a client-supplied `cb` is safe — PO-confirmed):** the client supplies `cb` (bound to ITS live `h`),
and the CP signs with the **authenticated `sub`** (not client-chosen). At the hub, the RR3 gate checks BOTH
`cb == SHA-256(live-h ‖ hubId)` (anti-cross-session-replay, CB-x1) **AND** the operator PoP (device-signed over the live
`h`, anti-seizure). So a wrong `cb` (not the live `h`) or a wrong `sub` (not this hub's owner) **fails closed at the hub**
— the CP forges identity but never the PoP (RR2-B), and the `cb`+PoP double-bind ties the ticket to the specific tunnel.
A malicious CP that swaps a hub's registry `dhPubKey` is caught by the client-side TOFU pin (CYP-478, `HubTrust.Pinned`).

**★ Distinct operator-facing failures (UIUX 3-truths model).** The mint response is **typed**, not a generic null —
the client renders the right cause:
- **`CP_SESSION_EXPIRED`** — the CP could not authenticate the operator (dead Kratos session) → the client routes to
  re-auth (AuthGate) and resumes. **Non-terminal.**
- **`NOT_AUTHORIZED_FOR_HUB`** — the operator is authenticated but does not own the hub (`RegisteredHub.ownerId`) →
  **terminal, and distinct from** the hub-side `authRejected` (the RR3 gate rejecting the PoP). Two different truths: a
  not-owner is stopped at the CP; a compromised-CP-minted identity is still stopped at the hub — the client must not
  conflate them.
- **`cpUnreachable`** (network to the CP) is **client-side** and never appears in the response.

**INERT seams (this ticket):** `HubTicketMinter.mint(request, cpSession): HubTicketResponse` — end-to-end authenticate
→ owner-check → `CpJwtMinter`; `InertHubTicketMinter` fail-closed. `:core` `HubTicketRequest{hubId, cb}` +
`HubTicketResponse{cpJwt?, failure?}` + `enum HubTicketFailure{CP_SESSION_EXPIRED, NOT_AUTHORIZED_FOR_HUB}` (@Serializable).

## 4. INERT seams delivered here (compile-only, no live behavior)
- `:core` `HubTicketRequest(hubId, cb)` + `HubTicketResponse(cpJwt?, failure?)` + `enum HubTicketFailure` (@Serializable,
  via CommJson) — the client↔CP mint wire contract, incl. the typed operator-facing failure distinction.
- `:server` `controlplane/RelayRendezvous.kt` — `RelayRendezvous` (register/resolve + opaque-id derivation) +
  `InertRelayRendezvous` stub. Pure interface; no relay dial.
- `:server` `controlplane/HubTicketMinter.kt` — `HubTicketMinter.mint(request, cpSession): HubTicketResponse` seam +
  `CpOperatorSession` + `InertHubTicketMinter` fail-closed stub (no live mint). Composes the merged `CpJwtMinter` +
  `HubRegistrar` when live.
All off-by-default; the current server / tests are unchanged (health 200). The live wiring (a real relay + a real mint
endpoint) is CYP-427 activation, Auftraggeber-GO.

## 5. Threat model / cross-cutting
- **Untrusted relay** (RR4): ciphertext + opaque id + sizes/timing only; the opaque rendezvous id (hashed epoch) hides
  hubId; the relay is a dumb pipe (auth is end-to-end Noise + RR3).
- **Outbound-only** (RR5): the hub dials the relay; no inbound tunnel listener (already enforced hub-side).
- **Offline verify** (R1-A): the hub verifies the hubTicket with the pinned CP key — no CP call at verify time.
- **Channel-binding double-bind** (S-E): `cb` (in the ticket) + PoP (device-signed) both bind the live `h` — the hub
  rejects any ticket not minted for this session's `h`, and any PoP not signed by an enrolled device.
- **Anti-seizure** (RR2-B): the CP forges identity (mints the ticket) but never the PoP — a compromised CP cannot
  authenticate as the operator at the hub. Recovery is hub-local backup-codes (CYP-485), never central-login-alone.
- **Live-activation gated** (Auftraggeber-GO): relay-server operation + flag-on + deploy. **Relay-server hosting/access
  decisions escalate to the PO.**
