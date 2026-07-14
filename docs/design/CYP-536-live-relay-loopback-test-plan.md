# CYP-536 — Live-Relay Loopback E2E Test Plan + Harness Sketch (for Backend/Team-2)

> Reviewer deliverable (test **design**, not build — Backend/Team-2 owns the committed test; Reviewer gates the green SHA). Closes the one remaining unproven point: all M2 e2e so far uses an **in-memory relay** (`InMemDuplex`). This proves the datapath over a **real relay WS socket** on loopback — **no staging deploy needed**.

## Conclusion: loopback suffices for datapath correctness

The "1 unproven point" is the **real relay**, not WAN. Loopback sockets are **real sockets** (real TCP/WS stack, real framing, real async I/O, real rendezvous pairing) — exactly what `InMemDuplex` bypasses. The real relay is CI-startable:

- `server/.../relay/RelayServer.kt` → `fun Application.relayModule(relay: RendezvousRelay = RendezvousRelay())` installs `WebSockets` + a `webSocket("/relay")` route that reads `X-Cyppie-Role` (hub/client) + `X-Cyppie-Rendezvous`, and pairs peers via `relay.join(WebSocketRelayPeer(...))`. There is also a standalone `main()` (`:server:relayRun`).
- `app/shared/.../relay/KtorWsRelayConnector.kt` already dials it with the role + rendezvous headers; `Cyp494RelayWsConnectorE2eTest` drives the real connector against a real relay over loopback (the startup building block).

**What loopback proves** (datapath correctness): real relay pairs hub↔client on a rendezvous-id · real WS framing (1 binary frame = 1 Noise message, no length prefix) · **Noise-NK handshake over the real relay** (distinct live `h`) · **RR3 gate over real frames** · workspace request over the real tunnel · god-token-reject + anti-replay over the real path.

**What loopback does NOT prove** (ops-validation → staging, Auftraggeber-deploy-gated, NOT datapath correctness): WAN latency/loss/MTU · `wss://` TLS-to-relay (orthogonal — Noise is the E2E security boundary; the relay is a dumb pipe of opaque frames, so TLS-to-relay is defense-in-depth, not the boundary) · NAT / reconnect-over-network.

## Test: `Cyp536LiveRelayLoopbackE2eTest` (`:e2e`)

= the joint e2e (`Cyp536JointNTunnelAuthE2eTest`) with `InMemDuplex` swapped for the **real `relayModule()`** over `embeddedServer(Netty, port=0, host="127.0.0.1")`. Ungated (test-only, no prod code, proves existing behavior).

### Teeth

- **T1 (core datapath):** a real Noise tunnel pairs over the real relay WS (loopback) → RR3 authenticates (real CpJwt + PoP over the live `h`) → `GET /api/agents` = **200 + real roster** over the real path.
- **T2 (god-token over the real path):** the static operator (god) token over the tunnel connector → **401** (the guard holds over real frames, not just in-memory).
- **T3 (N-scale bridge):** **2 concurrent tunnels** on 2 distinct rendezvous-ids over the real relay → both authenticate + carry requests (the 2-tunnel milestone over a real network).
- **T4 (anti-replay over the real path):** tunnel-K's PoP replayed onto tunnel-J → `bad_signature`; a reused nonce on the shared gate → `nonce_replayed`.
- **★ T5 (the keystone — anti-vacuity):** assert the relay **actually relayed** — `spyRelay.frames(rendezvousId) > 0`. The in-memory path would show **0** relay frames. **Without T5 the whole test can pass vacuously green without ever touching the real relay.** This is the discriminator between "went through the real relay" and "in-memory shortcut." *(Reviewer will verify T5 is present + non-vacuous, and that the platform really wires `relayModule()` rather than `InMemDuplex`, at the gate.)*

### Harness sketch (grounded on the real APIs)

