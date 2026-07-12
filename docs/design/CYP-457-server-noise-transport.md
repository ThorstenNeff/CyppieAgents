# CYP-457/458/459 — Server-Noise-Transport (Design, ratified decomposition)

> Status: **Decomposition APPROVED (Design-Review 2026-07-12)** · Epic CYP-427 Phase-2 Remote · Backend lane
> Base: develop (CP-chain S-B/S-C/S-E/S-D complete + INERT) · Wire contract: `CYP-443-tunnel-framing-spec.md` (LOCKED)
> Client counterpart: CYP-443 (Dev) — `NoiseJavaClientTransport` INITIATOR, `Noise_NK_25519_ChaChaPoly_BLAKE2s`, noise-java 0.1.1
>
> **★ Build of the crypto-core (CYP-457) is GATED on the principal's RR5 risk-posture ratification** (the loopback
> carve-out is a risk-posture call, not a Backend decision). No crypto-core merge before that GO. This doc + the
> INERT-compiling seams are the ungated deliverable.

## 0. What this is
The **server (hub) side** of the remote tunnel: accept remote clients over a Noise-encrypted **outbound-only reverse tunnel** through an **untrusted relay**, and present the decrypted byte substrate (L2) to the **existing** Ktor HTTP/1.1 + WS routes **unchanged**. The hub is the Noise_NK **responder** (its X25519 `dhKey` static from S-C/CYP-441, private in the S-B/CYP-434 `SecretStore`). Built **INERT** — no live relay dial until an explicit Phase-2-Remote-GO.

Layer model (from the LOCKED CYP-443 spec):
```
App  the EXISTING hub HTTP/1.1 + WS (Ktor routes) — UNCHANGED
L2   ordered/reliable/full-duplex BYTE stream (TCP-like)
L1   Noise record: Noise_NK_25519_ChaChaPoly_BLAKE2s, sequential nonce, split() pair
L0   Relay frame: relay WS carries each Noise message as one opaque binary frame
```

## 1. Mechanism decision — Option A (loopback bridge), RR5-ratified path
Stack = **Ktor 3.5.0 on Netty**. `NettyApplicationEngine.start()` binds via `bootstrap.bind(host, port)` (an
`InetSocketAddress`) — so there is **no clean socket-less path** through Ktor's own start (Netty's in-VM
`LocalServerChannel` needs a `LocalAddress` Ktor won't supply). Options weighed: **A** loopback bridge (a
`127.0.0.1` listener the tunnel L2 is bridged to) · **B2** custom Netty-Local engine (socket-less but depends on
`ktor-server-netty`-internal handlers — non-public API, version-fragile) · **B1** hand-rolled HTTP/1.1+WS codec
(large new attack surface).

**Design-Review verdict (PO + RR5-author): Option A.** RR5's "no inbound/exposition" targets **PUBLIC** listeners; a
**loopback** listener is **carved out** (R5 local-mode already listens on loopback). This avoids B2's non-public-API
feasibility risk entirely. Option A carries **4 mandatory teeth** (§3) — the loopback bridge must never become an
auth-bypass. (B2/B1 recorded here only as the rejected alternatives.)

## 2. Build slices (3 — the Slice-0 spike is MOOT under A: loopback uses Ktor's native bind)

### CYP-457 — S1 Server Noise-Terminator (NK-responder) — CRYPTO CORE (Reviewer adversarial-gated; RR5-build-gated)
**Scope:** given a raw L0 frame source/sink, run the Noise_NK handshake as **responder** on the hub's `dhKey`
static, then expose the L2 byte substrate + `h`. Mirrors Dev's `NoiseJavaClientTransport` (INITIATOR) 1:1 — reuse the
`RelayChannel`/`NoiseTunnel` shape (+ `TestRelays`).
- **AC:**
  1. NK responder handshake completes against a CYP-443-style initiator; `h == getHandshakeHash()` is exposed
     post-handshake and **equal on both ends** (feeds S-E `cb`).
  2. ★ **Sequential-nonce fail-closed** (Noise-inherent; the `CipherState` owns the per-message nonce, not on the
     wire): a dropped / reordered / duplicated L1 frame → `AEADBadTagException` → the channel **errors** (RESET), no
     silent reorder/replay, no resync. **Money-tooth**; mutation (bypass the tag check / attempt resync) → replay
     accepted = RED.
  3. Zero plaintext before handshake completes; suite pinned `Noise_NK_25519_ChaChaPoly_BLAKE2s` (RR1), no
     negotiation; direction-independent cipher states (`split()` pair). **RR8**: no plaintext fallback.
  4. `dhKey` static consumed as the responder static from S-C `HubIdentity` (private via S-B `SecretStore`) — never
     generated ad-hoc.

### CYP-458 — S2 Relay-Connector (outbound-only) + Loopback-Bridge (Option A) — TRANSPORT — carries T1/T2/T3
**Scope:** the hub dials OUT to the relay WS, registers under an opaque rendezvous id, per accepted tunnelled
connection runs the Terminator → presents L2 to the existing Ktor routes via a **`127.0.0.1`-only loopback bridge** →
funnels into the CYP-410 `SessionManager`.
- **AC:**
  1. ★ **Outbound-only (RR5):** the connector opens the connection to the relay; **no external listener** for the
     tunnel. (The internal bridge is loopback-only — see T3.) Any external/wildcard bind/listen path = RED.
  2. ★ **Truncation-guard (§4):** relay drop / L0 close / decrypt-fail surfaces as a **connection RESET** (in-flight
     uncertain), **never** a clean App EOF; clean EOF only when the in-Noise App layer signals it. Mutation:
     relay-close → clean EOF = RED (a malicious relay could truncate a response).
  3. **Opaque rendezvous (RR4):** the relay sees only the opaque id + ciphertext + sizes/timing; no hub
     identity/payload in the clear.
  4. **1 L2 connection per tunnel** (no mux, §5); **≤8** in-flight frames, bounded channel, no OOM (§3).
  5. Existing Ktor routes run **UNCHANGED** over L2 (no API changes).
  6. **T1** · **T2** · **T3** (§3) — the loopback-bridge security teeth.

