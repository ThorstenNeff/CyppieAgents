# CYP-620 — Transport Replacement: App-Level Stream-Mux over One Noise Tunnel

> Status: DESIGN (for Assist review → Auftraggeber ratification → build with gates)
> Author: backend · Inputs: Dev (client transport), Dev5 (pool analysis, via po2) · Reviewer: Assist
> Supersedes the patch-path (CYP-609/611/616/618/619). Build is a rewrite, gates resumed, no patch-loop.

## 1. Problem & goal

The remote transport is **one-tunnel-per-stream**: each loopback connection (every WS + every REST call) needs its
own Noise tunnel, and the tunnels are drawn from a **bounded per-operator pool** (the WS6-axis-2 DoS cap, currently 24
→ 23 data-ids after the control id). The real workload — **~14 long-lived WS** (7 global singleton streams + one
agent-WS per agent) **+ concurrent REST** — exceeds the pool. Five patches (CYP-609 hub-FIN, CYP-611 cap→24, CYP-616
lifecycle-reserved-slot, CYP-618 relay asymmetric-close, CYP-619 client hard-evict backoff) removed the reset-storm and
the "Server unreachable", but the **DATA demand still exceeds the slot count → streams queue forever** = remote not
usable. The pool is a structural dead-end.

**Goal:** a **multiplexed** transport — many independent streams over **ONE** connection — so there is **no pool** and
no per-stream tunnel count to cap against demand.

## 2. The driving constraint (why this is not a free "swap the stack")

**E2E through the UNTRUSTED relay (RR4).** The relay pairs one hub + one client on an opaque rendezvous id and forwards
**opaque Noise frames verbatim** — it never decodes, parses, or inspects; it sees only ciphertext + the opaque id +
sizes/timing. The end-to-end **Noise** channel and the **RR3** auth (CpJwt ∧ operator-device-PoP bound to the live
handshake `h`) sit *off* the relay. Any multiplexing solution **MUST run INSIDE the Noise tunnel** — otherwise it
bypasses the RR3/RR4/E2E security layer (hard-won, Assist-audited) and would require re-auditing the whole envelope.
This constraint is the decision driver: it rewards reusing the Noise/relay/auth layer and penalizes replacing it.

## 3. Options evaluated

| Opt | E2E-through-untrusted-relay | Effort | Migration |
|-----|-----------------------------|--------|-----------|
| **A — app-level stream-mux over ONE Noise tunnel** | **FULL** — mux framing lives inside the Noise ciphertext; relay sees only opaque frames; RR4 unchanged | **M-L (~1–1.5 wk)** | **small** — pool collapses to ~1 tunnel; hub `LoopbackBridge` + client transport become mux/demux; **WS/REST routes UNCHANGED** |
| B — gRPC/HTTP2 | possible, but HTTP/2 must STILL run inside Noise (HTTP/2-over-Noise-over-relay) → you pay HTTP/2 **and** keep Noise | L-XL (~2–3 wk) | large — WS/REST → HTTP/2 transport rewrite + h2c-over-loopback wiring + dependency |
| C — QUIC/HTTP3 | poor — QUIC has its own TLS crypto → **double-crypto** with Noise; QUIC is UDP vs the TCP/WS relay; QUIC's no-HoL benefit is **moot over a reliable Noise tunnel** | XL+ (poor fit) | full transport replacement |
| D — Stream-Consolidation (CYP-612) | n/a (no transport change) | M (days) | app-layer — consolidate 7 singleton streams → ~2 |

### 3.1 A-vs-B conclusion (the core trade-off) + final recommendation

The trade-off is **hand-rolled-mux risk (A) vs HTTP/2-over-Noise cost (B)**. The decisive facts:

- **A and B have the SAME head-of-line profile.** Both mux over ONE reliable connection (TCP/WS under Noise), so both
  have TCP-level HoL. Only QUIC (C) avoids it — and that benefit is **moot over a reliable tunnel** and comes with C's
  disqualifying costs. So B does **not** buy a HoL improvement over A.
- **B does not let us drop Noise/relay.** HTTP/2 must run *inside* the Noise tunnel (constraint §2), so B keeps the
  entire Noise/relay/RR3 layer **and** adds HTTP/2 on top — the worst of both.
- **Our workload does not need HTTP/2's feature set.** ~14 low-bandwidth control/status WS + short REST. We need
  stream-id demux + per-stream backpressure + a lifecycle — **not** HPACK, server-push, priority trees, etc. A focused
  mux is smaller than adopting+wiring HTTP/2.

