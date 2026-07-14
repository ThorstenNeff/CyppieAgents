# M1 + M2 Dogfood Readiness — Server / Remote-Hub Runbook

> Owner: Backend. **Prep only — no deploy motion.** This is the *server/remote-hub-side* readiness checklist for the
> combined M1 (device enroll/attest) + M2 (N-tunnel remote hub over a real relay) dogfood on **staging**, the milestone
> right after deploy. Client/UX walkthrough is the companion `docs/design/device-enroll-dogfood-walkthrough.md`.
> Every step is **executable** (a check + expected result + the fail-closed note). Substitute `<...>` per the staging env.

## 0. Topology that must be true on staging

Four server-side processes + Kratos + the desktop client. All hub↔CP↔relay hops are **credentialed**; the relay is a
**dumb pipe of opaque Noise frames** (no hub secret/crypto on it — RR4).

```
 Desktop client ──WS(role=client)──┐                        ┌── outbound WS(role=hub) ── Remote Hub (dials the relay, RR5)
   127.0.0.1:47472 (OIDC cb)       ├── RELAY (untrusted) ───┤        │
        │  Kratos operator OIDC    │  CYPPIE_RELAY_*:8788    │        └── loopback tunnel connector (god-token-guarded)
        └────────── Control Plane (CP) ─────────────────────┘  admit · challenge · hubticket(CpJwt) · rendezvous · hubs
```

**Datapath correctness is already PROVEN in CI (CYP-549, live-relay loopback e2e: real `relayModule`, T1–T5, N×-green).**
So on staging the relay leg is **ops-validation only** (§4: WAN / TLS / NAT), *not* a correctness proof.

---

## 1. Service-up preconditions (run first; each is fail-closed)

