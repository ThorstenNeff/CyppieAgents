# CYP-443 — Noise Tunnel Framing Spec (client↔hub wire contract)

> Status: **Proposal for Backend reconciliation via PO** · CYP-443 (Epic CYP-427 Phase-2 Remote) · client strand
> Feeds: the CR3 in-process Noise-framing Ktor engine (client) + Backend's Noise-terminating adapter (hub).
> Direction (PO, mediator, 2026-07-11): the Noise tunnel is a **transparent, ordered byte substrate**; the
> existing hub protocol (**standard HTTP/1.1 + WebSocket, the existing REST/WS routes**) runs over it **unchanged**,
> like HTTP over TLS/VPN. **No new mux, no WS-in-WS, no API reinvention.**
>
> **This spec is the wire contract, not code.** It must be identical on both ends (client Ktor engine ⇄ hub
> Noise terminator) — hence Backend reconciliation before either side wires its engine. All layers fail-closed.

---

## 0. Layer model

```
  App:   the EXISTING hub HTTP/1.1 + WS (Ktor client repos/live-sources ⇄ Ktor server routes) — UNCHANGED
  ───────────────────────────────────────────────────────────────────────────────────────────────────────
  L2  Byte substrate:  an ordered, reliable, full-duplex BYTE stream (TCP-like). The App runs on this verbatim.
  L1  Noise record:    each direction's byte stream is chunked into Noise transport messages (AEAD, sequential
                       nonce). Confidentiality + integrity + ordering-authentication. Outside the CP origin (RR6-ii).
  L0  Relay frame:     the relay WebSocket carries each Noise message as one opaque binary frame, client↔hub,
                       ordered + reliable. The relay sees ciphertext + frame sizes/timing only (RR4).
```

The client's CR3 engine and the hub's terminator each present L2 (a byte stream) to their standard HTTP/1.1+WS stack; L1/L0 are symmetric and transparent.

---

## 1. L0 — Relay frame