### CYP-459 — S3 Boot-Wiring (INERT) — carries T4
**Scope:** wire RelayConnector + Terminator into boot, **opt-in** (parity with the `CYPPIE_MASTER_KEY`-gated S-C
wiring / the governor), INERT until Phase-2-Remote-GO.
- **AC:**
  1. **Opt-in:** only when Phase-2-remote is configured (flag/env) does the hub dial the relay; else off → local boot
     unchanged (no behavior change).
  2. **RR3 auth:** a tunnelled connection is authenticated by S-E `CpJwtVerifier` with the **live `h`** from CYP-457
     (`aud==hubId` + channel-binding) before it reaches any route.
  3. **INERT:** no live relay dial, no deploy, until an explicit GO.
  4. **T4** (§3) — the non-re-presentable in-process principal tooth.

## 3. The 4 mandatory teeth (Design-Review, Option A)
- **T1 (CYP-458) — loopback-without-auth → reject** (+ **positive precondition**, non-vacuous): a request over the
  loopback bridge with **no valid credential/session is rejected** by the existing auth guard exactly as a network
  request; the positive leg proves a properly-authed request over the SAME loopback **is served** (so T1 isn't
  vacuously green because everything is rejected). The bridge grants **zero** implicit trust.
- **T2 (CYP-458) — ★ HEADLINE — transport-injected "already-auth" marker spoofed → reject.** The loopback bridge must
  NOT be able to assert authentication (e.g. inject a header/attribute a route might read as "already authenticated"
  because it is local). The route **re-verifies the real credential/session** (S-E `CpJwt` / Kratos), **never** a
  transport claim. Tooth: a forged "already-auth" marker on a loopback request → still rejected; mutation (a route
  trusts the transport marker) → the forged request is served = RED. **This is the loopback-becomes-auth-bypass guard.**
- **T3 (CYP-458/459) — bind == 127.0.0.1, never 0.0.0.0.** The bridge listener binds ONLY loopback; assert the bound
  address is loopback (never a wildcard/public interface). Mutation (bind `0.0.0.0`) → RED. Keeps RR5's carve-out valid.
- **T4 (CYP-459, flag) — non-re-presentable in-process principal at the L2 WebAuthn session.** The in-process
  principal derived for a tunnelled connection is NOT replayable/re-presentable as a WebAuthn-authenticated operator
  session (an in-process trust marker cannot be laundered into the operator-auth surface). Tied to the Phase-2
  operator-auth binding (RR3 + WebAuthn CYP-427).

## 4. §7 [RECONCILE] resolutions (Backend ← the LOCKED CYP-443 spec)
The four CYP-443 §7 `[RECONCILE]` points, resolved from the Backend/hub-terminator side (both terminators now agree):
1. **§4 Close-semantics — CONFIRMED (Reset, not EOF).** The hub terminator treats a **relay drop / L0 close /
   decrypt failure as a RESET** (in-flight uncertain), **never** a clean App EOF. A clean close is only surfaced when
   the **in-Noise App layer** signals it (HTTP/1.1 `Connection: close` + hub closing L2, or the WS close frame — both
   encrypted). This is the truncation-attack guardrail (= CYP-458 AC2 / T-context).
2. **§5 Connection cardinality — CONFIRMED (1 L2 connection per tunnel, no mux).** The tunnel carries one keep-alive
   HTTP/1.1 connection + WS upgrades, exactly as the local transport does today (mirrors `sharedWsHttpClient`). Client
   opens N tunnels if it needs concurrency; the hub terminator accepts **one** L2 connection per tunnel. (= CYP-458 AC4.)
3. **§1 Relay flavor — CONFIRMED (message-preserving WS, no length prefix).** MVP = the relay WS preserves message
   boundaries; **no** 2-byte length prefix. (If a byte-stream relay is ever used, both ends adopt the prefix — not MVP.)
4. **§3 Backpressure bound — CONFIRMED (≤ 8 in-flight frames).** The hub terminator's bounded in-flight cap matches
   the client's **≤ 8**; the write path suspends when the relay send buffer is full (bounded channel, never unbounded
   → no OOM). (= CYP-458 AC4.)

## 5. Cross-cutting / threat model
- Untrusted relay: ciphertext + metadata (sizes/timing) only; ZK scoped to payload (not padded — not over-promised).
- Truncation attack → §4 guard (CYP-458 AC2). Replay/reorder → sequential nonce (CYP-457 AC2). MITM → NK pins the
  responder static (the client knows `dhPubKey` from the CP registry; a wrong static fails the handshake).
- Loopback-as-auth-bypass → T1/T2/T3 (the bridge is a byte pipe, never an authz claim).
- The CP-chain closes here: S-C `dhKey` static → CYP-457 `h` → S-E `cb` → RR3 auth.
