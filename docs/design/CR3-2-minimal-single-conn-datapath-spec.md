# CR3-② (Path A) — Minimal Single-Connection Datapath: build-ready spec

> Status: **design/analysis only — NOT built.** Shovel-ready for Auftraggeber-GO. Mux-non-prejudicing.
> Depends on: CR3-① `ClientLoopbackBridge` (merged in the CYP-525 post-merge batch). Object-grounded against develop `4572a278`.
> Companion: `cr3-datapath-scope` (the A-vs-B fork). Path B (full live workspace = WS + concurrency) is the separate mux-ratification item.

---

## 0. Goal & the one invariant

Land the **sequential-REST dogfood** — the operator issues discrete workspace calls (`GET /api/agents`, send a
message) **one at a time** over the real Noise tunnel — with **no new engine** and **no mux**. The whole design rests on
ONE invariant:

> **Exactly one loopback connection is pumped over the single L2 tunnel at a time** (Spec §5 "one L2 per tunnel, no mux").

Everything below preserves that. The full mux/multi-tunnel decision (Path B) is **untouched** — Path A is additive.

---

## 1. `RemoteHubTransport.jvm` rewire — concrete steps

Today `app/shared/src/jvmMain/.../net/hub/RemoteHubTransport.jvm.kt` is the fail-loud actual
(`remoteTransportNotYetAvailable()`, CYP-411). The `RemoteHubTransport` `expect class` implements `HubTransport`
(`httpBaseUrl`, `wsBaseUrl`, `httpClient`, `sessionToken()`, `close()`). Rewire steps:

1. **Env-gate (fail-closed → INERT).** Activate ONLY when the remote transport is enabled (mirror the server
   `buildRemoteTransport` env gate). Not enabled → keep `remoteTransportNotYetAvailable()` (unchanged INERT). No
   speculative activation.
2. **Compose + start the session.** Build a `RemoteHubSession` via the existing jvm factory
   (`RemoteHubMode.jvm.kt` `liveRemoteConnectComponentsFactory`), `session.start()`. (Seam note: the `expect class
   RemoteHubTransport()` no-arg ctor must gain a factory/DI seam — the actual needs the session + config. Additive:
   route it through `TransportModeResolver`, which already selects LOCAL vs REMOTE.)
3. **Await CONNECTED (bounded).** Collect `session.state` until `conn == RemoteConnState.CONNECTED`, then read
   `session.tunnel` (non-null at CONNECTED). A bounded timeout → fail-loud/INERT (never a half-open transport).
4. **Bind a loopback ServerSocket.** `ServerSocket().bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))`
   (ephemeral port). ★ **Assert `serverSocket.inetAddress.isLoopbackAddress` (F2)** before use; read the actual bound
   port. Never `0.0.0.0` / wildcard — the workspace bytes must never leave the host in cleartext.
5. **Single-in-flight sequential accept-loop** on a background coroutine over the session scope:
   ```kotlin
   val bridge = ClientLoopbackBridge("127.0.0.1")
   while (isActive) {
       val socket = serverSocket.accept()                 // blocks for the next workspace connection
       val tunnel = session.tunnel ?: break               // lost CONNECTED → stop accepting (fail-closed)
       bridge.pump(tunnel, RealBridgeConn(socket))         // pump-to-RST, THEN loop → the NEXT accept
   }
   ```
   The loop is **structurally single-in-flight**: `pump` blocks until the connection ends (RST), so the next `accept()`
   only runs after the current one finishes. A 2nd client that connects mid-pump waits in the OS accept backlog. An
   explicit `Semaphore(1)` around `pump` is the belt-and-suspenders invariant marker (and the seam the single-in-flight
   tooth asserts). **No mux, no multi-tunnel — one L2/conn at a time (§5).**
6. **Expose the seam.** `httpBaseUrl = "http://127.0.0.1:$boundPort"`; `httpClient = sharedWsHttpClient(...)` configured
   **connection-pool = 1** (see §3 caveat); `sessionToken()` = the operator credential; `close()` = stop the loop +
   `serverSocket.close()` + `session.close()`. `wsBaseUrl` is exposed for shape-compat but the live-WS UX is **deferred**
   (§4) — a WS upgrade holds the single tunnel open and starves every other call.

**Result:** the unmodified workspace Ktor client dials `http://127.0.0.1:<port>`; each REST call is a fresh
Content-Length-framed connection pumped over the tunnel and RST-closed; the unmodified hub routes serve it. Sequential.

---

## 2. Test-harness design — the two ②-gate real-socket bars

CR3-① proved the `pump` logic over the fake `BridgeConn` seam. These two are the **real-TCP** proofs the ②-gate adds.

