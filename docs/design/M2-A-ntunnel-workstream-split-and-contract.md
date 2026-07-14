# M2 Option A (N-Tunnel) — Workstream Split & Coupling Contract

> Status: **ratification-ready v1** (PO-authored kickoff artifact) · Owner: PO (architecture + contract + integration gate)
> Foundation merged: develop `e365f078` (= verified `5562f9f2`, Reviewer-GO 5/5, INERT until remote-connected)
> Related: CYP-427 (M2 epic), CYP-535 (H7 backpressure), CYP-532 (OIDC hardening, ratification-gated), CYP-197 (multi-op/BYOA, future)

This doc is the **single source of truth** for the A-build. Team-1 (co-located) owns the coupled core; Team-2 (via po2, another host) owns the separable seams **behind the frozen contracts in §4**. Interface between PO and po2 = **written specs (this doc) + green SHAs**.

---

## 1. Goal & the problem it solves (F-M2-1)

After CONNECT the mode-blind workspace (CYP-411) opens ~8 eager persistent WS (Capacity/Lifecycle/Comm/Events/ACL/TokenUsage/Busy/TerminalControl) + per-agent WS. **One Noise tunnel = one duplex byte-stream** (no mux, spec §5). One tunnel therefore deadlocks on the first persistent WS → the workspace hangs (F-M2-1). **Option A = N tunnels**, one per concurrent logical connection, giving a true live remote hub with full WS streaming.

**Non-goal (deferred):** live-op decoupling / multi-operator (CYP-532/197). Single enforced operator stands.

---

## 2. Architecture (grounded in the merged foundation)

The load-bearing seam already exists and is per-connection:

```kotlin
// app/shared/.../net/hub/RemoteTunnelHubTransport.kt
class RemoteTunnelHubTransport(private val tunnelSource: TunnelSource, …) {
    // per accepted loopback connection:  val tunnel = tunnelSource.acquire()  (line 55)
}
fun interface TunnelSource { suspend fun acquire(): NoiseTunnel? }   // ← the N-tunnel swap point
```

Today `acquire()` hands back **one shared** tunnel → F-M2-1. **N-tunnel = a pooling `TunnelSource`** whose `acquire()` establishes a **distinct** `NoiseTunnel` per logical connection (up to a cap), each over its **own rendezvous-id**, each PoP-authenticated.

- **Relay = 0 changes.** It already pairs 1↔1 per rendezvous-id (`RendezvousRelayDialer`). N distinct rendezvous-ids = N independent pairings. No relay protocol change, no mux (§5 stays intact).
- **Server:** hub + CP accept **N concurrent** tunnels; per-tunnel Noise handshake + PoP verify + session/TTL.
- **Client:** pooling `TunnelSource` dials N rendezvous-ids via `NoiseJavaClientTransport.connect(pinnedHubStatic, relay, prologue)`; maps each acquire() to a fresh tunnel; owns pool lifecycle + cap.
- **Auth cost-driver:** each tunnel PoP would trigger a WebAuthn user-verification. N tunnels ≠ N prompts → **PoP-per-tunnel UV-caching** (cache one UV, derive N PoPs within a session).
- **Backpressure:** per-tunnel bound at the pump (`ClientLoopbackBridge.pump`, CYP-535) + an **aggregate cap** across the pool.

---

## 3. Workstreams

| # | Workstream | Team / Owner | Primary module(s) | Coupling |
|---|---|---|---|---|
| **WS1** | Server: N-concurrent tunnel accept + CP N-mint + per-tunnel session/TTL + PoP verify | **Team-1 Backend** | `:server`, control-plane | **Coupled core** |
| **WS2** | Client: pooling `TunnelSource` (per-acquire distinct tunnel, N rendezvous-ids, pool lifecycle/cap) + transport wiring | **Team-1 Dev** | `app/shared` jvmMain net/hub | **Coupled core** |
| **H7** | Backpressure: per-tunnel bound at the pump + aggregate pool cap (CYP-535) | **Team-1 Dev** (bridge owner) | `ClientLoopbackBridge`, pool | core-internal |
| **WS3** | PoP-per-tunnel **UV-caching** + per-tunnel PoP derivation | **Team-2 (Dev5/Backend2)** | `app/shared` operator-auth | behind **C1** |
| **WS4** | E2E **N-tunnel harness** — extend Tier-B to N concurrent tunnels (datapath + god-token-per-tunnel + backpressure) | **Team-2 (Tester2)** | `:e2e` | behind **C2/C3** |
| **WS5** | Per-tunnel **status UX chrome** (pool state: connecting/up/down/backpressured, aggregate) | **Team-2 (UIUX2)** | `app/shared` workspace chrome | behind **C3** |
| **WS6** | **Adversarial security review** of the N-tunnel credential/PoP/rendezvous/pool path (continuous) | **both Reviewers** | — | reads all |