| # | Check | Command | Expect | Fail-closed |
|---|---|---|---|---|
| 1.1 | **CP reachable** | `curl -fsS <CP_URL>/api/health` (or the CP's health path) | `ok`/200 | hub boot won't admit → INERT |
| 1.2 | **Relay reachable** | `curl -fsS http://<RELAY_HOST>:<RELAY_PORT>/health` | `ok` | no pairing → no tunnel |
| 1.3 | **Kratos public up** | `curl -fsS <KRATOS_PUBLIC>/health/ready` | 200 | operator can't OIDC-login |
| 1.4 | **Hub is admitted+owned at the CP** | `curl -fsS -H "Authorization: Bearer <CP_OPERATOR_TOKEN>" <CP_URL>/api/cp/hubs` | the hub id present, owned by `<OPERATOR_ID>` | rendezvous register is owner-gated → 403 → no dial |
| 1.5 | **Relay is NOT the security boundary** | confirm the relay process links no hub store/secret (it's `:server:relayRun` = `relayModule()` only) | dumb pipe | — |

> The relay binds `CYPPIE_RELAY_HOST:CYPPIE_RELAY_PORT` (default `0.0.0.0:8788`); its public exposure / TLS reverse-proxy
> is **deploy-owned**, not decided by the process.

---

## 2. Remote-hub boot env gate (the hub dials the relay ONLY if ALL are present — else fail-closed INERT)

`buildRemoteTransport` refuses to dial with any gap (parity with the `CYPPIE_MASTER_KEY`-gated custody). Verify on the
hub host **before** boot:

| Env var | Purpose | Verify |
|---|---|---|
| `CYPPIE_REMOTE_RELAY_URL` | the relay WS URL the hub dials | non-blank, `ws(s)://…` reachable (§1.2) |
| `CYPPIE_CP_URL` | CP base (register + admit) | matches §1.1 |
| `CYPPIE_CP_OPERATOR_TOKEN` | operator bearer for rendezvous-register (owner-gated) | present; the hub is owned (§1.4) |
| `CYPPIE_OPERATOR_ID` | the single pinned operator (RR3 `sub` pin) | matches the CP-JWT `sub` + the enrolled operator |
| `CYPPIE_CP_ISSUER` / `CYPPIE_CP_KID` / `CYPPIE_CP_PUBKEY` | CP-JWT verify (iss / kid→pubkey) | pubkey base64 decodes to the CP's signing pubkey |
| `CYPPIE_OPERATOR_RP_ID` | WebAuthn RP id (Fido2 `rpIdHash` check; unused on Raw) | the hub origin |
| `CYPPIE_MASTER_KEY` **or** passphrase custody | SecretStore master-key custody | present (else the store fails closed; CYP-434) |
| `CYPPIE_OP_SESSION_TTL_MIN` *(optional)* | passive op-session TTL | default 15 min if unset |

**Boot verification (fail-closed evidence):** after boot, the log should show the CYP-526 relay-connector **dialing**
(`CYP-526 dialing relay as role=hub` … `relay responder established`). If any env is missing, expect the **INERT**
line (`CYP-459 relay connector INERT (enabled=…, relayUrl set=…)`) and **no dial** — a half-configured remote is never
dialed. **Confirm the INERT-vs-live line explicitly** (don't infer from silence).

---

## 3. Enroll / attest flow readiness (server-side of the M1 first-connect)

The **first** connect of a new device enrolls it (TOFU under a CP-authenticated operator, CYP-525). Server-side truths
to verify on staging (client copy/UX = the companion doc):

1. **CP-JWT mint is live + `sub`-pinned.** The hub's RR3 gate (`Rr3TunnelGate`) verifies the CpJwt: `iss==CYPPIE_CP_ISSUER`,
   `aud==hubId`, `exp/nbf`, `sub==CYPPIE_OPERATOR_ID`, and the channel-binding `cb == base64url(SHA-256(h ‖ hubId))`.
   *Verify:* a CpJwt minted for one session's `h` cannot authenticate another (anti-cross-session-replay). The device PoP
   (`operatorAuthChallenge(h,hubId,nonce)`, Raw Ed25519 on Linux/dogfood) is verified **AND**-conjoined with the CpJwt.
2. **TOFU first-enroll is reachable only past a valid CpJwt** (no land-grab): on an empty operator-device store the gate
   anchors the presented raw-32B device key **after** proving possession, under the CpJwt-authenticated operator (CT-2b).
   *Verify:* the staging operator-device store starts empty (first device) → first connect enrolls; a second connect
   with the same operator does **not** re-enroll (steady-state PoP against the anchor).
3. **Recovery-codes are the only return** (no central login reset). The hub mints the codes once, reveals E2E over the
   tunnel, and **Finalizes only on the client `SavedAck`** (persist code-hashes + set the anchor atomically). No ack →
   discard → re-mint next connect (no-lockout). *Verify:* the finalized-enrollment store persists 0600, owner-only.
4. **God-token-per-tunnel + revocation-fanout hold** (M2): the static `OPERATOR_TOKEN` is refused on every tunnel's auth
   channel (only a CP-scoped operator session passes); a revocation tears down **every** one of the operator's N tunnels
   (`TunnelSessionRegistry.revokeOperator`). *Verify:* over a live tunnel, a request bearing the static operator token
   → 401 on the tunnel connector; the same token → 200 on the public connector (guard is port-scoped).

---

## 4. Kratos loopback return-URL patch — verification steps

The desktop operator authenticates via Kratos OIDC; after login Kratos must be allowed to **return to the desktop
loopback** (`app/desktopApp/…/main.kt` binds `127.0.0.1:47472`). This is the patch to verify on staging.

| # | Check | How | Expect |
|---|---|---|---|
| 4.1 | **`allowed_return_urls` includes the loopback callback** | inspect the effective Kratos config (`selfservice.allowed_return_urls`) — staging substitutes the `REPLACE_ME` placeholders | the list contains `http://127.0.0.1:47472/callback` (the desktop loopback), **exact scheme+host+port+path** |
| 4.2 | **`default_browser_return_url` set** | same block | a real staging URL, not `REPLACE_ME` |
| 4.3 | **OIDC providers injected via env, not file** | `SELFSERVICE_METHODS_OIDC_CONFIG_PROVIDERS` (JSON array incl. `client_secret`) is set in the Kratos env — the file block documents SHAPE only and **omits** the secret (fail-closed if env missing) | provider present; a `${VAR}` literal in the file would break GitHub `incorrect_client_credentials` — confirm it's the env path |
| 4.4 | **Return-URL is exact-match, not prefix** | attempt an OIDC login end-to-end; on success Kratos 302s back to `127.0.0.1:47472/callback` | the desktop's loopback listener receives the code; a *missing* entry → Kratos refuses the return (login can't complete) |
| 4.5 | **email_verified / linking posture** | per CYP-532 (S1b still probe-gated): GitHub is S1a-safe; generic providers not yet added on staging | no silent-merge exposure (CYP-532 §1(c) closes before broader OIDC) |
| 4.6 | **argon2 password cost floor present** | Kratos `hashers.argon2` iterations+memory set (no account-lockout by design → the cost floor is the online-brute-force defense, RC2) | set (per `Rc2ConfigAssertionTest`) |

---

## 5. Live-relay OPS-validation checklist (WAN / TLS / NAT — NOT correctness)

CYP-549 proved the datapath (real relay pairing, real Noise-NK over real WS framing, RR3 over real frames, anti-replay,
`spyRelay.frames>0`). On staging the **remaining** relay concerns are purely operational:

| # | Concern | Check | Notes |
|---|---|---|---|
| 5.1 | **`wss://` TLS to the relay** | the hub + client dial `wss://<relay-fqdn>` through the deploy reverse-proxy; TLS terminates at the proxy | Noise is the **E2E security boundary**; TLS-to-relay is defense-in-depth, not the boundary (the relay only ever sees opaque frames) |
| 5.2 | **WAN latency / MTU** | the NK handshake + a workspace request complete within the client's connect timeout over the real network | the in-CI proof is latency-free; watch the connect-view state machine reaches CONNECTED without a spurious RECONNECTING loop |
| 5.3 | **NAT / reconnect** | drop the relay leg mid-session → the hub's persistent responder re-dials (CYP-526) and the client re-binds; verify no silent stall | the CYP-541 in-flight-uncertain honesty signal should show during the drop |
| 5.4 | **Rendezvous-id opacity on the wire** | the relay/proxy logs show only the opaque `X-Cyppie-Rendezvous` id + ciphertext + sizes/timing — never a `hubId` or plaintext | RR4; the id is `base64url(SHA-256(hubId ‖ epoch))`, epoch a CP-secret |
| 5.5 | **N-tunnel presence** | the hub is present at the relay on the epoch-derived **set** of rendezvous-ids (cap = `DEFAULT_TUNNEL_POOL_CAP`); the client dials the set minus the base id | per-operator tunnel CAP is the structural DoS floor |

---

## 6. Combined-run smoke (the dogfood happy-path, server-observable)

Run the client walkthrough (companion doc) and confirm the server-side transitions:

1. Client connects → **relay pairs** hub↔client on a rendezvous-id (relay log: two peers joined, forwarding).
2. **NK handshake** completes → the hub's RR3 gate reads one `TunnelAuthRequest` → verifies CpJwt ∧ PoP against the live `h`.
3. First device → **TOFU enroll**: grant `firstEnroll=true`, `EnrollResponse` (codes) E2E over the tunnel, client `SavedAck`,
   hub **Finalizes** (persist), final grant `firstEnroll=false` = CONNECTED.
4. Workspace request over the tunnel → **200 + real roster** (M2 datapath); the static operator token over the tunnel → 401.
5. Revocation (operator action) → **all** the operator's tunnels torn down.

**Gate to "dogfood ready":** §1–§4 all green + §5 ops-checks pass on the real network. §5 is the only genuinely-new
staging risk (datapath correctness is retired by CYP-549).

---

## Appendix — quick fail-closed reference

- Any missing hub env (§2) → **INERT, no dial** (confirm the log line, never infer from silence).
- Hub not admitted+owned (§1.4) → rendezvous-register 403 → no dial.
- Missing `allowed_return_urls` loopback entry (§4.1) → OIDC login can't return → operator can't authenticate.
- Missing `SELFSERVICE_METHODS_OIDC_CONFIG_PROVIDERS` (§4.3) → Kratos validation error (fail-closed) — better than a broken literal.
- Missing master-key custody (§2) → SecretStore fails closed (no plaintext fallback, CYP-434).