### F1 — real-socket truncation proof (RST aborts, never a truncated-as-clean body)
- Stand up `ClientLoopbackBridge` on a **real** loopback `ServerSocket`; run the accept→pump loop.
- Inject a **fake `NoiseTunnel`** that: absorbs the request (`send`), yields a **PARTIAL** response whose
  `Content-Length` header promises MORE than it delivers (e.g. `Content-Length: 100` + 12 body bytes), then
  `receive()` returns `null` (the induced relay drop).
- Drive it with a **real client**: either a raw `java.net.Socket` (write the request, read the response) OR a real Ktor
  `HttpClient` issuing `GET /api/agents`.
- **Assert:** the client observes a **truncation error** — a raw socket `read()` throws `SocketException:
  Connection reset` (from the bridge's SO_LINGER-0 RST); a Ktor client `assertFailsWith<IOException>` (the response is
  incomplete vs its `Content-Length` → the client errors). ★ **NEVER** a completed 200 with a short body.
- Mutation (clean-close instead of RST) → the client reads a truncated body as a clean EOF → the assert RED. (This is
  the real-socket analog of CR3-①'s `MUT clean-close → RED`, now over TCP.)

### F2 — loopback bind-assert (the BOUND address, not a config string)
- After `serverSocket.bind(...)`, **assert on the bound socket**: `serverSocket.inetAddress.isLoopbackAddress == true`
  **AND** `!serverSocket.inetAddress.isAnyLocalAddress` (never the wildcard). This checks what the OS actually bound,
  not the config intent.
- Negative: a non-loopback host → `ClientLoopbackBridge`'s `require(isLoopbackAddress)` (and the transport's bind
  guard) fail-closed (`assertFailsWith<IllegalArgumentException>`).

### Plus — single-in-flight tooth + e2e receipt
- **Single-in-flight:** open a 2nd loopback connection while the 1st is mid-pump; assert the 2nd is **not** pumped
  concurrently (it waits — observable via the `Semaphore(1)` permit / the tunnel seeing strictly serialized traffic).
  Proves no accidental mux.
- **e2e receipt (dogfood proof):** one real `GET /api/agents` over the ACTUAL Noise tunnel (real client transport ↔
  server LoopbackBridge ↔ real hub routes) returns the real agent list — the honest "datapath works" receipt.

---

## 3. Client-side caveat (Dev's lane) — connection-pool = 1

The workspace **Ktor client** must be constrained to **one connection** (`Engine { pipelining = false ; maxConnectionsPerRoute = 1 }`
/ CIO `endpoint.maxConnectionsPerRoute = 1`) OR the operator flow must be genuinely sequential. Otherwise a screen-load
that fires several REST calls in parallel opens concurrent connections → the single-in-flight tunnel serializes them →
UI stall. This is client config (Dev owns the `HubTransport` REMOTE `httpClient`), **co-ratified with the Path-A build**.

---

## 4. Explicitly deferred to Path B / the mux decision

Path A does **not** attempt (and must not silently half-attempt) any of these — each inherently needs concurrency over
one tunnel = the mux/multi-tunnel ratification:
- **Concurrent connections** — parallel REST + the multiple live WS the workspace opens.
- **WebSocket over the tunnel** — the live UX (comm-panel timeline, agent windows, lifecycle, token-usage, busy-state).
  A WS upgrade holds the single tunnel open indefinitely → starves every other call. `wsBaseUrl` is exposed for
  shape-compat only; live-WS is Path B.
- **Connection-close-framed clean-vs-abort signal** — Path A's RST-always is correct only for **Content-Length-framed**
  responses (the durchstich's `GET`). Connection-close framing needs a real clean-vs-abort signal from the tunnel/session.
- **HTTP keep-alive** — RST-per-response kills it (fine for §5 no-mux; the perf win is Path B).
- **Web / iOS** — Path A is JVM/Desktop only (`ServerSocket`). The multiplatform HTTP/1.1+WS-over-bytes engine is Path B.

---

## 5. One-line scope statement (for the Auftraggeber)

**Path A is shovel-ready, mux-non-prejudicing, and proves the datapath end-to-end — but it lands sequential-REST-only
(operator issues discrete calls one at a time), NOT the live-streaming workspace.** If the "orchestrate over the tunnel"
dogfood = discrete sequential operator REST actions → Path A now (mux-free). If it = the live streaming workspace → that
is Path B = the mux ratification first. The recommended sequence is **A now → B after the mux decision** (A is additive,
never thrown away — the server LoopbackBridge + CR3-① bridge + this transport all carry into B).
