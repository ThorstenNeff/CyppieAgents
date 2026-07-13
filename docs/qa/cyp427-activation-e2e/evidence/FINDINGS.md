# CYP-427 — Live Machine Transport E2E (A): Findings

**Run:** controlled staging, Auftraggeber-GO. develop tip `d9a3d6be` (CYP-524 merged). 2026-07-13.
**Stand:** CP `https://api.cyppie-agents.com` · hubId `hub_c1d6f5ffd892a03d` · pinned dhPubKey (32B X25519) · relay `wss://api.cyppie-agents.com/relay`.
**Harness:** `app/shared/src/jvmTest/.../connect/Cyp427LiveTransportE2eTest.kt` (gated `CYP427_LIVE=1`). Operator bearer read host-side only, **never logged/echoed/committed** — all evidence is secret-scrubbed by construction.

## Outcome: transport proof NOT achieved — a real HIGH integration finding (caught pre-dogfood).

### Verified-good (the client path up to the handshake)
- **Step-0 reject-before-mutation** (read-only): relay bare-GET → 400 (header-gated) · no-header WS dial → **101 then WS CLOSE 1008 `bad_rendezvous`** (no tunnel) · non-op / no-auth → **401** with the exact prod `{"error":{"code":"unauthorized",...}}` envelope (also live-confirms the CYP-524 Blindness-B fidelity + CYP-511 resolve-owner-gate). See `step0-reachability.txt`.
- **HP-1 resolve → `Bound`** — the hub IS registered in the CP; the rendezvous resolves with the operator bearer.
- **CT-1 rendezvous opacity** — `rendezvousId = nrW8_oGbUYbZtt8claiqPXCtBwZzrDRM9fwO79GGqzQ` ≠ hubId (opaque routing label, relay never learns hubId); `relayUrl` is the CP-supplied binding.
- **Relay WS dial opens** (with the `X-Cyppie-Rendezvous`/`X-Cyppie-Role` headers) and **Noise msg1 (e,es), 48B, is sent**.
- **Fail-closed at HP-2 is correct** — the client never proceeds without a completed handshake (good security behavior).

### The gap (HP-2): the Noise_NK handshake never completes — no valid msg2 responder
Across **5+ live dials**, no valid Noise `msg2` returns. The relay **closes the connection ~1s after msg1**, observed variously (all consistent with "no responder"):
- `relay.receive()` → `null` (clean close),
- WS `java.util.concurrent.CancellationException: "Channel was cancelled"` at **~1.2s** (from ktor's WS outgoing processor),
- once a stray frame that failed `HandshakeState.readMessage` (`NK handshake failed`).

Result each time: **no completed handshake → no CONNECTED → no grant frame → transport NOT established.** See `transport-roundtrip.txt` (HP-2 fails closed) and `state-trace.txt` (`RELAY_DIALING → TIMEOUT`).

### Root cause — primary hypothesis
**The hub is registered in the CP (rendezvous resolvable) but is NOT live-paired to the relay as a responder.** The client reaches the relay and sends msg1, but no hub answers with msg2 → the relay gives up (~1s close). This echoes the CYP-524 registration/liveness theme (registered ≠ live-present at the relay). Likely a hub-side relay dial-out / liveness gap (code path CYP-509/521).

### Secondary hypothesis — less likely (to rule out)
The published `dhPubKey` ≠ the hub's live Noise static. **Weighed lower:** a present-but-wrong-key hub would *deterministically* return a `msg2` that fails the AEAD tag; across runs we mostly see **NO frame at all**, not an AEAD-fail. (The pin still fails closed correctly in either case.)

### Recommendation (priority)
1. **Backend/deploy:** verify the hub `hub_c1d6f5ffd892a03d` holds a **live relay responder connection** (hub-side relay dial-out up), and confirm the published `dhPubKey == hub live static`.
2. Then **re-run** the transport proof — the harness is staged (one command). On a live-paired hub, HP-2..6 should complete and yield the AEAD `TunnelAuthGrant` transport round-trip (with the honest `granted=false, reason=device-not-enrolled` per CYP-525, until the operator device key is enrolled).

### Scope notes carried
- **Full CONNECTED** (option B) is gated on **CYP-525** (client operator-device-key persist/enroll + hub device-store provisioning) — not part of this transport-proof run.
- **HP-6(ii)** a genuine Hub-HTTP/CR3 route over the tunnel is **out of scope** (CR3 not wired; only the `NoiseTunnel` primitive exists).

---

## RESOLUTION (re-run after CYP-526 + deploy redeploy) — TRANSPORT PROOF ACHIEVED

**CYP-526** (hub relay-responder liveness: persistent reconnect loop + register retry/guard + anti-rotation cached id)
merged → develop `75bb7b2d`; deploy redeployed and verified **role=hub responder established=1** (was 0), stable.
New boot → new rendezvous epoch (`aXHvSU5t…`, the old `nrW8_` stale); the harness **re-resolves fresh** (HP-1
`GET /api/cp/rendezvous/{hubId}` → binding.rendezvousId — no hardcoded/cached id) → picked up the new id automatically.

**Re-run result (client harness `862ab059`, unchanged — CYP-526 was server-side): ✅ TRANSPORT PROVEN, 4/4 reproducible.**
- HP-1 resolve → Bound (fresh id) · CT-1 opacity (id ≠ hubId).
- HP-2 **Noise_NK handshake OK** against the pinned dhPubKey → per-session live-h (unique each run, CI-2).
- HP-3 trust-pin holds · HP-4 cb = base64url(SHA-256(h‖hubId)).
- **HP-6(i) TRANSPORT ROUND-TRIP PROVEN** — framesSent=1 (795B) / framesRecv=1 (40B): a **TunnelAuthRequest** sent +
  an **AEAD-decrypted `TunnelAuthGrant` frame returned** over Noise_NK. Responder = the hub's **RR3 tunnel-gate**
  (NOT an echo, NOT a CR3 workspace route). This is the bidirectional encrypted transport proven end-to-end.
- Authorization: **`granted=false, reason=auth_failed`** — denied at the operator-device-PoP layer (a fresh,
  non-enrolled device key — **option A**, honest; full CONNECTED is gated on **CYP-525**). No fake CONNECTED.
- **HP-6(ii)** genuine Hub-HTTP/CR3 route over the tunnel: **OUT OF SCOPE** (CR3 not wired) — not part of the claim.

**The original HIGH finding is resolved by CYP-526** (the signature flipped from "no valid msg2 / relay closes ~1s"
to a completed handshake). The dhPubKey pin **matches** the hub's live static (the handshake completes) — the earlier
secondary key-mismatch hypothesis is disproven. See `rerun/transport-roundtrip-PROVEN.txt` + `rerun/reproducibility.txt`.
