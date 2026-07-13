# CYP-427 Phase-2 Activation — E2E Happy-Path + Fail-Closed Test Plan (QA)

> **Author:** QA/Test Engineer — the activation-phase E2E drehbuch for when the remote seams go live. Docs-only /
> planning, ungated. **Analogous to the S8 „echte Agenten am Repo" drehbuch, one layer down: the remote-connect
> durchstich.**
> **Authority:** `CYP-501-activation-contracts.md` (①relay/rendezvous ②CP hubTicket-mint) · client `RemoteHubSession`
> state machine (`RELAY_DIALING→E2E_HANDSHAKE→TRUST_CHECK→AUTHENTICATING→CONNECTED`) · the merged server auth chain
> (Rr3TunnelGate ①→②→③, verified) · CYP-443 tunnel framing (LOCKED) · `18-…§6.3` decisions.
> **★ ACTIVATION BOUNDARY:** the **in-process** durchstich (fake relay + fake CP-mint) is **ungated** and is what this
> plan runs. The **true live activation** (a real relay-server + `CYPPIE_REMOTE_RELAY_URL` flag-on + deploy) is a
> **MUTATION requiring Auftraggeber-GO** ([[live-infra-mutation-authorization]]); **relay-server hosting/access-posture
> decisions escalate to the PO** (risk/access, not a QA or Backend call). This plan is sharp *before* the GO; the live
> run waits for it.

---

## 0. Discipline (both halves)

- **Every fail-closed "never CONNECTED" tooth needs a positive-control anchor** — the happy path reaching CONNECTED in
  the same harness — else "never connects" is vacuous (a broken flow never connects anyway).
- **Per-★-tooth mutation from the start** (axis 23): for each guard the E2E leans on, a mutation that *removes* it must
  drive the negative path to CONNECTED (red) — no downstream masking. The gate-level teeth (CB-x1/CB-and-b/CB-d1,
  REV-1) are already unit-proven load-bearing; here we prove the **end-to-end wiring** actually consults them.
- **Assert stage ORDER, not just the terminal state** — the state machine must *visit* RELAY_DIALING→E2E_HANDSHAKE→
  TRUST_CHECK→AUTHENTICATING→CONNECTED; a jump straight to CONNECTED (skipping AUTHENTICATING) is a bug an
  end-state-only assertion misses.
- **Harness:** drive the REAL `RemoteHubSession` + REAL `Rr3TunnelGate` + REAL `CpJwtMinter` against an **in-process
  fake relay** (`TestRelays`/`DurchstichHarness`) + fake `HubTicketMinter`/`RelayRendezvous` — no live socket, no
  mutation. This is the ungated deliverable.

---

## 0.1 Grounded against develop `e779172a` (real merged artifacts — CYP-507)

- **`:core` wire (CYP-507):** `RendezvousResolveResponse{ binding: RendezvousBinding? = null, failure: RendezvousFailure? = null }` · `RendezvousBinding{ rendezvousId, relayUrl }` · `enum RendezvousFailure { NOT_REGISTERED, RELAY_UNAVAILABLE }`. Business outcomes = **200 + typed body** (mint/ModeRoutes idiom); only structural auth failures are HTTP 401/403. `relayUrl` rides **inside** `binding` (the CP is the single source — its co-located `CYPPIE_REMOTE_RELAY_URL`; the client never needs its own relay config).
- **⚠ NO exactly-one invariant (PO-flagged):** the KDoc says "exactly one of binding/failure", but the TYPE does not enforce it (both nullable) → **new tooth FC-shape** (§3): `binding==null ∧ failure==null` **OR** both-set → the client **fail-closes** (never dials, never a half-CONNECTED). A naive binding-first read would dial on a both-set response = bug.
- **Typed failures map the negatives:** `NOT_REGISTERED` = hub never registered / offline (client waits/retries) · `RELAY_UNAVAILABLE` = no live relay at the CP / activation gate off (INERT).
- **Relay LIVE+DARK:** `wss://api.cyppie-agents.com/relay` (bind `127.0.0.1:8788` behind the TLS-edge; env unset = DARK, no dial). Dial headers (`KtorWsRelayConnector`, CYP-506/509): **`X-Cyppie-Rendezvous`** (opaque id) + **`X-Cyppie-Role: client`**; `RelayServer` missing/invalid headers → **1008** (fail-closed, verified).
- **rendezvousId is a routing LABEL, not a trust anchor** (CYP-507 KDoc): `base64url(SHA-256(hubId‖epoch))`, epoch = CP-**secret** per-registration 128-bit that **never leaves the CP** → the client gets only the opaque id and **cannot re-derive/verify** it (re-deriving from a CP-supplied epoch adds no trust — GIGO). Real integrity is **downstream**: the TOFU-pinned hub `dhPubKey` fails a wrong-rendezvous Noise handshake closed (CYP-478/495). → **CT-1 reframed** (§4).