**Recommendation: OPTION A.** It preserves the full security envelope (zero RR3/RR4 re-audit), collapses the pool, is
the smallest blast radius, adds no dependency, and leaves the WS/REST routes untouched. **B is the explicit fallback**
if the hand-rolled mux (§4) proves too risky to get correct. **C is out.** **D is a complementary demand-reducer**
(fewer streams eases the mux load) but is *not* the fix — the pool remains; adopt D later if desired, independent of A.

**Effort: M–L (~1–1.5 weeks server-side)** — MuxBridge + frame codec + per-stream backpressure + lifecycle + a full
teeth suite. Client-side mux/demux is a parallel effort (Dev). No new dependency; Noise/relay/RR3/auth untouched.

## 4. Design (Option A)

### 4.0 RATIFIED APPROACH — library-A: a proven mux (yamux) over a Noise-msg↔byte-stream adapter

> Adjudicated by the CYP-620 owner (backend), converging with both review lenses (Assist Focus-5 + Backend2
> feasibility): **do NOT hand-roll the mux core.** The dangerous layer — credit-window flow-control, framing, stream
> lifecycle, fairness (the classic mux killers G1–G4: credit-deadlock / framing-desync / writer-HoL / teardown-race) —
> comes **PROVEN** from **yamux** (or its spec). We spent a full day on hand-rolled transport bugs; importing a proven
> flow-control design is the point.

- **Mux core = yamux's design** (length-prefixed frames + per-stream window-based flow-control + open/close/reset
  lifecycle). Off-the-shelf lib if a clean JVM **and** KMP one exists (**Build-task 0: portable-yamux availability
  check**); else implement the **yamux SPEC** on both sides with **conformance vectors** — still imports the proven
  *design* (the risk is the algorithm, not the byte-plumbing). §4.2 below is then the precise conformance target.
- **The ONLY two custom surfaces (both teeth-pinned):**
  1. **Noise-msg↔byte-stream adapter** — yamux wants a reliable BYTE-stream; `ServerNoiseTunnel` is **MESSAGE-framed
     (≤65535, byte-stream framing deferred CYP-443)**. The adapter chunks yamux's byte-stream into ≤65535 Noise
     messages on send and reassembles on receive, **boundary-preserving**. This IS the CYP-443 work. Its one real risk
     is **backpressure-passthrough** (M1/§4.4): yamux's window-credit MUST actually backpressure the Noise socket and
     propagate relay-TCP backpressure up into the windows — else the adapter buffers unbounded. Contained + teeth-pinned.
  2. **Control-stream priority scheduler** — the thin layer for Team2-invariant #4 (reserved control-stream capacity),
     since yamux has per-stream windows but no priority (§4.8.4).
- **Envelope config:** yamux defaults (256 streams × 256 KB = 64 MB) are re-configured to **our `maxStreams` cap + ≤256
  KB/stream** (§4.6, §4.8.6), single-sourced.
- **Fallback:** hand-rolled CYMUX (§4.2 as an actual wire format, with the heaviest G1–G4 + no-byte-bleed teeth) ONLY
  if no yamux lib is portable AND the spec-impl proves infeasible on KMP. Not expected.

The rest of §4 describes the design; where it says "the mux", read "yamux's mux core"; the §4.8 invariants are the
**wrapper** our code owns around the library.

### 4.1 Architecture & insertion points

The Noise tunnel, the relay, RR3 auth, and `TunnelSessionRegistry` are **unchanged**. The change is at the **tunnel
boundary**, entirely inside the E2E-encrypted channel:

- **★ Module layout — SHARED codec (compiler-enforced contract).** The yamux **codec** (frame parse/serialize + the
  per-stream window state-machine + the streamClass/hello framing, §4.2) is PURE LOGIC — no platform deps — so it lives
  in the shared **`:core`** common module (where the `@Serializable` `:protocol` DTOs already live). Hub-JVM and
  client-KMP both depend on it → **the wire contract is compiler-enforced, not merely conformance-tested** (a bilateral
  divergence bug is structurally impossible — one codec). Conformance vectors (in `:core` tests) validate the shared
  codec once against hashicorp/yamux. **Only the WIRING is per-side and platform-specific** (below).
