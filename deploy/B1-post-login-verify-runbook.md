# B1 Post-Login Server-Verify Runbook

> Owner: Backend. **The second half of the dogfood** — everything AFTER the operator is logged in (CYP-576 GitHub-OIDC).
> Covers the B1 device-lifecycle: **enroll → passphrase-custody → recovery-codes → connect (Noise/RR3) → agent-turn →
> restart-persistence.** Staging `api.cyppie-agents.com` / Hub `hub_c1d6f5ffd892a03d` / operator `ab7c54e3`.
> Companion to `CYP-576-verify-runbook.md` (login leg). Every step = server-observable marker + **expected** + fail-closed.
>
> **Division:** Team-2 (Backend2/Tester2) run the **live teeth** (drive the client, hold creds/tokens); they paste the
> **non-secret server markers** (log lines / masked verdicts) and the **backend agent arbitrates** green/red vs this
> runbook + code (Path B). **A session_token / device seed / operator token is a SECRET — never its VALUE in channel/logs.**
>
> **The core B1 truth:** fail-closed here = **SILENT INERT / uniform reject**, NOT a crash → every marker is verified by an
> **explicit** log line or observed transition, **never inferred from "no error in the log."**

---

## 0. Preconditions

- CYP-576 login leg green (its runbook §1–§6): operator authenticated, `ab7c54e3` = OPERATOR, `/api/cp/hubs` lists the hub.
- M1-M2 readiness §1–§5 green: CP/Relay/Kratos up, hub admitted+owned, remote connector LIVE-not-INERT (`CYP-526 … relay responder established`).
- Hub-static fingerprint OOB-confirmed (see §7 / already 2/3-triangulated; 3/3 completes at connect).

---

## 1. The B1 flow map (who verifies what)

```
 login(CYP-576) ─▶ ENROLL(first connect, TOFU/CpJwt) ─▶ PASSPHRASE(master-key custody) ─▶ RECOVERY-CODES(mint+SavedAck)
      ─▶ CONNECT(Noise-NK + RR3: CpJwt∧PoP vs live h) ─▶ AGENT-TURN(workspace over tunnel) ─▶ RESTART-PERSISTENCE(#4)
```
Team-2 drive each step live; I arbitrate the server markers below.

---

## 2. Step-by-step server-observable verify