**Rule:** WS1↔WS2 iterate against each other over the tunnel-establishment + credential protocol → **kept co-located on Team-1**. WS3/4/5 build against the **frozen §4 contracts** → parallel on Team-2, no fast cross-team loop needed.

---

## 4. Coupling contracts (FROZEN for parallel build)

These seam **shapes** are frozen now so Team-2 starts immediately; the core team (WS1/WS2) fills the *internal* details without changing the shapes. Any shape change = PO-ratified doc revision, relayed to po2.

### C1 — Tunnel credential + PoP protocol (WS1 server-verify ↔ WS3 client-PoP)
> **Hardened per WS6 review + reconciled with the PUBLISHED C1 format (CYP-536 `2745f1b8`, `docs/design/CYP-536-C1-tunnel-credential-pop-format.md`).** The binding is FROZEN, not TBD — leaving it TBD risked WS3 building a conformant-but-insecure provider on another host.

- **FROZEN binding — NO new format:** each of the N tunnels uses the *existing* RR3 PoP (`:core operatorAuthChallenge`, CYP-473), evaluated once per tunnel over **that tunnel's own live Noise channel-binding `h_i`**. Challenge bytes = `LP(h_i) ‖ LP(hubId.utf8) ‖ LP(nonce_i) ‖ LP("operator-auth")` (LP = 4-byte-BE len-prefix); transcript = `Ed25519.sign(deviceKey, challenge)` (raw, the Linux/MVP branch). **The PoP MUST bind to `h_i`, NEVER to `rendezvousId`** (rendezvousId is client-chosen + relay-visible → binding to it drops anti-MITM channel-binding). **3 per-tunnel uniqueness rules:** **R1** sign against THAT tunnel's own `h_i` (foreign `h` → `bad_signature`); **R2** fresh single-use `nonce_i` (shared nonce → tunnels 2..N get `nonce_replayed`, hub burns it on tunnel 1); **R3** one `cpJwt_i` per tunnel, `cb`-bound to `h_i` — this is "CP N-mint": CP issues N tokens/session, **no CpJwt shape change, only count.**
- **UV-cache = PRESENCE/ACCESS-GATE ONLY, never a signature substitute.** One UV ceremony per session, cached → N PoPs, 0 extra prompts. Linux/Raw (the [[no-hardware-no-shortcuts]] CYP-525 path): unlock the persistent Ed25519 key ONCE, then N plain signs — each still over its own `h_i`+`nonce_i`. A *reused* assertion/PoP across tunnels IS the forbidden "cached-UV = unbounded-reuse window."
- **Seam:** `PerTunnelPoPProvider { openSession(): UvAssertion?; popFor(rvid, challenge): ByteArray }`. **WS2 (client transport) OWNS challenge assembly** (it holds `h_i`/`nonce_i`/`cpJwt_i`, so nonce↔sig stay coupled); **WS3's `popFor` is a PURE SIGNATURE** over the caller-assembled challenge and does NOT construct the binding. `rvid` is **correlation/logging ONLY — MUST NOT enter the signature.** This decomposition *structurally* eliminates the bind-to-rvid footgun: the assembler (WS2) holds `h_i` and uses it; WS3 can only sign what it's given. WS3 builds against a fake until it consumes the published format.
- **God-token-reject per tunnel (WS1):** `TunnelGodTokenGuard` (f2dd508b) refuses the static OPERATOR_TOKEN on **every** tunnel's auth channel. With N connectors the guard discriminator must check the **port SET**, not a single `tunnelPort`. **Revocation must fan out to ALL N** operator-sessions.
- **WS6 verifies:** `popFor` is a pure sign, `rvid` is crypto-inert, R1/R2/R3 hold, UV-cache is access-gate-only (no per-`h_i` signature substitution).

