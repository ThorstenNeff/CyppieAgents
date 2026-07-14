# CYP-536 — C1: Per-Tunnel Credential + PoP Format (WS1 publishes → WS3 consumes)

> Status: **PUBLISHED v1 (Day-1 unblock for Team-2 WS3/WS4/WS5).** Owner: WS1 (Team-1 Backend).
> Base: develop `7358c853` (M2-A foundation). This is the frozen §4-C1 detail-fill for
> `docs/design/M2-A-ntunnel-workstream-split-and-contract.md` — it does **not** change any frozen shape.
> Grounded in merged code (path:line cited); every claim is verifiable against `7358c853`, nothing invented.

---

## 0. The headline for Team-2

**There is NO new challenge format and NO new signature transcript.** The per-tunnel PoP is the **existing**
single-tunnel RR3 PoP (`operatorAuthChallenge` in `:core`, CYP-473), evaluated **once per tunnel** with that
tunnel's own live Noise handshake hash `h_i` and a **fresh single-use nonce_i**. What is *new* under N tunnels is
three per-tunnel **uniqueness rules** (§2) + **UV-caching** so N PoPs cost **one** user-verification (§3). WS3 builds
against the `PerTunnelPoPProvider` seam in §4; the challenge bytes it receives are already assembled by the caller.

---

## 1. The challenge bytes (unchanged — cited, not invented)

The challenge is `com.tneff.cyppieagents.operator.operatorAuthChallenge(h, hubId, nonce)`
(`core/.../operator/OperatorAuthChallenge.kt:22`), byte-identical on client (PoP build) and server (verify):

```
challenge = LP(h) ‖ LP(hubId.utf8) ‖ LP(nonce) ‖ LP("operator-auth")
            where LP(x) = big-endian-uint32(x.size) ‖ x        // 4-byte length prefix, injective
```

- `h` = the **live Noise handshake hash**, a fixed **32 bytes** (BLAKE2s in the pinned suite). **Distinct per tunnel.**
- `hubId` = the hub id string (same for all N tunnels of a session).
- `nonce` = freshness bytes, **single-use at the hub** (`BoundedNonceLedger`, `OperatorAssertionVerifier.kt:35`).
- `"operator-auth"` = the purpose tag (`OPERATOR_AUTH_PURPOSE`, domain separation).

Length-prefixing is mandatory (variable-length fields) — do **not** bare-concat. Use the `:core` function; never
re-implement the layout (the whole point of CYP-473 was to kill the hand-mirrored-drift class).

## 1a. Signature transcript (unchanged — cited)

Two branches, OS-selected, server-authoritative (`DevicePoP.kt`, verified in `OperatorAssertionVerifier.kt:99-160`):

- **Raw (Ed25519) — the cross-platform base, the ONLY runtime-reachable branch on Linux (MVP host):**
  `signature = Ed25519.sign(deviceKey, challenge)` — signs the challenge bytes **directly**.
  Server verify = `RawKeys.ed25519Verify(pubkey, challenge, signature)` (`OperatorAssertionVerifier.kt:162-166`).
- **Fido2 (CTAP2) — macOS Touch-ID / Windows Hello, NOT reachable on Linux:**
  `signature` is over `authenticatorData ‖ SHA-256(challenge)`, the **UV flag (0x04) MUST be set**, `rpIdHash ==
  SHA-256(expectedRpId)`, signCount lenient (`OperatorAssertionVerifier.kt:138-160`). Wire form carries
  `credentialId ‖ authenticatorData ‖ signature`.

Wire type = `OperatorPoPWire.Raw(signature)` | `OperatorPoPWire.Fido2(credentialId, authenticatorData, signature)`
(`core/.../operator/TunnelAuth.kt:20`), carried in `TunnelAuthRequest{cpJwt, pop, nonce, devicePublicKey?}` as the
**first tunnel frame** after the Noise handshake; hub replies **one** `TunnelAuthGrant{granted, reason?, firstEnroll}`.

---

## 2. The THREE per-tunnel uniqueness rules (NEW under N — the correctness core)

Each of the N tunnels is an independent Noise session, so three inputs MUST be per-tunnel-distinct. Violating any of
them makes tunnels 2..N fail verification even though tunnel 1 succeeds:

| # | Input | Rule | If shared across tunnels → failure |
|---|-------|------|-----------------------------------|
| R1 | `h_i` — the tunnel's live handshake hash | Sign against **THAT tunnel's own** `h_i` (each tunnel co-produces its own 32-B `h` in its NK handshake). | Server has `h_2`, recomputes a different challenge → `bad_signature`. |
| R2 | `nonce_i` — fresh single-use per tunnel | Generate a **fresh random nonce per tunnel**. | The hub `BoundedNonceLedger` burns it on tunnel 1's success → tunnels 2..N get `nonce_replayed` (`OperatorAssertionVerifier.kt:107`). |
| R3 | `cpJwt_i` — CP-minted, channel-bound to `h_i` | Mint (client fetches via `CpJwtProvider.cpJwt(h_i, hubId)`) **one CpJwt per tunnel**; its `cb == base64url(SHA-256(h_i ‖ hubId))` binds it to `h_i`. | A CpJwt minted for `h_1` presented on tunnel 2 → `cb` mismatch → reject (`Rr3TunnelGate.kt:98-112`). |

