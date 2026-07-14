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
- **Frozen:** each of the N tunnels authenticates with a **per-tunnel Proof-of-Possession** bound to the operator device key (`PersistentOperatorDeviceKey`), presented in the Noise `prologue`/first authenticated frame. The **UV (WebAuthn user-verification) is performed ONCE per session and cached**; the N per-tunnel PoPs are derived from the cached UV assertion (no N prompts). Fail-closed: absent/invalid PoP on any tunnel → server refuses THAT tunnel (RST), never a fallback.
- **TBD-by-WS1 (does not block WS3):** exact PoP challenge bytes + signature transcript. WS3 builds against a `PerTunnelPoPProvider` seam: `suspend fun popFor(tunnelIndex/rendezvousId, challenge): ByteArray` + `cachedUv: UvAssertion?`. **WS1 publishes the challenge format first; WS3 consumes it.**
- **Security invariant (WS6 guards):** the god-token-reject (`TunnelGodTokenGuard`, f2dd508b) applies **per tunnel** — the static OPERATOR_TOKEN is refused on every tunnel's auth channel; only the CP-scoped operator session passes.

### C2 — `TunnelSource` N-semantics (WS2 ↔ foundation/transport)
- **Frozen interface (unchanged signature):** `fun interface TunnelSource { suspend fun acquire(): NoiseTunnel? }`.
- **Frozen semantics under N:** each `acquire()` returns a **distinct, live, PoP-authenticated** `NoiseTunnel` over its own rendezvous-id, up to `poolCap`. `null` = fail-closed (transport RSTs the connection, never local fallback — preserves the `noLiveTunnel_failsClosed` invariant). The pool owns tunnel lifecycle; closing the transport closes the acceptor but **not** session-owned tunnels (existing `close_closesAcceptor_butNotTheSessionOwnedTunnel` invariant holds per-tunnel).
- **`poolCap`:** single-sourced const, initial value set by WS2 from the eager-WS count + headroom (≈ 10–15); referenced by H7 aggregate + WS4 harness.

### C3 — Per-tunnel status/observability (WS2 ↔ WS4 harness, WS5 UX)
- **Frozen:** the transport emits a per-tunnel state stream `TunnelPoolState` = list of `{ rendezvousId, state ∈ {DIALING, UP, BACKPRESSURED, DOWN}, sinceTs }` + `aggregate { active, cap, anyBackpressured }`. WS5 renders it; WS4 asserts on it. WS2 owns the emitter; the **enum + shape are frozen here**.

### C4 — Rendezvous allocation (WS1 relay/hub ↔ WS2 client dial)
- **Frozen:** N **distinct** rendezvous-ids per session, client-allocated and dialed via `RendezvousRelayDialer`; relay unchanged (independent 1↔1 pairings). Server accepts any validly-PoP'd tunnel on any of the session's rendezvous-ids. **No shared rendezvous-id across tunnels.**

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
