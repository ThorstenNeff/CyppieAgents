# CYP-832 — Multi-Hub Hub/Relay Federation (Design-Pass)

> **Status: RATIFICATION-READY (v0.4)** — the §10 PL bundle (§4b wire-envelope + §5 trust-anchor/rotation/revoke, 8
> freeze points) is UNCHANGED from v0.3 (`232c9f80`, already at the PL). v0.4 adds design-ahead, **non-Weiche** detail
> so the post-ratification build has no gap: §3.1 rendezvous peering-id allocation + §7 BYOA/BYODB boundaries. §3
> topology firmed, §9.3 named, §8.1 PL build-teeth folded. Design-only, no build. Build-gated behind the god-token close
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

### 3.1 Rendezvous peering-id allocation (design-ahead — non-Weiche)

*How* two hubs find each other on the relay. Grounded on the operator N-set (CYP-536): the relay pairs one
client-dialer to one hub-responder per opaque rendezvous-id; the CP mints epoch-derived ids, the responding side
registers them, the dialing side resolves + dials.

**Decision — federation gets its OWN peering-id namespace, distinct from the operator N-set.** A federated peering
is domain-separated from operator rendezvous-ids (the derivation adds a `"federation-peer"` domain tag; the exact
bytes are §4b-adjacent, but the SEPARATION is the design-ahead commitment). Three axes that must stay independent:
 1. **Revocation / cap isolation:** a federated-peer tunnel and an operator tunnel to the same hub must be
    independently revocable and independently DoS-capped. Sharing the operator N-set would conflate them in the
    `TunnelSessionRegistry` fan-out and the per-operator cap (CYP-536) — revoking a peer would tear down operator
    sessions. Federation peerings get their OWN cap and a revoke keyed by `peerHubId` (feeds §5-3 cross-hub revoke).
 2. **Credential axis:** operator tunnels attest with the RR3 operator device-PoP; federation peers attest with the
    `"federation-peer"` PoP (§4b-1) chained to the issuer anchor (§5). Different credential → different namespace
    keeps the two auth paths cleanly separated.
 3. **Lifecycle / cardinality:** the operator N-set is per-operator-SESSION (N concurrent workspace channels); a
    peering is per-PEER-HUB and long-lived.

**Role assignment (deterministic, avoids double-tunnels):** the Noise tunnel is bidirectional once established, so
ONE tunnel per unordered hub-pair, not two. The **lexicographically-lower `hubId` is the responder** (registers the
peering-id); the higher dials it. N peer-pairs = N tunnels, **no mux** (consistent with §3). Non-Weiche: it fixes no
wire bytes — §4b only freezes how the peering-id is CARRIED, not this allocation model.

## 4. Trust & identity across hubs

### 4a. Trust DECISION (soft — stub-able NOW, on the existing axis)

The *decision function* — "given a remote hub descriptor + its issuer, is this presented operator/agent trusted
**here**?" — is buildable **now** against the existing `HubIssuerTrust` enum. It is pure classification over an
already-shipped vocabulary; no wire freeze required. Fail-closed: unknown issuer → `NOT_TRUSTED` →
`REMOTE_NOT_CONFIGURED` collapses to deny. → **Story S-Fed-2.**

### 4b. ⚠ WEICHE — Federation wire envelope (byte format)

**HARD-REVERSIBLE. PL → Auftraggeber before freeze.** The on-the-wire byte shape of a Hub↔Hub federation frame.
Once two independently-deployed hubs speak it in the field, changing it is a coordinated flag-day across every
deployed hub — so the *shape* and the *forward-compat rule* must be ratified before the first federated hub ships,
even though exact field bytes may stay drafted (`@ExperimentalFederation`) up to the freeze.

**Envelope contents (proposed):** `version` · `issuer` identity (the Model-2 issuer=Relay anchor, §5) · a
**peer-attestation PoP** · a **payload** behind a typed discriminator.

**Ratification decisions — recommendation + freeze point:**

1. **PoP transcript — REUSE, do not invent** *(low-risk)*. The peer-attestation reuses the existing `:core`
   `operatorAuthChallenge(h, hubId, nonce)` (CYP-473 H2: `LP(h)‖LP(hubId)‖LP(nonce)‖LP(purpose)`, 4-byte-BE
   length-prefixed, injective, bound to the live Noise `h`) with a **distinct purpose tag** `"federation-peer"`
   (not `"operator-auth"`) — domain separation is exactly what the purpose field is for. Inherits the anti-replay
   `h`-binding and the single-`:core`-source anti-drift property for free (the CYP-536 C1 lesson: NO new challenge,
   NO new transcript). **Freeze:** the purpose-tag string + that the federation PoP IS this transcript.