- Transport = the relay **WebSocket** (opaque rendezvous, outbound-only hub, RR4/RR5). Each `send` is **one binary WS message** = **one L1 Noise message**; WS preserves message boundaries and order.
- The relay is **untrusted** (ciphertext only). It sees frame **sizes + timing** (a metadata channel; RR4 scopes zero-knowledge to *payload*, not metadata; padding is out of MVP scope, explicitly not over-promised).
- **Portability note (if a relay is ever a raw byte stream, not message-oriented):** prefix each Noise message with a **2-byte big-endian length** (the Noise spec's standard `len‖message`), max 65535. Over the WS this prefix is unnecessary (boundaries are preserved) and is **omitted** — but both ends MUST agree which relay flavor is in use. **MVP = WS, no length prefix.**

## 2. L1 — Noise record

- Suite `Noise_NK_25519_ChaChaPoly_BLAKE2s` (RR1), one pinned suite, no negotiation, params in the Prologue.
- After the NK handshake, each L2 byte chunk (≤ **65519 B** = 65535 − 16 MAC) is `encryptWithAd(ad=∅)` into one Noise message; the peer `decryptWithAd`s it back. **Per-message nonce is sequential** (the cipher state owns it) — a dropped/reordered/duplicated frame ⇒ nonce/tag mismatch ⇒ `AEADBadTagException` ⇒ **fail-closed** (never silent reorder or replay).
- **Direction independence:** initiator→responder and responder→initiator use separate cipher states (the `split()` pair). Each direction is serialized (nonce + on-wire order preserved).

## 3. L2 — Byte substrate (the contract the App sees)

- **Ordered, reliable, full-duplex bytes.** The writer's bytes are chunked (≤ 65519 B) into L1 messages; the reader concatenates decrypted messages into the inbound byte stream. **No semantic framing at L2** — HTTP/1.1 (Content-Length / chunked) and WS (its own frames) provide their own message framing on top, unchanged.
- **Backpressure (bounded, no OOM):** L2 is flow-controlled. The write path **suspends** when the relay send buffer is full (the relay WS backpressure propagates through a **bounded** channel between the byte adapter and L1 — never an unbounded queue). The read path suspends when no frame is available. Bound = a small fixed number of in-flight frames (proposed: **≤ 8**, reconcilable).
- **Chunk sizing is not semantic:** a single L2 write may span multiple L1 frames and a single L1 frame may carry bytes from multiple L2 writes. The App must not assume L1 boundaries == its message boundaries (it doesn't today — it speaks HTTP/1.1+WS).

## 4. Close semantics (the security-critical part) — [RECONCILE]

The **relay is untrusted**, so a relay/L0 close must **not** be read as a clean application EOF (a malicious relay could inject a close to truncate a response — a truncation attack):

- **Clean close = authenticated, in-band.** A graceful shutdown is signaled at the **App layer inside Noise** (HTTP/1.1 `Connection: close` + the hub closing the L2 stream, or the WS close frame — both encrypted). L2 surfaces a clean EOF **only** when the in-Noise stream signals it.
- **Relay drop / L0 close / decrypt failure = ERROR, not clean EOF.** The tunnel's `receive()` returning `null` because the **relay** closed is a **drop** → the CR3 engine surfaces a connection **reset** (not a clean close) → `RemoteHubSession.reportDropped()` → reconnect, and in-flight work is marked **uncertain** (H4). It is never presented to the App as "the response ended normally."
- **Consequence:** the App/engine must distinguish (a) in-Noise clean close from (b) transport drop. This is the one place the transparent-substrate abstraction needs an explicit rule — hence [RECONCILE] with Backend so both terminators agree.

## 5. What is NOT introduced

- **No new mux** — one L2 byte stream per hub session; HTTP/1.1 keep-alive + WS upgrade behave as over any socket. (If concurrent HTTP + multiple WS over one tunnel need true multiplexing, that is a *future* decision, not this MVP; today the client already opens multiple sockets and the tunnel would carry them as the relay/hub allow — **flag for Backend:** confirm whether the hub terminator accepts multiple L2 connections over the tunnel or one — this determines whether the client opens N tunnels or muxes. Proposed MVP: **the tunnel carries one keep-alive HTTP/1.1 connection + WS upgrades exactly as the local transport does today**, mirroring `sharedWsHttpClient`.)
- **No WS-in-WS re-encoding**, no bespoke RPC, no API changes. The hub routes are untouched.

## 6. Metadata / threat notes (RR4)

- The relay sees **frame count, sizes, timing** — a metadata channel (payload is ZK, metadata is not). Not padded in MVP; the ZK claim is scoped to payload (do not over-promise).
- The tunnel emits **zero plaintext before the handshake completes** and **zero plaintext outside Noise** (RR6-ii — plaintext lives only in-process). Remote is **always Noise** (RR8): there is no plaintext fallback path in this framing.

## 7. Open points for Backend reconciliation (via PO)

1. **[RECONCILE] Close semantics (§4):** confirm the hub terminator treats a relay drop as a reset (not clean EOF) and signals clean close in-band. This is the truncation-attack guardrail.
2. **[RECONCILE] Connection cardinality (§5):** one L2 connection per tunnel (HTTP/1.1 keep-alive + WS upgrades, mirroring today) vs multiple — determines client-side N-tunnels-vs-mux. Proposed: one, mirroring the local transport.
3. **Relay flavor (§1):** MVP = message-preserving WS (no length prefix); confirm. If a byte-stream relay is ever used, both ends adopt the 2-byte length prefix.
4. **Backpressure bound (§3):** the in-flight frame cap (proposed ≤ 8) should match the hub terminator's.

Once reconciled, the client CR3 engine (`RemoteHubTransport` = `HubTransport` over the L2 substrate) and the hub Noise terminator are wired against **this** contract; the existing repos/live-sources run unchanged.
