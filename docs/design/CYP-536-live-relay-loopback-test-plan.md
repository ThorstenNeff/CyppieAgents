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