- **Hub side (`:server`, backend):** today `LoopbackBridge` is a dumb 1-socket-per-tunnel pump. It becomes a **`MuxBridge`**: DEMUX inbound
  L2 frames by `stream-id` → dial/hold one **per-stream loopback socket** to the existing Ktor connector; MUX outbound
  per-stream socket bytes → `stream-id`-tagged frames back onto the ONE tunnel. The Ktor routes see the same per-stream
  loopback connections they see today — **no route changes**.
- **Client side (Dev's lane):** the client transport mirrors it — one mux/demux over its single Noise tunnel; each app
  WS/REST connection is a stream. Replaces `PooledTunnelSource`/`TunnelPoolState` with a single-tunnel mux.
- **Tunnels:** collapse to **1** live data tunnel per operator (optionally a tiny redundancy set for failover — TBD,
  not for count). The control tunnel (rendezvous element 0) is unchanged.

### 4.2 Frame format — THE bilateral wire CONTRACT = the yamux spec v0 (ratified; both teams build against it)

> **This is the shared, ratified contract between the hub (backend) and the client (Team2).** Wire = the **yamux
> specification v0** (authoritative: hashicorp/yamux) — we do NOT invent a format; we implement the proven spec in
> common Kotlin (no off-the-shelf JVM+KMP lib, §4.0) with conformance vectors against hashicorp/yamux. Versioned; no
> unilateral change (PO-brokered). Carried as the Noise L2 **plaintext** (the relay sees only the encrypted whole —
> RR4 intact). **All integers big-endian.**

**Frame header — 12 bytes (yamux spec):**

```
[ Version: u8 = 0 ][ Type: u8 ][ Flags: u16 ][ StreamID: u32 ][ Length: u32 ]  then  [ Length bytes payload ]
```

- **`Type`** — `0 = Data` · `1 = WindowUpdate` · `2 = Ping` · `3 = GoAway`.
- **`Flags`** (bitset) — `SYN = 0x1` (open) · `ACK = 0x2` (accept) · `FIN = 0x4` (graceful half-close) · `RST = 0x8` (abort).
- **`Length`** — for `Data`: payload length; for `WindowUpdate`: the window **DELTA** (credit grant, no payload).
- **`GoAway`** codes: `0 = normal` · `1 = protocol-error` · `2 = internal-error` (session teardown).

**Flow control (yamux spec) — this is where G1–G4 come PROVEN:** each stream has a receive window, **initial 256 KB**
(= our envelope; NOT the 64 MB default). The receiver sends `WindowUpdate{delta}` as it **drains** (deadlock-free:
credit on drain, not enqueue) — the spec rule: send an update when `available > max/2` or on `SYN`. A sender may only
send `Data` while it holds window.

**App layer over yamux (our thin additions to the contract):**

- **Stream = ONE HTTP/WS connection as a byte-stream.** The routes are UNCHANGED: a stream carries the raw HTTP/WS bytes
  to a per-stream loopback socket; Ktor routes by the path inside. No custom OPEN metadata needed for routing.
- **`streamClass` = the FIRST payload byte of a stream's first `Data`+`SYN` frame** — `0 = control/lifecycle · 1 =
  agent-ws · 2 = singleton-ws · 3 = rest`. The hub reads it, applies the reserved-control priority policy (§4.8.4),
  then treats the rest of the stream as the byte-stream. (No HTTP-peeking.)
- **`FIN`/`RST`** map 1:1 to the CYP-609 FIN-vs-RST loopback discipline, **per stream** (FIN → `shutdownOutput` the
  per-stream socket + drain; RST → abort). A stream close does NOT tear the tunnel or others (§4.8.3).
- **`maxStreams`** — the per-tunnel stream cap (DoS envelope, §4.6/§4.8.6); default proposed **64** (Dev5 finalizes).
  Config (window 256 KB, maxStreams) single-sourced in `:core` (CYP-613 style).

**G7 hello (integrity-protected IN Noise, before the yamux session):** `[ magic ][ version: u16 ][ mode: u8 ]`,
AEAD-authenticated by Noise; a mode/version mismatch or bad/absent hello → **fail-closed refuse** (§5 mixed-mode; the
untrusted relay cannot tamper it to force a downgrade). This wraps the yamux session; it is NOT part of yamux itself.

**Fail-closed framing (no-byte-bleed, §4.8.2):** a `Length` beyond the Noise-msg-reassembly bound, a `Data` for an
unknown/closed `StreamID`, or a window under/overflow → **GoAway(protocol-error) + tunnel reset** — never a best-effort
skip. The adapter (§4.0) routes each stream's payload to EXACTLY its socket; adversarial framing teeth pin it.

### 4.3 Stream lifecycle (open / close / reset)

- **Open:** initiator picks the next `streamId`, sends `OPEN{target}`; the peer dials the per-stream loopback socket.
- **Close (graceful):** `CLOSE` → the peer FINs its per-stream socket (drains the response), then the stream id retires.
  This carries the CYP-618 asymmetric-close lesson *per stream*: a stream close does not tear the tunnel.
- **Reset (abrupt):** `RESET` → the peer RSTs its per-stream socket (truncation guard, CYP-609 AC2 per stream).
- **Tunnel drop:** if the ONE tunnel drops, ALL streams reset; the client re-dials the tunnel and re-opens streams
  (with the existing durable cursors — e.g. `?since=<seq>` for `/ws/agent` — so no data loss, dedup by seq).

### 4.4 Flow-control / backpressure — **the key de-risk (prevents a NEW HoL case)**

The one real risk of A: **one slow stream must not block all others over the single tunnel** (a new head-of-line
case). Mitigation — **per-STREAM credit-based flow control**, extending the H7 design (which was per-tunnel) to
per-stream:

- Each direction of each stream has a **credit window** (default **≤8 in-flight frames, ≤256 KB/stream**). A sender may
  only send DATA while it holds credit; the receiver returns `CREDIT` as it drains its per-stream socket.
- **Deadlock-free by construction:** credit is returned when the receiver **DRAINS** the per-stream socket (bytes left
  the mux to the loopback/app), **not** when it enqueues — so a sender never waits on itself, and a stalled *consumer*
  (not the tunnel) is what throttles a stream. The tunnel writer never blocks on a single stream's credit (it just
  skips a creditless stream in the interleave).
- **Block-the-pump, loss-free:** when a stream is out of credit, its pump *blocks* (does not drop) — but **only that
  stream's** pump. Other streams keep flowing (they have their own credits). So a slow/stalled consumer throttles its
  own stream, never the tunnel.
- **Fairness + reserved control capacity (CYP-616-reserve, structural):** the mux writer weighted-round-robins ready
  streams so no single high-rate stream monopolizes the tunnel — and the **control/lifecycle stream-class (0) has a
  RESERVED credit floor + interleave priority**, so a bulk data stream (e.g. an avatar transfer) can NEVER starve a
  lifecycle **STOP** frame. This is the structural version of why CYP-610 had to *split* REST from WS at the pool
  level: here it is a first-class reservation, not a pool split. **No-starvation is an invariant (§4.8.4), teeth-pinned.**
- **Bounded memory:** total buffered ≤ `256 KB × maxStreams` — a hard, fail-closed cap (§4.6).

This is the single most important correctness property and gets the heaviest teeth (§4.7). It is why A's HoL profile is
*managed*, not worse than B's.

### 4.5 Ordering

Per-stream ordering is preserved for free: the tunnel is a single ordered (TCP/WS-under-Noise) channel, so frames — and
thus each stream's bytes — arrive in send order. Cross-stream ordering is **not** required (streams are independent);
the demux routes each stream's DATA to its own socket in arrival order.

### 4.6 DoS model (replaces the pool cap)

- **Old:** per-operator **tunnel-count** cap (the pool, `DEFAULT_TUNNEL_POOL_CAP`). Structurally conflicts with demand.
- **New:** per-operator **ONE tunnel**, bounded by (a) a **per-tunnel stream-count cap** (max concurrent streams — a
  bounded, generous number, not tied to a scarce id-set), and (b) the **credit-window memory bound** (§4.4). Excess
  stream-opens beyond the cap are refused fail-closed (not queued). The stream-count cap is a **DoS-envelope value** →
  Auftraggeber sign-off (same posture as the CYP-611 cap bump), and single-sourced (see the CYP-613 `:core` follow-up).
- Relay-side: unchanged — still one pairing per operator, unbounded ids not required (we use one).

### 4.7 Teeth (test discipline — gates RESUMED)

Full suite (this is a rewrite, not a patch): frame codec round-trip (all types, boundaries) · stream lifecycle
(open/close/reset, id retirement, even/odd split) · **backpressure** (a stalled stream blocks ONLY itself; other
streams keep flowing; the credit window bounds buffered memory — mutation-RED on removing the credit gate) · **fairness/
HoL** (a high-rate stream does not starve a low-rate one beyond the window) · **E2E-through-relay** (mux frames ride the
Noise ciphertext; a `RendezvousRelay`/real-route test proves the relay stays opaque and the routes are re-auth'd
per-stream) · fault injection (stream RESET, tunnel drop → all streams reset gracefully + client re-open with cursors)
· concurrency (N concurrent streams over one tunnel, sustained) · **NO-BYTE-BLEED (load-bearing, adversarial):** an
interleaved multi-stream transcript is demuxed and EACH stream's socket receives EXACTLY its own payload bytes and no
other stream's — fuzz the framing (truncated/overlong `len`, unknown `streamId`, reordered frames) and assert
fail-closed reset, never a cross-stream byte landing in the wrong socket (mutation-RED on the demux routing).
Real-route teeth in the CYP-459-T2 style over the loopback; adversarial verify for flow-control, DoS caps, and the
no-byte-bleed boundary.

### 4.8 Invariants (load-bearing — ratified WITH the contract; Team2 + backend agreed)

1. **Fail-closed throughout.** Any malformed frame / unknown `streamId`-for-`DATA` / `len` > `MAX_FRAME` / credit
   under- or over-flow / missing-or-bad hello → RESET the offending stream (or the tunnel for a framing violation);
   never best-effort-continue. A `pool`-mode peer speaking to a `mux`-mode peer → refused at the hello.
2. **★ No byte-bleed between streams (frame-integrity is the ONLY boundary).** The streams share ONE encrypted channel;
   the frame codec + demux are therefore the sole boundary. A demux that misroutes one stream's payload into another's
   socket is a **cross-stream data leak** (one WS/operator's bytes into another's) — the single load-bearing
   correctness surface, adversarially teeth-pinned (§4.7).
3. **Stream-lifecycle ≠ tunnel-lifecycle.** A stream CLOSE/RESET affects only that stream's socket (the CYP-609/618
   FIN-vs-RST discipline, per stream); it never tears the tunnel or another stream. Only a **tunnel** drop resets all
   streams → the client re-dials + re-opens with durable cursors (`?since=<seq>`), dedup by seq (no loss, no dupes).
4. **Reserved control-stream capacity (CYP-616-reserve, structural).** The control/lifecycle stream-class (0) holds a
   RESERVED credit floor + interleave priority, so a bulk data stream can NEVER starve a lifecycle STOP frame. This is
   the structural successor to CYP-610's REST/WS pool split. **No-starvation is an invariant, teeth-pinned (§4.4/§4.7).**
5. **Concurrency (CYP-556).** The stream table + credit accounting are thread-safe under the CYP-556 concurrency
   invariants — concurrent open/close/data/credit across streams have no races, no lost frames, no double-close.
6. **Stream-id hygiene / DoS envelope.** A bounded `maxStreams` per tunnel (REPLACES the pool cap 24) + the credit
   memory bound is the NEW per-operator DoS envelope; excess opens are refused fail-closed. `maxStreams` is a
   DoS-envelope value → **Auftraggeber sign-off** (like CYP-611), single-sourced (CYP-613).
7. **M1 — frame-len ≤ Noise-message.** No mux/yamux frame may exceed the Noise message cap (65535); the adapter (§4.0)
   chunks the byte-stream to fit and reassembles boundary-preserving. Backpressure MUST pass through the chunker
   (yamux window ↔ Noise socket ↔ relay-TCP) — the adapter's load-bearing integration property, teeth-pinned (§4.7).
8. **M2 — per-stream route auth.** Each stream carries its OWN credential; the inner Ktor route re-verifies it
   per-stream (bearer / Kratos session over the demuxed loopback), exactly as today. A stream never inherits another
   stream's auth; the mux grants no ambient trust (the loopback origin is still zero-trust, T1/T2).
9. **G5 — REST idempotency on tunnel-drop.** A tunnel drop resets ALL streams mid-flight; a re-opened REST that was
   in-flight must NOT double-commit. Non-idempotent REST (POST) carries an **idempotency-key**; the hub dedups a
   retried key within a window → at-most-once. (WS is already gap/dedup-safe via `?since=<seq>`.)
10. **G7 — mode/version marker integrity-protected IN Noise.** The `remote.transport` mode + protocol version live in
    the tunnel **hello carried INSIDE the Noise channel** (AEAD-authenticated), NOT in the clear — so the untrusted
    relay cannot tamper the marker to force a downgrade or a mixed-mode DoS. A bad/absent hello → fail-closed refuse (§5).

## 5. Migration / rollout

- **Feature-flag** `remote.transport=mux|pool` (boot/config). Both paths coexist during migration.
- **Coordinated flip:** the mux is a **wire-protocol change** — client and hub MUST flip together. The flag gates both
  ends; a mixed deploy (one mux, one pool) is refused fail-closed at handshake (a version/mode marker in the tunnel
  hello). So: deploy both, flip the flag, dogfood; a bad result flips back to `pool` without a redeploy.
- **Staged:** (1) build MuxBridge + client mux behind the flag (`pool` stays default); (2) dogfood on staging with
  `mux`; (3) on success, make `mux` the default; (4) once proven in production, delete the pool path (`PooledTunnelSource`,
  `TunnelPoolState`, the cap-count model) — that removal is its own PR.
- **Fallback:** the `pool` path is the safety net until step 4; no big-bang cutover.

## 6. Effort, inputs, risks

- **Effort (library-A): ~M (~1 wk server-side)** — the mux CORE is yamux (proven), so the build is the two custom
  surfaces + integration + teeth: (a) the Noise-msg↔byte-stream adapter, (b) the control-stream priority scheduler,
  (c) yamux↔loopback wiring + envelope config, (d) integration teeth (backpressure-passthrough, no-byte-bleed,
  conformance-to-spec). Similar-to-slightly-less than a hand-roll, at **much lower risk** (G1–G4 come proven). Client
  mux in parallel (Dev/Dev5, against the §4.2 contract). **Build-task 0: portable-yamux availability check (JVM+KMP).**
- **Owner responsibilities:** backend owns the hub adapter + scheduler; Dev/Dev5 own the client; the §4.2 contract is
  the shared seam (PO-brokered, no unilateral change). Assist delta-reviews the final approach.
- **Risks & open questions:**
  1. **Adapter backpressure-passthrough (the one real custom risk)** — yamux window ↔ Noise socket ↔ relay-TCP must
     propagate loss-free (§4.0/§4.8.7). Contained + teeth-pinned.
  2. **yamux JVM+KMP portability** — off-the-shelf if a clean lib exists; else spec-impl both sides + conformance
     vectors (still imports the design). Build-task 0.
  3. **Stream-count cap (`maxStreams`) value** — a DoS-envelope decision → Auftraggeber sign-off (like CYP-611).
  4. **Client↔hub version coordination** — the G7 in-Noise mode/version marker + fail-closed mixed-mode refusal (§5).

## 6.5 ★ G6 — the ONE Auftraggeber availability decision (single-tunnel SPOF)

Not a detail — an availability trade-off. With mux, all ~14 streams ride **ONE** tunnel, so a tunnel drop resets **ALL**
of them at once (today: 1 stream/tunnel → a drop hits 1). Two options:

- **(A) Accept the SPOF** — rely on fast **lossless** recovery: on a drop the client re-dials the tunnel + re-opens
  every stream via durable cursors (`?since=<seq>`, dedup) with per-stream backoff. A drop = a brief total-reconnect
  "blink", but **no data loss**. Simplest — one tunnel.
- **(B) Tiny failover set** — 2 tunnels, streams spread / hot-standby → a drop hits only its streams. Better
  availability, more complexity, and re-introduces the pool concept **bounded** (2, not demand-scaled).

**Backend recommendation: (A) now** (recovery is lossless + fast, drops are rare, the blink is brief) **+ (B) as a
follow-up** if operational drop-frequency justifies it. But the availability line is the **Auftraggeber's** call.

## 7. Recommendation summary

**Build library-A** — a proven mux (yamux) over one Noise tunnel, with a Noise-msg↔byte-stream adapter + a
control-priority scheduler as the only custom surfaces, our §4.8 invariants as the wrapper, behind a feature-flag with
a pool fallback, gates resumed. Smallest change that fixes the structural pool limit while preserving the entire
E2E/RR3/RR4 envelope AND importing the dangerous flow-control correctness (G1–G4) proven. Pending: **Assist delta-review
→ Auftraggeber ratifies A(library) + the §4.2 frame/yamux contract + the `maxStreams` DoS cap + G6 (availability)** →
build (backend: hub adapter+scheduler; Dev/Dev5: client).