**R3 is the "CP N-mint" half of WS1** (server/CP side): the CP issues N hub-scoped identity tokens per session, one
per tunnel handshake `h_i`. On the client, `CpJwtProvider.cpJwt(h, hubId)` is *already* per-`h` — it is simply called
N times, once per tunnel. **No CpJwt shape change** — only the count.

**Consequence for WS3:** the PoP provider does NOT own `h_i`, `nonce_i`, or `cpJwt_i` — the **caller** (the pool's
per-tunnel request builder, WS2) owns all three and assembles the challenge. WS3 only **signs** the assembled
challenge with the cached-UV device key (§4). This keeps `nonce_i ↔ signature_i` coupled in one component (the
assembler both signs-over and echoes the same `nonce_i` in `TunnelAuthRequest.nonce`) → no cross-field drift.

---

## 3. UV-caching — N PoPs, ONE user-verification (the C1 cost-driver)

Frozen intent (contract §2, §4-C1): the WebAuthn/user-verification ceremony is performed **once per session and
cached**; the N per-tunnel PoPs are **derived from the cached UV** — N tunnels must NOT mean N prompts.

- **Linux / Raw (MVP):** "UV" = the app-scope unlock of the persistent Ed25519 device key
  (`PersistentOperatorDeviceKey.loadOrGenerate()`, `PersistentOperatorDeviceKey.kt:35`). Unlock **once** at
  pool-open, cache the `KeyPair`/signer for the session, then each `popFor` is a plain `Ed25519.sign(cachedKey,
  challenge_i)` — **zero** extra prompts for N tunnels. Trivially satisfies the frozen intent.
- **Fido2 (non-Linux, post-MVP):** capture **one** UV assertion at pool-open, cache it as the `cachedUv`, derive the
  N CTAP assertions within the session. This is the genuinely harder authenticator work and is **WS3's** to design;
  C1 only fixes the *contract*: capture-once, gate-all-N, fail-closed-if-absent.

**Security (for WS6 — no regression):** caching the UV does **not** weaken per-tunnel binding. Each signature is still
over that tunnel's distinct `challenge_i` (its own `h_i` + fresh `nonce_i`). A stolen cached-UV session still cannot
forge a PoP for a tunnel whose `h_i` the attacker does not co-produce (the hub co-produces `h_i` live in the NK
handshake). UV-caching is a UX optimization (N→1 prompts), **not** a trust relaxation.

---

## 4. The frozen WS3 seam — `PerTunnelPoPProvider`

```kotlin
/** WS3 (Team-2). Signs a per-tunnel, caller-assembled channel-bound challenge with the CACHED-UV operator device key.
 *  UV is captured ONCE per session (openSession) and reused; N popFor calls = 0 extra prompts. Fail-closed. */
interface PerTunnelPoPProvider {
    /** Capture the ONE UV ceremony for this pool session. null ⇒ fail-closed: the pool must RST every tunnel. */
    suspend fun openSession(): UvAssertion?

    /** Sign `challenge` (= operatorAuthChallenge(h_i, hubId, nonce_i), assembled by the caller) with the cached-UV
     *  device key. `rendezvousId` identifies the tunnel (logging/telemetry; the crypto is fully in `challenge`).
     *  Returns the Raw Ed25519 signature bytes. Throw/absent-UV ⇒ the caller RSTs THIS tunnel (never a fallback). */
    suspend fun popFor(rendezvousId: String, challenge: ByteArray): ByteArray   // == OperatorPoPWire.Raw(_).signature
}
```

- **Return `: ByteArray` = the Raw Ed25519 signature** — matches the frozen §4-C1 seam exactly. The caller wraps it
  as `OperatorPoPWire.Raw(sig)` and builds `TunnelAuthRequest(cpJwt_i, Raw(sig), nonce_i, devicePublicKey?)`. This is
  correct and complete for the **Linux/Raw MVP** (the only runtime-reachable branch on the host).
- **Fido2 widening (flagged, PO-ratified when it lands):** the Fido2 branch needs `credentialId + authenticatorData +
  signature`, which does not fit `: ByteArray`. When Fido2 becomes reachable (non-Linux), the seam widens to return
  `OperatorPoPWire`. **Not** an MVP change; called out now so WS3 shapes the provider to *internally* produce a full
  `OperatorPoPWire` and expose the Raw signature for the MVP seam without a later rewrite. Any actual signature-return
  change = PO-ratified doc revision (per contract §4).
- **Fail-closed everywhere:** `openSession()==null` (UV denied / no authenticator) or `popFor` throws ⇒ the pool
  **RSTs that tunnel** — never a plaintext/local fallback (preserves `noLiveTunnel_failsClosed`,
  `RemoteTunnelHubTransport.kt:56-58`).

**Who owns what (the C1 division of labor):**

