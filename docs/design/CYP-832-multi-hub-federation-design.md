# CYP-832 — Multi-Hub Hub/Relay Federation (Design-Pass)

> **Status: DRAFT (v0.2)** — §3 topology firmed, §9.3 named, §8.1 PL build-teeth folded. Design-only, no build. Build-gated behind the god-token close
> (CYP-828 ✅ merged) **and** §9.3. This document exists to (a) sketch the federation architecture on the
> existing spine, (b) **isolate the hard-reversible Weichen** (§4b, §5) for the PL → Auftraggeber, and (c)
> carry the story decomposition so soft (stub-able) work can start against contracts *before* the Weichen freeze.
>
> **Nothing here arms an off-loopback remote connect.** Arming is the terminal step, gated (§9).

---

## 1. Goal & scope

Two (or more) Cyppie hubs, each a local `:server` with its own agents/worktrees, cooperate across a **trust
boundary** — an operator/agent identity minted at hub A is recognized (or explicitly *not* recognized) at hub B,
and hub-to-hub traffic flows over an authenticated transport. This is the **federation** layer.

**In scope (design):** Hub↔Hub protocol shape · cross-hub trust/identity model · server-side off-loopback
remote-connect admission posture · BYOA/BYODB touch-points · **surfacing the hard Weichen**.

**Out of scope:** live arming (gated §9); single-hub god-token boundary (CYP-828, done); the intra-hub ACL /
comm model (unchanged, CYP-9).

## 2. The spine this builds on (nothing invented from scratch)

| Substrate | What it already gives us | Ticket |
|---|---|---|
| **god-token loopback close** | operator grant is loopback-gated (`operatorEligible = isOperator ∧ loopbackPosture`, default fail-closed). **Precondition** — an off-loopback connect can no longer inherit the god-token. | CYP-828 |
| **Model-2 issuer-trust** | cross-hub operator identity via `issuer = Relay`; `HubIssuerTrust{TRUSTED, NOT_TRUSTED, REMOTE_NOT_CONFIGURED}` axis-c on `HubDescriptor.issuerTrust`; IssuerNotTrusted end-to-end. **The identity substrate.** | CYP-747, CYP-804 |
| **N-tunnel** | relay=0 / server≈M transport, per-tunnel PoP, revoke-fanout, DoS-cap. **The transport substrate.** | CYP-536 |
| **Gateway front-door** | same-origin allowlist default-deny, CORS=NONE, TLS=Caddy-edge. **The exposure envelope.** | CYP-638 |
| **Remote-bidi `/ws/hub`** | Ubuntu-26 BYOA proven remote-bidi hub channel. | CYP-687 |

**Design rule (from the god-token work):** every new cross-boundary grant/admission decision has a **DEFAULT =
the fail-closed value** and is decided by comparing FAILURE modes, not the happy path
([[safe-but-silent-default-needs-own-state]]). A federation not-configured hub must be indistinguishable from a
hub that denies — silence = deny.

## 3. Hub↔Hub protocol (topology) — FIRMED (deep pass)

Federation hub↔hub **reuses the existing Noise-tunnel-over-relay substrate** (CYP-536 N-tunnel / CYP-457 Noise
transport) — NOT a new socket and NOT a mux. Grounding on the transport as it stands:

- **Relay stays 0-trust** (relay=0, CYP-536): it pairs exactly one client-role dialer to one hub-role responder
  per opaque rendezvous-id. A federated peer participates by taking the **client (dialer) role** toward the
  target hub's rendezvous — the same client datapath the remote-operator case needs (the CR3/G1 client lane).
  No relay change; N distinct rendezvous-ids give N concurrent hub↔hub channels.
- **No mux.** Multiplexing many logical streams over one tunnel is design-forbidden (no WS-in-WS; it would need a
  from-scratch framing layer). Concurrency = **N tunnels**, each recycling the whole per-tunnel PoP stack — the
  ratified N-tunnel direction, not mux.