```kotlin
// 1. REAL relay (Ktor WS) on a real loopback port — with a spy to count relayed frames (T5).
val spyRelay = SpyRendezvousRelay()           // wraps RendezvousRelay, counts frames per rendezvousId
val relay = embeddedServer(Netty, port = 0, host = "127.0.0.1") { relayModule(spyRelay) }.start(wait = false)
val relayUrl = "ws://127.0.0.1:${relay.engine.resolvedConnectors().first().port}"

// 2. REAL hub: BootOrchestrator + Rr3TunnelGate + ConcurrentRelayResponderManager;
//    the hub's NoiseRelayConnector dials relayUrl as role=hub on rendezvousId (real WS socket).
val hub = bootRealHub(relayUrl, rendezvousId, gate, responderMgr, scope)

// 3. REAL client transport: RendezvousRelayDialer(KtorWsRelayConnector(relayUrl), resolver→rendezvousId), role=client.
val client = buildRemoteHubTransport(realRelayDialer(relayUrl, rendezvousId), sessionToken, scope)

// 4. Real Noise-NK over the real relay → RR3 gate → workspace request.
val resp = client.get("/api/agents", agentToken)
assertEquals(200, resp.status.value)
assertTrue("backend" in resp.bodyAsText())
assertTrue(spyRelay.frames(rendezvousId) > 0, "★ T5: frames really traversed the real relay (in-memory would be 0)")
```

Mirror the real-relay startup from `Cyp494RelayWsConnectorE2eTest`; mirror the real Noise/RR3/workspace part from `Cyp536JointNTunnelAuthE2eTest` / `CypM2TierBTransportTest`. Optional higher fidelity: 3 real OS processes (hub / client / relay) — proves process-boundary/lifecycle too, but the datapath proof already stands with the loopback sockets.

**Staging run (WAN / TLS / NAT)** stays separate + Auftraggeber-deploy-gated (ops-validation, not datapath correctness).

---

## CYP-549 build result — T1 flake + fix (reviewer gate finding, 2026-07-14)

Backend built the test (`Cyp536LiveRelayLoopbackE2eTest`, `ab943c14`) to this plan and reported full `:e2e` **134/0**. The adversarial gate (real `--no-ff` onto current develop, per-axis mutation) confirmed the **substance** but caught a **timing-race flake** that Backend's single run missed.

**Substance verified (content-GO on the datapath):**
- **#1 T5 non-vacuity — proven:** neutralizing the `CountingRelayPeer` counter (`frames` stays 0, datapath still works) reds **T1/T3/T4 specifically on their T5 asserts** ("frames really traversed the real relay"), while **T2 (no T5) stays green** — i.e., "RED despite a working datapath". T5 genuinely discriminates real-relay from in-memory.
- **#2 real relay, not a cheated fixture:** `SpyRelay.install` is byte-faithful to the real `relayModule` handler and uses the real `RendezvousRelay` + `WebSocketRelayPeer` + `relay.join` — the only addition is the transparent `CountingRelayPeer` decorator. *(LOW: the hand-copied handler can drift from `relayModule` — recommend a drift-guard, or refactor `relayModule` to accept a peer-decorator so the test uses the real function.)*
- **#3 datapath proven:** T1–T4 green in 3/3 re-runs over the real relay.

**⚠ T1 flake (blocks a clean merge):** in the object-level run, **T1 failed once** at the 200-assert with an **empty response** (`Cyp536LiveRelayLoopbackE2eTest.kt:317`, `client.receive()` returned `null` fast = the tunnel closed before the response). Tally: **1 fail in 4 isolation runs**. The datapath works (T3 does the same `GET /api/agents` → 200 twice and is always green), so this is a **timing race, not a product defect** — a transient early `null` on the responder-`bridge()`-vs-GET window. Same class as the H6 flake CYP-541 just fixed; a 134/0 full-suite run is one lucky scheduling.

**Root cause:** `httpOverTunnel`'s `client.receive() ?: break` — a transient early `null` (the response hasn't been bridged yet) breaks the loop with `''`.

**Fix (routed to Backend):**
- **(a) [recommended, deterministic — the H6 "await-stable-state" pattern]:** the responder signals **bridge-readiness** (a `CompletableDeferred` completed immediately before `bridge()` begins pumping); the test **awaits that readiness before sending the GET** → removes the send-before-bridge race at the root. Consistent with the CYP-541 H6 de-flake.
- **(b) [cheaper fallback]:** make `httpOverTunnel` race-robust — distinguish "tunnel closed" from "response not yet arrived" (a bounded await/retry until the status line is complete OR a real EOF+timeout), not an immediate break on the first `null`.

**Re-gate criteria (when Backend re-pushes):** the T1 harden is (a) or (b); the class is green **N× over re-runs** (not one lucky run); T5 non-vacuity intact (the neutralize mutation still reds T1/T3/T4); and the LOW handler-drift note is addressed or accepted.