- **⚠ Two opposite nonce orderings — the ASYMMETRY is the point (relevant to CYP-516 register-owner-gate, nonce-adjacent):** the **RR3 operator** nonce (CYP-477/490) is **consume-AFTER-verify** and this is a **SECURITY REQUIREMENT** — the nonce is presented **pre-auth over the Noise tunnel** (the PoP *is* the auth), so a nonce-only griefer (no device key) could pre-burn a victim's reused-on-retry nonce → **it is mutation-anchored** (`MUT-PREBURN` reds `cbAndB…doesNotBurnNonce`, proven in the CYP-485∪490 run). The **admission** nonce (CYP-512) is **consume-FIRST**, and this is a **safe SIMPLICITY choice, NOT an independently security-load-bearing ordering**: both `/cp/challenge` and `/cp/admit` are `authenticatedApi(…OPERATOR)`-gated, so the pre-auth nonce-only-griefer threat that forces consume-after in RR3 **does not exist here** (an attacker can't present a nonce without operator creds). **The dangerous alignment is one-directional: changing RR3 → consume-first reopens the grief-DoS (guarded by MUT-PREBURN); changing admission → consume-after is harmless churn (no security tooth reds — correctly).** So: don't "align" RR3 down to consume-first; the admission side needs no symmetric security tooth (a flip there won't red, and shouldn't be forced to).

**⛔ Flip-preconditions (Doc 19 §3.0) — the E2E fires only AFTER the flip; pre-stage the axes fireable the MOMENT all are merged/live:** CYP-508 (mint-live, owner-check wired) · **CYP-511 (owner-gate on `resolve` — the resolve-liveness-leak/DoS residual, MUST-before-flip)** · CYP-509 (hub dial-OUT) · CYP-494 full (HttpRendezvousResolver + composition-root ①② + dialer-swap) · **CYP-512 ✅ MERGED @ `60341b1e` (live-hub-admission-flow: `GET /cp/challenge` + `POST /cp/admit` → `HubRegistrar.admit`; admits the dogfood hub so the mint owner-check matches → HP-0 satisfiable)** · CYP-451 (hub-admission PoP substrate) · CP-env (`CYPPIE_CP_SIGNING_SEED`/`KID`/`ISSUER` + hub `CYPPIE_CP_PUBKEY` matched + `CYPPIE_REMOTE_RELAY_URL`). The flip itself needs **Auftraggeber authorization** (outward-acting activation).

---

## 1. The unified activation flow (one linear durchstich)

```
CYP-494 RelayDialer.dial(hubId)
  → CP resolves hubId → OPAQUE rendezvous (base64url(SHA-256(hubId‖epoch))) → relay WS   [RELAY_DIALING]
  → Noise_NK over the relay (hub=responder on its pinned dhKey) ⇒ live h                 [E2E_HANDSHAKE]
  → handshake succeeded AGAINST the TOFU pin ⇒ hub authentic                             [TRUST_CHECK]
CYP-496 CpJwtProvider.provide(hubId, cb=base64url(SHA-256(h‖hubId)))
  → CP mints hubTicket {iss=CP, aud=hubId, sub=AUTHENTICATED operatorId, nbf, exp(short), cb}  [AUTHENTICATING]
  → client sends {cpJwt=hubTicket, pop, nonce} (TunnelAuthRequest)
  → hub RR3 gate: cb==SHA-256(live-h‖hubId) ∧ operator PoP(live-h) → grant               [CONNECTED]
  → CR3 Ktor engine runs HTTP/WS over the tunnel
```

---

## 2. Happy-path E2E (HP-*) — the durchstich reaches CONNECTED