- **The near hub exposes its operator surface over a tunnel-scoped listener that rejects the static god-token**
  (CYP-427 `TunnelGodTokenGuard`, already built) — the same discipline as the loopback/god-token close
  (CYP-828). The far hub must therefore present a **CP-scoped, issuer-Relay-trusted cross-hub identity**
  (Model-2, CYP-747), NOT the static token.
- **Auth is NOT propagated by the pump** (M2 Q2): the tunnel authenticates the *channel* (RR3 = CpJwt +
  device-PoP → `operatorId` used only for the revocation registry); the near hub's routes **independently
  re-verify** the real credential carried in the request. Federation's cross-hub identity IS that
  independently-verified credential — which is why the trust-DECISION (§4a) and the trust-ANCHOR (§5) are the
  load-bearing parts, and the transport is not (it exists).

*Open (next pass):* rendezvous-set allocation for a hub↔hub peering (who registers, who dials which slot), and
whether federation reuses the operator N-set or gets its own **peering-id namespace** — leans toward its own,
decided together with §4b.

## 4. Trust & identity across hubs

### 4a. Trust DECISION (soft — stub-able NOW, on the existing axis)

The *decision function* — "given a remote hub descriptor + its issuer, is this presented operator/agent trusted
**here**?" — is buildable **now** against the existing `HubIssuerTrust` enum. It is pure classification over an
already-shipped vocabulary; no wire freeze required. Fail-closed: unknown issuer → `NOT_TRUSTED` →
`REMOTE_NOT_CONFIGURED` collapses to deny. → **Story S-Fed-2.**

### 4b. ⚠ WEICHE — Federation wire envelope (byte format)

**HARD-REVERSIBLE. PL → Auftraggeber before freeze.** The on-the-wire byte shape of a Hub↔Hub federation frame
(the envelope carrying issuer, PoP transcript, payload discriminator, version). Once two independently-deployed
hubs speak it in the field, changing it is a coordinated flag-day. DTOs can be *drafted* in `:core` behind
`@ExperimentalFederation` for shape-review, but **the byte contract does not freeze here.** → **Story S-Fed-1
(build-drafts only).**

*Decision needed:* version-negotiation strategy (hello-handshake vs. embedded version tag), discriminator
namespace, forward-compat rule.

## 5. ⚠ WEICHE — Trust-anchor provisioning, rotation & cross-hub revoke

**HARD-REVERSIBLE. PL → Auftraggeber before freeze.** *Who* is the trust anchor (which relay/issuer signs a
cross-hub identity), *how* its key rotates, and the *semantics of a cross-hub revoke* (does a revoke at hub A
propagate to hub B, on what latency, fail-open or fail-closed on partition). This extends CYP-536 revoke-fanout
and the still-open CYP-747 S4 C3 cross-hub-revoke. The **default posture on partition must be deny**
(fail-closed), but the anchor/rotation protocol is a durable commitment. → **Stories S-Fed-6, and gates S-Fed-3.**

## 6. Server-side off-loopback remote-connect admission (fail-closed gate)

Structurally a mirror of the god-token loopback gate: a **DEFAULT-deny** admission posture that only opens when
federation is *explicitly* configured with a trusted anchor. Buildable **now** against a **stub trust-decider**
(the S-Fed-2 contract) — the gate structure, the fail-closed default, and the reject teeth do not need the wire
freeze. Live arming is §9. → **Story S-Fed-3.**

## 7. BYOA / BYODB touch

*(TBD — mostly design.)* A bring-your-own-agent joining via a *federated* hub (the agent's checkout/custody lives
with the user, per D9); DB custody across hubs (BYODB — whose master-key custody, cf. CYP-670 D2 master-key-in-RAM).
Surfaces custody Weichen; little build. → **Story S-Fed-5.**