| Owns | Component | Responsibility |
|------|-----------|----------------|
| `h_i` | the tunnel (Noise handshake) | produced live per tunnel; read via `NoiseTunnel.handshakeHash` |
| `nonce_i` | WS2 pool request-builder | fresh single-use random per tunnel; echoed in `TunnelAuthRequest.nonce` |
| `cpJwt_i` | WS2 via `CpJwtProvider.cpJwt(h_i, hubId)` (CP N-mint = WS1 server) | one per tunnel, `cb`-bound to `h_i` |
| `challenge_i` | WS2 pool request-builder | `operatorAuthChallenge(h_i, hubId, nonce_i)` — assembled, handed to `popFor` |
| signature over `challenge_i` | **WS3** `PerTunnelPoPProvider` | cached-UV Ed25519 sign; the only WS3 crypto |
| the wire `TunnelAuthRequest` | WS2 | wraps `Raw(sig)` + echoes `nonce_i` + `cpJwt_i` |

---

## 5. Security invariant — god-token-reject holds PER TUNNEL (WS6 guards)

Two independent per-tunnel gates, both already per-tunnel by construction on `7358c853`:

1. **RR3 tunnel-auth gate** (`Rr3TunnelGate.authorize`, run once per tunnel before its bridge starts): CpJwt AND
   operator-device PoP, both bound to that tunnel's live `h_i`. The static `OPERATOR_TOKEN` is **not** a valid RR3
   credential — only a CP-scoped operator session + device PoP passes. Runs independently for each of the N tunnels.
2. **`TunnelGodTokenGuard`** (f2dd508b, on the tunnel-scoped 127.0.0.1 connector): every tunnel's bridged HTTP/WS
   bytes are pumped into that **one** tunnel-scoped connector, so the static-God-token reject (bearer **and**
   `?token=`) applies to **every** tunnel's requests automatically. No per-tunnel wiring needed — the guard is on the
   connector all N tunnels share.

⟹ **the god-token-reject is per-tunnel for free.** N tunnels do not dilute it.

---

## 6. C2 / C4 confirmation (shapes hold on `7358c853` — no revision needed)

- **C2 (`TunnelSource` N-semantics):** CONFIRMED. `fun interface TunnelSource { suspend fun acquire(): NoiseTunnel? }`
  is unchanged; the accept-loop already **RSTs on `acquire()==null`** (`RemoteTunnelHubTransport.kt:55-59`,
  `conn.reset()`) and **never closes session-owned tunnels** (`close()` closes only the acceptor, `:64-69`). The
  N-swap is purely *inside* `acquire()` (return a distinct pooled tunnel per call, up to `poolCap`) — the transport,
  the fail-closed invariant, and the ownership split are untouched. WS2 owns `poolCap` (single-sourced const, ≈10–15).
- **C4 (rendezvous allocation):** CONFIRMED. `rendezvousId` is an **opaque `String`** used ONLY as a map key
  (`RendezvousRelay.table.compute(peer.rendezvousId)`, `RelayServer.kt:64`, "never reversed to a hubId"); the relay
  pairs **1↔1 per id**. N **distinct** ids ⇒ N independent pairings, **relay = 0 changes**. Client dials each via
  `RelayWsConnector.open(relayUrl, rendezvousId)` (`RelayWsConnector.kt:12`). No shared id across tunnels.
  - **WS1 server note (the coupled-core spike with WS2):** today the hub is a **single persistent serial responder**
    — `NoiseRelayConnector.start()` dials **one** cached rendezvous-id, terminates the NK handshake as responder,
    bridges one tunnel, re-dials on end (`NoiseRelayConnector.kt:~100-131`). WS1's server build **fans this into N
    concurrent responders** (one per session rendezvous-id: dial → NK-terminate → RR3-gate → bridge, all concurrent,
    each with its own session/TTL), plus **CP N-mint** (R3) + **CP N-register/resolve** (the hub registers N ids so
    the client resolves the set). The exact rendezvous-set allocation protocol (client-allocated N ids, how the hub
    learns them) is the day-1 WS1↔WS2 iteration and does **not** block Team-2 (it is behind the frozen C4 shape).

---

## 7. What Team-2 can start NOW (all unblocked by this doc)

- **WS3 (PoP-per-tunnel UV-caching):** implement `PerTunnelPoPProvider` (§4). Linux/Raw: cache the unlocked
  `PersistentOperatorDeviceKey`, sign each caller-assembled `challenge`. Honor R1/R2/R3 as *inputs you receive*, not
  inputs you generate. Fail-closed on absent UV.
- **WS4 (E2E N-tunnel harness):** assert (a) 2→N concurrent tunnels each independently PoP-authenticate, (b) a
  **shared nonce across two tunnels → the 2nd is `nonce_replayed`-rejected** (R2 tooth), (c) god-token-reject fires
  per tunnel (§5), (d) a foreign-`h` PoP is rejected (R1 tooth).
- **WS5 (status UX):** render the C3 `TunnelPoolState` (frozen in the contract) — orthogonal to C1.

Questions on this format → PO (→ relay to po2/Team-2). Shape changes → PO-ratified revision of this doc.