| # | Stage | Assert |
|---|-------|--------|
| HP-0 ★ | **admission precondition** (CYP-512 merged @ `60341b1e`) | the dogfood hub is admitted: `GET /api/cp/challenge` → single-use PoP nonce → `POST /api/cp/admit {registration, nonce}` → `HubRegistrar.admit` (CYP-451 transcript PoP + self-certifying hubId; **`registration.ownerId` must == the authenticated operator**, else `owner_mismatch`) → populates the **shared `cpHubRegistrar`**. Then the CYP-508 mint owner-check (`RegisteredHub.ownerId == operatorId`) passes. **Without admission the registry is empty → mint `NOT_AUTHORIZED_FOR_HUB` → CONNECTED unreachable** (§FC-notadmitted). Admission-side teeth already exist (`Cyp512HubAdmissionRoutesTest`, `Cyp508HubTicketRoutesTest`); the E2E just *consumes* an admitted hub. |
| HP-1 | RELAY_DIALING | `RelayDialer.dial(hubId)` resolves hubId → an **opaque** rendezvous (§CT-1) → dials the (fake) relay; state = RELAY_DIALING |
| HP-2 | E2E_HANDSHAKE | Noise_NK against the hub's **pinned** dhPubKey completes → a live `h` is produced; state = E2E_HANDSHAKE |
| HP-3 | TRUST_CHECK | handshake succeeded against the pin → `HubTrust.Pinned` holds (trustCheck passes, CYP-478); state = TRUST_CHECK |
| HP-4 | AUTHENTICATING | `CpJwtProvider.provide` mints a hubTicket with `cb == SHA-256(live-h‖hubId)` and `sub == authenticated operatorId`; client sends `{hubTicket, pop, nonce}`; state = AUTHENTICATING |
| HP-5 ★ | CONNECTED | RR3 gate grants (cb matches live-h ∧ PoP over live-h) → state = **CONNECTED**, `session.tunnel != null`, `failure == null` |
| HP-6 (i) ★ | **tunnel carries traffic — TRANSPORT proof** | a **raw byte/frame round-trip over the `NoiseTunnel` primitive** succeeds: send frame(s) in, receive frame(s) out, **record the actual bytes/frames** as evidence (not `state==CONNECTED`). **CHARACTERIZE the responder explicitly** — echo / relay-loopback, NOT a real hub route. This is the **real, achievable value of the live run**: transport is proven end-to-end (Request→Noise→Relay→Loopback→Response). |
| HP-6 (ii) | **genuine Hub-HTTP route over the tunnel** | ⚠ **VERIFIED-AT-OBJECT (PO `1526150411872899122`): the CR3-Ktor-engine [workspace-HTTP/WS over the tunnel] is NOT wired — only the `NoiseTunnel` primitive exists.** So a *real Hub-HTTP route over the tunnel* is **likely NOT present yet** (needs CR3). At run time: check at the object whether CR3 is wired; if not, report HP-6(ii) as **OPEN / not-proven**, NOT green. **Never fold (ii) into the "CONNECTED nutzbar" claim** — transport-proven ≠ hub-route-proven. |
| HP-order ★ | ordering | the state stream **visits all five** states in order; CONNECTED is **never** reached before AUTHENTICATING (no stage-skip) |

**Non-vacuity for HP:** HP-5 (CONNECTED) + HP-6(i) (transport round-trip) are the positive anchors for every §3 negative.
HP-order guards against a wiring that sets CONNECTED without actually running the gate. HP-6(ii) is NOT an anchor — it is
gated on CR3 being wired and is reported honestly as OPEN when it is not.

---

## 3. Fail-closed negatives (FC-*) — each **never CONNECTED**, with the positive anchor from §2