2. **Discriminator namespace — reuse the CommJson sealed-interface idiom.** `sealed interface FederationFrame`
   with `@Serializable` variants under CommJson `classDiscriminator="type"` + `explicitNulls=false` — the wire
   idiom already proven across the protocol. **Freeze:** the discriminator key + the initial variant type-names.
3. **Version negotiation — explicit hello-handshake with a min/max range** *(the flag-day insurance)*. The first
   framed message after tunnel establishment is `FederationHello{ protoMin, protoMax, … }`; peers negotiate the
   highest common version; a peer outside the other's range is **refused (fail-closed)**, never assumed-compatible.
   Bounds the flag-day cost — future versions negotiate DOWN instead of a synchronized upgrade. **Freeze:** the
   hello exists, is the first frame, and the negotiate-down rule.
4. **Forward-compat rule** *(the freeze that matters most)*. Additive-only fields; nullable-default-ABSENT (CYP-804
   `explicitNulls=false`, proven); NEVER reorder / NEVER repurpose a field; an **unknown frame type or discriminator
   fails CLOSED** (deny/close the peering), never silently ignored. **Freeze:** this rule itself — it is what makes
   every later additive change safe.

**Draftable now (S-Fed-1, `@ExperimentalFederation`):** the Kotlin DTOs for the hello + frame variants, for
shape-review. **Frozen only at ratification:** the four points above. S-Fed-4a (CYP-851) rides ONLY the
`@ExperimentalFederation` stub interface and never a concrete variant shape → a post-review reshape costs nothing.

## 5. ⚠ WEICHE — Trust-anchor provisioning, rotation & cross-hub revoke

**HARD-REVERSIBLE. PL → Auftraggeber before freeze.** *Who* signs a cross-hub identity, *how* its key rotates, and
what a cross-hub *revoke* means. Grounded on the existing anchor: `RemoteRelayWiring.IssuerAnchor(issuer, kid, pub)`
(CYP-747 S1) — resolved ALL-OR-NOTHING from a complete issuer set, and `HubIssuerTrust.TRUSTED` ⟺ an anchor is
pinned. Federation reuses this anchor as the cross-hub trust root; the decisions below extend it.

**Ratification decisions — recommendation + freeze point:**

1. **Anchor = the pinned issuer=Relay (Model-2), provisioned OOB.** A hub trusts a federated peer's identity only
   if it chains to an anchor the hub has pinned (`resolveIssuerAnchor != null`). Provisioning is out-of-band —
   matches `HubIssuerTrust.NOT_TRUSTED`'s "recovery is OOB only, no in-app grant." **Freeze:** the anchor is the
   cross-hub trust root; NO in-app trust-grant path exists.
2. **Rotation — pin a KEYSET `{kid → pub}`, overlap window** *(today's single-pin is the gap; the biggest durable
   commitment)*. The anchor already carries a **`kid`** — the rotation seam is native. Rotation = the issuer signs
   with a new `(kid, pub)`; hubs pin BOTH old and new during an overlap window and accept either, so rotation is
   not a flag-day. A **rotation attestation signed by the OLD key** authorizes the new key. **Freeze:** single-pin
   → keyset, the overlap semantics, and the rotation-attestation shape.
3. **Cross-hub revoke — the issuer/anchor is the revoke authority; fan-out extends CYP-536.** Intra-hub revoke
   already fans to all N sessions of an operator (`TunnelSessionRegistry.revokeOperator`, CYP-536). Cross-hub revoke
   = the issuer publishes a revocation that federated hubs honor (the still-open CYP-747 S4 C3). **Freeze:** the
   revocation object shape + who may publish it + the propagation channel.
4. **Partition posture — FAIL-CLOSED** *(non-negotiable with the spine)*. A hub that cannot confirm revoke/anchor
   state from the issuer treats federated identities as **NOT_TRUSTED** → collapses to the S-Fed-2 **DENY**
   (CYP-849). It may over-deny during a partition; it never over-admits. This is what couples §5 to §9.3 (the
   tunnel↔credential binding) and to §6 (the admission gate). **Freeze:** partition = deny, always.