## 8. Story decomposition (first cut — mirrors the PO dispatch)

**Stub-able NOW (against contracts, before the Weichen freeze):**
- **S-Fed-2** — Cross-hub trust-decision fn on the existing `HubIssuerTrust` axis (§4a).
- **S-Fed-3** — Off-loopback remote-connect admission gate, fail-closed DEFAULT-deny, vs. stub trust-decider (§6).
- **S-Fed-4a** — Hub↔Hub session plumbing (client/server) vs. a stub transport (§3).

**⚠ Weiche-gated (PL / Auftraggeber before freeze):**
- **S-Fed-1** — Federation envelope DTOs in `:core`, `@ExperimentalFederation` drafts only; byte-freeze = §4b.
- **S-Fed-4b** — N-tunnel binding (CYP-536); TTL/mux touches M2.
- **S-Fed-5** — BYOA/BYODB custody design (§7).
- **S-Fed-6** — Cross-hub revoke fanout; trust-anchor/rotation = §5.

### 8.1 Build-teeth for the stub-able trio (PL-ratified)

Delivered *with* the trio tickets once M1 (story-boundaries-firm) is reported:

1. **S-Fed-4a rides the `@ExperimentalFederation` STUB interface only** — never a concrete envelope DTO shape.
   Touching a concrete shape here forces a reshape at the §4b byte-freeze. (The §5-Naht discipline: the session
   plumbing programs against the seam, not the wire.)
2. **S-Fed-3 fail-closed carries a MUTATION tooth** — remove the DEFAULT-deny → an admit-green result MUST go
   RED. A fail-closed gate whose deny-default can be silently dropped without a red test is vacuous
   ([[safe-but-silent-default-needs-own-state]] · [[verification-aimed-at-wrong-object]]).
3. **Nothing armed live** — the trio builds against contracts/stubs only; off-loopback remote-connect stays DARK
   behind §9 (esp. §9.3) and the §4b/§5 ratification.

## 9. Preconditions & build-gates

1. **§9.1** god-token loopback close — CYP-828 ✅ merged. *(Off-loopback can no longer inherit operator.)*
2. **§9.2** getenv-compare confinement — CYP-829 (routed, awaiting PO gate). *(No `== getenv("OPERATOR_TOKEN")` bypass.)*
3. **§9.3 — Tunnel↔credential runtime binding for multi-operator (G4 close / CYP-532 hard-gate).** Federation is
   **multi-operator by construction** — each hub carries its own operator identity. Today's safety rests on
   single-operator being HARD-enforced (partial-unique `idx_single_operator`, `NOT EXISTS` upgrade guard, NO
   promote endpoint), which makes the M2 **G4** scope-leak precondition — "hub-A's tunnel carries hub-B's
   operator credential" — *unreachable*. Off-loopback federation removes that single-op floor, so it MUST NOT arm
   until the RR3-tunnel `operatorId` is bound to the route-verified credential (the deferred G4 / CYP-532
   binding). Without it, a federated tunnel could let hub-A's operator act as hub-B's operator. **This is the
   precondition that couples §5 (cross-hub trust-anchor) to §6 (the admission gate).** Live off-loopback
   remote-connect stays DARK until §9.3 holds **and** the §4b/§5 Weichen are ratified.

## 10. Open Weichen for PL → Auftraggeber (the ratification block)

- **§4b** — Federation wire-envelope byte contract + version-negotiation. *(Flag-day cost once fielded.)*
- **§5** — Trust-anchor identity, key-rotation, cross-hub revoke semantics + partition posture.

*(This block is what the PO forwards to the PL. Everything outside it — §4a, §6, S-Fed-2/3/4a — runs against
contracts now.)*

---

*Author: backend. Grounded on CYP-828 / CYP-747 (Model-2) / CYP-536 / CYP-638 / CYP-687. Deep passes pending;
this skeleton isolates the Weichen so soft work parallelizes immediately.*