| # | Injected fault | Expected | ★ mutation (proves the guard is consulted end-to-end) |
|---|----------------|----------|--------------------------------------------------------|
| FC-shape ★ | `RendezvousResolveResponse` with **both-null** (`binding==null ∧ failure==null`) OR **both-set** | client **fail-closes** — never dials, **never CONNECTED** (the type has NO exactly-one invariant; a binding-first read must not honor a both-set/both-null response) | client reads binding-first ignoring the both-set/null case → dials → CONNECTED = red |
| FC-relayunavail ★ | resolve returns `failure = RELAY_UNAVAILABLE` (no live relay / gate off) | typed INERT failure surfaced, **never CONNECTED** (PO: "kein Relay→nie CONNECTED") | treat RELAY_UNAVAILABLE as retry-and-dial → red |
| FC-notregistered | resolve returns `failure = NOT_REGISTERED` (hub offline / never registered) | client **waits/retries**, **never CONNECTED** until re-registered (distinct from RELAY_UNAVAILABLE) | conflate NOT_REGISTERED with a connectable state → red |
| FC-dial1008 | dial the relay with missing/invalid `X-Cyppie-Rendezvous`/`X-Cyppie-Role` | relay closes **1008** (fail-closed, verified) → never CONNECTED | — (server-side, already verified; assert the client surfaces it as a failure, not a hang) |
| FC-handshake | relay misdirects to an **attacker hub** (wrong dhPubKey) | Noise `es` fails → `HandshakeFailed` → never CONNECTED (pin catches misdirection) | accept any static → red |
| FC-trustchanged ★ | hub dhPubKey **changed** vs the TOFU pin | `TrustChanged` = **TERMINAL** → LOST, **no silent retry** (CYP-478, CI-5) | treat TrustChanged as transient → a reconnect loop = red |
| FC-cb ★ | hubTicket minted for a **different session's h** | RR3 gate rejects (CB-x1) → `AuthRejected` → never CONNECTED (PO: "falsches cb→Hub-Reject") | gate uses a constant `h` → the wrong-cb ticket connects = red |
| FC-sub ★ | hubTicket `sub` ≠ this hub's pinned operator | RR3 gate rejects (CB-and-b) → `AuthRejected` (PO: "falsches sub→Hub-Reject") | remove the sub-pin check → red |
| FC-notadmitted ★ | hub **NOT admitted** — real reasons: registry empty (never admitted) · admission `owner_mismatch` (`registration.ownerId != authenticated operator`) · `nonce_invalid` (never-issued/**replayed** — consume-first) · PoP reject (`pop_invalid`/`hubid_not_self_certifying`/`signing_pub_malformed`) · `no_operator` | the CP mint refuses → **`HubTicketFailure.NOT_AUTHORIZED_FOR_HUB`**, no hubTicket, **never CONNECTED** (positive anchor = HP-0 admitted hub connects). CP-side already covered by `Cyp512HubAdmissionRoutesTest`/`Cyp508HubTicketRoutesTest` | mint skips the owner-check (`hub.ownerId != operatorId` removed) → a non-owned hub mints a ticket = red |
| CT-2b ★ | **admission ownerId is CP-authenticated, never the hub payload** (symmetric to CT-2/mint POINT-2) | `POST /cp/admit` enforces `registration.ownerId == authenticated operator` (`owner_mismatch` else) → a hub **cannot register itself as owned by someone else**; CT-2 (mint) + CT-2b (admit) **double-enforce** "authority = authenticated identity, not the request/payload" | admit takes ownerId from the payload → a spoofed-owner hub is admitted = red |
| FC-revoked ★ | `revokeOperator` during an active CONNECTED session | **immediate teardown** (CYP-484 REV-1) → tunnel dropped → state LOST/RECONNECTING, tunnel gone | remove the registry teardown → session stays CONNECTED = red |
| FC-noretry ★ | AuthRejected / TrustChanged terminal vs a transient DROP | terminal → **LOST, no reconnect**; transient → RECONNECTING (the Outcome discriminator) | classify AuthRejected as transient → a silent retry after a real rejection = red |

**Positive-control pairing (anti-vacuity):** every FC row is asserted **beside** HP-5 in the same harness — the fault
is the *only* difference from a run that connects. Without HP-5 present, "never CONNECTED" is vacuous.

---

## 4. CYP-501 contract teeth (CT-*) — the activation contracts specifically

| # | Contract | Assert |
|---|----------|--------|
| CT-1 ★ | **Rendezvous is an opaque routing LABEL, not a client-verifiable trust anchor (RR4)** | The client dials `binding.rendezvousId` — an **opaque** id (`base64url(SHA-256(hubId‖epoch))`), **NOT the hubId**. **The client CANNOT re-derive/verify it** — the `epoch` is a CP-**secret** per-registration 128-bit value that **never leaves the CP** (re-deriving from a CP-supplied epoch adds no trust — GIGO). So CT-1 does **NOT** assert a client-side derivation check. **Assert:** (a) the dialed id is **≠ hubId** (opacity — the relay never learns hubId); (b) integrity is **downstream** — a **wrong/misrouted** rendezvous is caught by the **TOFU-pinned `dhPubKey`** (Noise handshake fails closed = FC-handshake), never by a client id-check. Relay-side (harness models the CP epoch): id **rotates ACROSS registrations**, NOT per-session (within-registration correlation is an accepted §8.5 residual — do NOT assert per-session unlinkability). *(Mirrors CYP-507 KDoc + CYP-501 Fix-a.)* |
| CT-2 ★ | **sub is CP-authenticated, not client-chosen** | the minted hubTicket `sub == authenticated operatorId`; a client-supplied `sub` is **ignored** (the CP sets it from the authenticated identity) |
| CT-3 | **Ownership check at mint** | `hubId` not owned by the operator (`RegisteredHub.ownerId != operatorId`) → the CP **refuses to mint** (reject before a ticket exists) |
| CT-4 | **Short TTL = min(ticket-exp, hub tunnel-TTL)** (CYP-484) | an **expired** hubTicket → hub rejects (ties REV-3 past-TTL); the effective session lifetime is the min of the two |
| CT-5 | **Offline verify (R1-A)** | the hub verifies the hubTicket with the **pinned CP key**, no CP round-trip at verify — inject CP-unreachable at verify time → still verifies (no hidden hot-path CP call) |
| CT-6 | **Untrusted relay = dumb pipe** | the relay sees only opaque id + ciphertext frames (1 frame = 1 Noise msg, no length prefix, CYP-443); it can neither read payload nor learn hubId; a socket close on either leg fail-closes the pair |

**★ CT-1 + CT-2 are the CYP-501 headline** (RR4 opaque rendezvous + CP-mint authenticated sub — the two contract
properties the PO named). CT-2's mutation: let the client choose `sub` → a forged-sub ticket authenticates = red.

---

## 5. Checklist + open items

**Run (in-process, ungated) at each activation-related merge:**
- [ ] HP-1..HP-5 reach CONNECTED; **HP-order** visits all 5 states, no stage-skip. **HP-6(i)** = raw Noise-tunnel byte/frame round-trip proven, responder characterized (echo/loopback); **HP-6(ii)** = real Hub-HTTP route over the tunnel reported OPEN/not-proven if CR3 unwired (do NOT oversell "CONNECTED nutzbar").
- [ ] FC-shape (both-null/both-set → fail-closed), FC-relayunavail/notregistered (typed `RendezvousFailure`), FC-dial1008, FC-handshake/trustchanged/cb/sub/revoked/noretry each **never CONNECTED** (or teardown for FC-revoked), each with the HP-5 positive anchor; each ★ mutation drives its negative to CONNECTED (red) → the guard is consulted E2E.
- [ ] CT-1 rendezvous opaque (≠ hubId; rotates ACROSS registrations, NOT per-session — within-registration correlation is accepted §8.5); CT-2 sub CP-authenticated (client-sub ignored); CT-3 ownership; CT-4 TTL-min + expired reject; CT-5 offline verify (CP-unreachable at verify still works); CT-6 relay dumb-pipe.
- [ ] Non-vacuity: no FC tooth passes without HP-5 in the same harness; per-★ mutation reds each negative for the right reason, disjoint.

**★ LIVE activation run (Auftraggeber-GO ONLY):** real relay-server + `CYPPIE_REMOTE_RELAY_URL` flag-on + deploy. **Do
NOT run without the Auftraggeber-GO via the PO.** A read-only reachability probe (relay reachable, rejects a non-op
token before any tunnel) is pre-authorized diagnosis; **spawning a live tunnel / mode-swap is the mutation** — design the
probe to reject BEFORE the mutation and present for PO blessing ([[live-infra-mutation-authorization]]).

**Open items to confirm at branch-time (read the impl, don't guess):**
1. Client seams — CYP-494 full (`HttpRendezvousResolver` consuming `RendezvousResolveResponse` + composition-root ①② + dialer-swap) + CYP-496 `CpJwtProvider`; confirm `RemoteHubSession` + `KtorWsRelayConnector` wire the REAL resolver (not `StubRemoteConnectFeed`) before the E2E is non-vacuous. CYP-494 also carries the 2 CYP-510 confused-deputy conditions (instance-sameness of `RegistryPresentedHubKeySource.of(hub)` + `PendingOobConfirmations`; `of(hub)` not `fromControlPlane` — TOCTOU) — verify displayed==presented==pinned at the composition root.
2. `TestRelays`/`DurchstichHarness` — confirm the in-process fake relay pairs hub+client on a rendezvous and pipes
   frames verbatim (the ungated harness for §2/§3).
3. `HubTicketMinter` live composition (authenticate → own-check → `CpJwtMinter`) — CT-2/CT-3 target its real mint path.
4. Where TRUST_CHECK's pin comes from (CYP-478 `HubTrust.Pinned` / first-use OOB fingerprint) — FC-trustchanged targets it.
5. The Outcome discriminator (DROPPED/TRANSIENT/TERMINAL/CLOSED) mapping — FC-noretry depends on AuthRejected/TrustChanged = TERMINAL.