**Note:** §5-2 (rotation keyset) and §5-3 (revoke) are the two irreversible commitments; §5-1 and §5-4 largely
formalize the fail-closed posture that already exists.

## 6. Server-side off-loopback remote-connect admission (fail-closed gate)

Structurally a mirror of the god-token loopback gate: a **DEFAULT-deny** admission posture that only opens when
federation is *explicitly* configured with a trusted anchor. Buildable **now** against a **stub trust-decider**
(the S-Fed-2 contract) — the gate structure, the fail-closed default, and the reject teeth do not need the wire
freeze. Live arming is §9. → **Story S-Fed-3.**

## 7. BYOA / BYODB touch — FIRMED (design-ahead — non-Weiche)

The federation boundary is a TRUST + TRANSPORT relationship, NOT a shared identity or datastore. Two decisions,
both fail-closed:

**BYOA — a federated peering carries OPERATOR trust only; cross-hub AGENT participation is an explicit, narrow
grant.** An agent brought to hub A (its checkout/custody with the user, per D9) does NOT automatically gain identity
on hub B. If an agent on A must participate in a channel on B, that is an explicit per-agent **federated
channel-share**, operator-gated — the cross-hub analog of the intra-workspace `ChannelShare` (CYP-93 / S17), and
fail-closed by default (unshared ⟹ the agent is local to its own hub). A brought agent never silently acquires reach
across the peering.

**BYODB — per-hub custody is NOT shared across the federation.** Each hub keeps its OWN encrypted-at-rest store and
its OWN master key (CYP-434 SecretStore, CYP-670 D2 master-key-in-RAM). A federated peering never shares a datastore
or a master key. Cross-hub data that must be visible on both hubs (messages, ACL projections) flows over the
federation transport (§3 session plumbing) and each hub **persists its own copy in its own store** — no shared DB,
no shared custody, no cross-hub master-key entanglement. If BYODB later means "bring your own Postgres" (CYP-220),
that is a per-hub DSN choice, orthogonal to federation.

**Why non-Weiche:** neither decision fixes a wire byte or an irreversible trust-anchor commitment — they are
architecture boundaries (identity scope + custody scope) the post-ratification build slots into. → **Story S-Fed-5**
is now a build against these boundaries, not an open design question.

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

## 10. Open Weichen for PL → Auftraggeber (the ratification block) — RATIFICATION-READY

Each Weiche is now spelled out in its section with a **recommendation + an explicit freeze point**. The PL bundles
these eight freeze points; everything outside them (§4a, §6, S-Fed-2/3/4a) runs against contracts now.

**§4b — Federation wire envelope (four freeze points):** (1) PoP = reuse `operatorAuthChallenge` with a
`"federation-peer"` purpose tag; (2) `sealed FederationFrame` under CommJson `type`-discriminator; (3) explicit
`FederationHello` version-negotiation, negotiate-down, out-of-range = refuse; (4) forward-compat rule
(additive-only, nullable-absent, unknown-type = fail-closed).

**§5 — Trust anchor / rotation / revoke (four freeze points):** (1) anchor = pinned issuer=Relay (Model-2),
OOB-provisioned, no in-app grant; (2) **rotation = keyset `{kid→pub}` + overlap window + old-key rotation
attestation** (the biggest commitment; today's pin is single); (3) **cross-hub revoke** object + authority +
propagation (CYP-747 S4 C3); (4) partition posture = fail-closed → NOT_TRUSTED → DENY.

**Arming is NOT in this block.** Even fully ratified, off-loopback stays DARK behind §9 — esp. **§9.3** (tunnel↔
credential binding, M2 G4 / CYP-532) — plus god-token close (CYP-828 ✅) and server-side re-auth. Ratifying the wire
+ trust Weichen unblocks **building** the federation; it does not arm it.

---

*Author: backend. Grounded on CYP-828 / CYP-747 (Model-2, `IssuerAnchor`/`operatorAuthChallenge`) / CYP-536 /
CYP-427 / CYP-638 / CYP-687 / CYP-93-S17 / CYP-434 / CYP-670. §3(+3.1) topology + §4b wire-envelope + §5
trust-anchor/rotation/revoke + §7 BYOA/BYODB all deep-passed. §10 = the PL Weichen bundle (unchanged since v0.3).
Trio (S-Fed-2/3/4a) BUILT + merged. Building the federation is unblocked by ratifying §4b/§5; arming stays DARK
behind §9.*