### C2 — `TunnelSource` N-semantics (WS2 ↔ foundation/transport)
- **Frozen interface (unchanged signature):** `fun interface TunnelSource { suspend fun acquire(): NoiseTunnel? }`.
- **Frozen semantics under N:** each `acquire()` returns a **distinct, live, PoP-authenticated** `NoiseTunnel` over its own rendezvous-id, up to `poolCap`. `null` = fail-closed (transport RSTs the connection, never local fallback — preserves the `noLiveTunnel_failsClosed` invariant). The pool owns tunnel lifecycle; closing the transport closes the acceptor but **not** session-owned tunnels (existing `close_closesAcceptor_butNotTheSessionOwnedTunnel` invariant holds per-tunnel).
- **`poolCap`:** single-sourced const, initial value set by WS2 from the eager-WS count + headroom (≈ 10–15); referenced by H7 aggregate + WS4 harness.

### C3 — Per-tunnel status/observability (WS2 ↔ WS4 harness, WS5 UX)
- **Frozen:** the transport emits a per-tunnel state stream `TunnelPoolState` = list of `{ rendezvousId, state ∈ {DIALING, UP, BACKPRESSURED, DOWN}, sinceTs }` + `aggregate { active, cap, anyBackpressured }`. WS5 renders it; WS4 asserts on it. WS2 owns the emitter; the **enum + shape are frozen here**.

### C4 — Rendezvous allocation (WS1 relay/hub ↔ WS2 client dial)
- **Frozen:** N **distinct** rendezvous-ids per session, client-allocated and dialed via `RendezvousRelayDialer`; relay unchanged (independent 1↔1 pairings). Server accepts any validly-PoP'd tunnel on any of the session's rendezvous-ids. **No shared rendezvous-id across tunnels.**

### C5 — Additional frozen security invariants (WS6 hardening)
- **Server-side per-operator tunnel CAP (WS1) — DoS floor.** Client `poolCap` (C2) is *necessary-but-not-sufficient*: the hub MUST enforce its OWN per-operator concurrent-tunnel cap, so a client bug or attacker cannot spawn unbounded tunnels. Exceeding it → refuse the NEW tunnel (RST), never degrade an existing one.
- **Per-tunnel credential re-verification, no cross-talk (C2/WS2).** Each of the N routes re-verifies its OWN credential — the foundation "each route re-verifies its cred" invariant holds **per tunnel**. No session-state is shared or trusted across tunnels; a valid tunnel #k grants nothing to tunnel #j.
- **H7 aggregate = ONE shared counter across N pumps (CYP-535).** The pool-wide backpressure cap is a single shared counter over all N pumps, NOT a per-pump bound applied ×N. Cap-hit stays truncation-safe (block-the-pump backpressure, no silent drop, no Noise-stream corruption); RST-on-null stays orthogonal.

---

## 5. Integration protocol (how capacity → wall-clock, not a backed-up queue)

1. **Contract-first:** Team-2 builds against §4 shapes from day one (stubs/fakes for the not-yet-frozen internals, e.g. a fake `PerTunnelPoPProvider`).
2. **Each host self-gates its branch fully green** on its own box (full `:server` / `:app:shared:jvmTest` / `:e2e` suites) and hands the PO a **green SHA + test tallies**. Team-2's runs are on po2's host (free parallel compute, off the PO's OOM-fragile box).
3. **PO runs the lighter integration gate** on the merged tree: revert-guard (`merge-base --is-ancestor` + `--diff-filter=D`) + clean-merge + affected-module tests on the *merged* result (NOT a cold full re-run). PO is sole develop merge-owner (both teams).
4. **Batch-integrate coherently:** related branches merged as integration batches, not one merge per tiny branch, so upstream capacity doesn't back up behind the PO gate.
5. **Escalation:** architecture questions (about this design) → po2 relays to PO → PO answers same-loop → po2 relays back. PO stays reachable.
6. **No deploy** without explicit Auftraggeber GO (staging stays `b2d4516b`; default agents PRESERVE byte-identical).

---

## 6. Sequencing

- **Now:** WS1 publishes the C1 challenge format + confirms C2/C4 shapes (day-1 core spike); WS2 stands up the pooling `TunnelSource` against C2; Team-2 starts WS3 (against C1 fake), WS4 (against C2/C3), WS5 (against C3) in parallel; WS6 reviews each seam as it lands.
- **Converge:** WS1+WS2 prove **2 concurrent tunnels** end-to-end (the first real N>1 datapath) → then scale to `poolCap`; H7 lands with the pool; WS4 harness graduates from 2→N.
- **Gate to "M2 done":** the N-tunnel workspace operates the hub with **live WS streaming over N tunnels**, god-token-reject holds per-tunnel, H7 bounds hold, N-tunnel E2E green, security review signed off. Then Auftraggeber deploy GO + combined M1+M2 dogfood.