### 2E — ENROLL (first connect, TOFU under CpJwt) — `Rr3TunnelGate`
| # | Marker | Expect | Fail-closed / RED |
|---|---|---|---|
| 2E.1 | Exactly **one `TunnelAuthRequest`** read off the tunnel | 1 per connect | replay of a 2nd on the same tunnel → reject |
| 2E.2 | **CpJwt ∧ PoP** both verified vs the tunnel's **LIVE `h`** | grant only after BOTH (never PoP-alone / CpJwt-alone) | either alone grants → **STOP** (auth split) |
| 2E.3 | **TOFU only PAST a valid CpJwt** (no land-grab): empty anchor → `provisionalFinalizeFlow` under the CpJwt-authenticated operator | first connect enrolls; anchors the presented raw-32B key AFTER proving possession | enroll reachable without a valid CpJwt → **STOP** (silent-fail #4 in §3) |
| 2E.4 | Non-32-byte `h` rejected early (`Rr3TunnelGate` CB-d1 short-circuit) | reject before any store touch | — |

### 2P — PASSPHRASE / master-key custody (SecretStore) — CYP-434/548
| # | Marker | Expect | RED |
|---|---|---|---|
| 2P.1 | Master-key custody present (`CYPPIE_MASTER_KEY` **or** passphrase custody) | SecretStore opens; canary decrypts | absent → store fails closed (no plaintext fallback); enrollment can't persist |
| 2P.2 | Custody is a **stable, persisted** value (not per-container regen) | same key across restarts | unstable → 2X lockout (§2X / #2) |

### 2R — RECOVERY-CODES (mint once, reveal E2E, Finalize on SavedAck) — `BackupCodeStore`/`FinalizedEnrollmentStore`
| # | Marker | Expect | RED |
|---|---|---|---|
| 2R.1 | Codes minted **once**, revealed **E2E over the tunnel** | `MintedCodes` returned to client over the Noise tunnel | plaintext codes in a log → **STOP** |
| 2R.2 | Hub stores **ONLY per-code salted SHA-256** (`BackupCodeStore` salted hash), never plaintext | at-rest = hashes only | plaintext at rest → **STOP** |
| 2R.3 | **Finalize ONLY on the client `SavedAck`, ATOMICALLY** (`FinalizedEnrollment(device, codes)` — anchor + code-hashes commit together) | persist happens iff SavedAck received; bounded `savedAckTimeoutMs=30s` | codes revealed but persisted before ack, or non-atomic → §3 #5 |
| 2R.4 | No ack within 30s → **discard → no CONNECTED** (no-lockout, re-mint next connect) | clean discard, `firstEnrollLock` released | hung provisional never releases → liveness bug |

### 2C — CONNECT (Noise-NK + RR3 steady-state) — `Rr3TunnelGate`
| # | Marker | Expect | RED |
|---|---|---|---|
| 2C.1 | CpJwt claims verified: `iss==CYPPIE_CP_ISSUER`, `aud==hubId`, `exp/nbf`, `sub==CYPPIE_OPERATOR_ID`, `cb==base64url(SHA-256(h‖hubId))` | all pass vs live `h` | any lax → **STOP** |
| 2C.2 | Steady-state (already finalized): PoP vs the **enrolled anchor** → `grant(firstEnroll=false)` (`Rr3TunnelGate.kt:160`) | **RE-ATTEST, not re-enroll** | re-enroll on a 2nd connect → anchor not read (#2/#9) |
| 2C.3 | Anti-cross-session replay: a CpJwt minted for one session's `h` **cannot** authenticate another | reject | accepts → **STOP** (cb not bound) |

### 2A — AGENT-TURN (workspace over the tunnel)
| # | Marker | Expect | RED |
|---|---|---|---|
| 2A.1 | A workspace/roster request over the established tunnel | **200 + real roster** (M2 datapath); mediation delivers the turn | 200 on public but tunnel dead → datapath gap |

### 2X — RESTART-PERSISTENCE (the 1× PO-triggered restart — silent-fail #4)
> **PO triggers the restart centrally via deploy; I OBSERVE, do not trigger.** PO signals the exact moment.

| # | Marker | Expect (GREEN) | RED |
|---|---|---|---|
| 2X.1 | Post-restart boot | `CYP-525 operator anchor unreadable` **ABSENT** | present → **MASTER_KEY drift**, sealed device blob won't decrypt → operator **LOCKOUT** |
| 2X.2 | Post-restart connect | steady-state PoP vs the **SAME** anchor → CONNECTED **`firstEnroll=false`** (re-attest) | re-enroll / rejectTampered / rejectFinalizeCommitFailed → §3 |

---

## 3. The B1 silent-fail catalog (9) — each looks healthy at boot; verify EXPLICITLY

| # | Silent-fail | Server marker | GREEN | RED |
|---|---|---|---|---|
| 1 | **Mint↔RR3 keypair mismatch** (`CP_SIGNING_SEED` not the keypair of `CYPPIE_CP_PUBKEY`) → uniform `auth_failed`, looks like a network error | isolated crypto proof: mint-sig verifies under live `CYPPIE_CP_PUBKEY` (= the `CpJwtVerifier` check) **OR** a real tunnel authorizes | verify VALID / tunnel connects | mint OK but no tunnel ever connects |
| 2 | **MASTER_KEY unstable across restart** → sealed device blob won't decrypt → RR3 `rejectTampered` → operator lockout (boot looked healthy) | 2X.1/2X.2 | anchor-unreadable ABSENT + re-attest | `operator anchor unreadable` present |
| 3 | **MASTER_KEY absent** → `hubSecretStore=null` → hub INERT (loud missing-list, but if missed = no enroll) | boot `missing +=` pre-flight list | custody present, no missing | INERT / missing MASTER_KEY |
| 4 | **TOFU land-grab** — first-enroll reachable WITHOUT a valid CpJwt → attacker anchors their device | 2E.2/2E.3 | enroll only past CpJwt∧PoP | enroll on bare/invalid CpJwt |
| 5 | **Finalize non-atomic / before SavedAck** → codes revealed but not persisted → restart lockout or double-mint | 2R.3 | atomic commit on ack | persist-before-ack / partial |
| 6 | **Finalize commit fails silently** (storage fault: disk-full/perms) → codes revealed, not persisted (`CYP-525/558 finalize commit FAILED`) | log scan | line ABSENT + CONNECTED persists | `finalize commit FAILED` present |
| 7 | **Static `OPERATOR_TOKEN` accepted over the tunnel** → bypasses the CP-scoped operator session (god-token-per-tunnel breach) | §4 | tunnel 401 / public 200 | tunnel 200 on static token |
| 8 | **Revocation doesn't fan out** → a revoked operator keeps N-1 tunnels alive | `TunnelSessionRegistry.revokeOperator` returns the count torn down | ALL N torn down | any tunnel survives |
| 9 | **Corrupt/tampered finalized anchor** read → must fail-CLOSED, not silently re-enroll/crash | `CYP-525 operator anchor unreadable … refusing ALL connects` is the INTENTIONAL guard | that guard fires on a genuinely-corrupt anchor; absent on a healthy one | silent re-enroll / crash instead of the guard |

---

## 4. Static-token-over-tunnel → 401 (god-token-per-tunnel + revocation fanout) — M2

| # | Check | Expect |
|---|---|---|
| 4.1 | A request bearing the **static** `OPERATOR_TOKEN` on a **tunnel** auth channel | **401** (only a CP-scoped operator session passes) |
| 4.2 | The **same** static token on the **public** connector | **200** (guard is port-scoped) |
| 4.3 | Operator **revocation** action | **every** one of the operator's N tunnels torn down (`revokeOperator` count == N) |

---

## 5. Hub-static fingerprint (OOB anti-MITM) — cross-ref

Already 2/3-triangulated (backend-Python ⟂ deploy-Kotlin, identical) for `dhPubKey` of `hub_c1d6f5ffd892a03d`
(`colonHex(SHA-256)` + 11 PGP-words, `HubFingerprintDisplay`). The **3/3** (human client display ↔ our locked value)
completes at the CONNECT step. Script: `scratchpad/hub_fp.py`. Divergence → **HALT**.

---

## 6. Arbitration protocol

Team-2 run the live teeth (client drive + creds). They paste, per step, the **non-secret** server marker (the exact log
line / the masked verdict / the HTTP status). I arbitrate green/red vs this runbook + the code cites. **Any RED in §3 →
STOP the demo + escalate to the PO immediately** (these are the silent lockout / takeover classes, not cosmetic).

---

## Appendix — RED log strings (watch these verbatim)

- `CYP-525 operator anchor unreadable (tampered/corrupt) — refusing ALL connects` → #2/#9 (lockout / tamper).
- `CYP-525/558 finalize commit FAILED — enrollment NOT persisted` → #6 (storage fault, codes revealed unpersisted).
- `CYP-459 relay connector INERT` / `InertRelayConnector` present → hub never dialed (should be ABSENT).
- Any plaintext recovery code / `session_token` / device seed in a log → **STOP** (#2R.1/2R.2, secret leak).
- Tunnel auth `200` on the static `OPERATOR_TOKEN` → #7 (god-token breach).

**Fail-closed reference:** missing MASTER_KEY → INERT (loud); unstable MASTER_KEY → 2X lockout (silent, catch it);
enroll without CpJwt → land-grab (STOP); revocation not fanned out → #8. GREEN = the explicit marker, never silence.
