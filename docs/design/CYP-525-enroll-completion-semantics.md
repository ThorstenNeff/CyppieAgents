# CYP-525 — Operator Device Enroll: Completion Semantics (JOINT design record)

> Status: **REVISION 2 — awaiting PO re-ratification + Reviewer re-review; build HELD.** The first ratification
> (`cbd893ac`) was NO-GO on an adversarial pre-review: H1 (crash-durability: program-order ≠ storage-order) + H2
> (concurrent-provisional cross-contamination = a Q6-escalation) + H3 (client SavedAck must gate on a validated set).
> This revision converges H1+H2 into ONE atomic, serialized, fsync'd finalize record {session code-hashes + anchor}.
> Design-pass-first for a security-critical seam. JOINT: Backend (hub) authored; **Dev annotates ▸CLIENT**.

---

## 0. The problem this seam solves

TOFU first-enroll must deliver **honest recovery backup codes** to the operator exactly once. The hazard: if the hub
**anchors** the device (so `isEnrolled`/never-re-enroll applies) but the operator closes **before** confirming they saved
the codes, the next connect **skips** enroll (isEnrolled-first) and the single-use codes **cannot be re-revealed** (the hub
holds only salted hashes) → **permanent lockout** — the exact limbo GE2 exists to prevent.

**Root cause:** `receipt-ack ≠ user-saved-ack`, and the device-anchor commit was decoupled from code custody + user
confirmation. Fix: a **provisional → finalized** two-phase enroll with the **anchor as the last atomic commit**.

---

## 1. State model

| State | Meaning | `isEnrolled`/never-re-enroll? | Persisted? |
|---|---|---|---|
| **Empty** | no device anchored | no | — |
| **Provisional** | PoP-verified, device key held, fresh codes generated + sent, **awaiting user-saved-ack** | **NO** — not anchored, not in the never-re-enroll set | **NO** (reload-safe: a restart pre-finalize discards it) |
| **Finalized** | user-saved-ack received; code-hashes persisted (durable) **and** device anchored, atomically | **YES** | **YES** (durable) |

**Definition — "fully enrolled" (client-skippable + never-re-enroll) ⟺ Finalized ONLY**, and Finalized ⟺
`{ PoP-verified ∧ code-hashes-persisted-durable ∧ user-saved-ack }`, committed atomically.

---

## 2. The finalization protocol (RR3-handshake extension, first-enroll only)

```
client → hub : TunnelAuthRequest { cpJwt, pop, nonce, devicePublicKey }   // devicePublicKey ALWAYS present (cheap 32B)
hub          : verify CpJwt (sub==pinnedOperatorId) ; verify PoP against the PRESENTED key (possession)
hub → client : TunnelAuthGrant { granted=true, firstEnroll = (store NOT finalized) }
      if firstEnroll:
        hub  : acquire the FIRST-ENROLL LOCK (single provisional in-flight) ; recheck-Finalized under lock  // H2
        hub  : minted = BackupCodeStore.mint(10)                          // THIS session's codes — held LOCAL, not installed
        hub → client : EnrollResponse { backupCodes = minted.plaintexts } // E2E over Noise, relay blind; the ONE reveal
        ▸CLIENT      : validate a complete, non-empty code set ; show RecoveryCodesReveal ; user confirms "saved"   // H3
        client → hub : SavedAck                                            // user-saved, NOT mere receipt
        hub  : FINALIZE = ONE atomic, fsync'd commit of { minted.entries (THIS session) + device anchor } together   // H1+H2
        hub  : release the FIRST-ENROLL LOCK
        hub → client : TunnelAuthGrant { granted=true, firstEnroll=false } // ★ the CONNECTED signal — sent ONLY after fsync
      hub          : start the byte-bridge  (== CONNECTED)
```
- **CONNECTED signal (the divergence-risk decision, pinned):** an **explicit final frame** — a second
  `TunnelAuthGrant { granted=true, firstEnroll=false }` sent **only after the finalize fsync completes** — is the
  CONNECTED signal; the client reads grants until it sees `granted=true ∧ firstEnroll=false` (steady-state = the first
  grant; first-enroll = this second grant after the reveal/ack/finalize). The client **never infers** CONNECTED from the
  byte-bridge bytes. (Reuses the committed grant type — no 4th `:core` frame.)
- On `firstEnroll=false` (already Finalized): no EnrollResponse/ack — that grant IS CONNECTED, bridge starts.
- **★ H1+H2 converged finalize:** the code-hashes (of THIS provisional session, not "current store") **and** the device
  anchor are committed as **ONE atomic, fsync-durable record under the first-enroll lock**. So (H2) the race-loser never
  installs — no cross-contamination — and (H1) the anchor is never durable before the hashes (one record, one flush).
  No `SavedAck` / a crash before the fsync completes → nothing durable → next connect `firstEnroll=true` → re-mint FRESH.

---

## 3. Invariants (the security core)

1. **★ No-lockout (headline):** there is **no reachable state** where (Finalized / never-re-enroll) ∧ (no usable recovery
   codes). Finalize is ONE atomic fsync'd record of {code-hashes ∧ anchor} — both durable or neither.
2. **★ H1 — atomic crash-durable finalize:** the code-hashes and the anchor commit as **ONE atomic, fsync-durable
   record**; the CONNECTED frame is emitted **only after** the fsync returns. There is no reorder window (one write, one
   flush) — a power-loss crash can never leave (anchor durable ∧ hashes lost). (Requires verifying `SecretStore` atomic
   multi-key write + flush — §4; if unsupported, wrap: write the combined record + explicit flush before the anchor
   counts as committed.)
3. **★ H2 — serialized, session-bound finalize (SECURITY, defeats a Q6-escalation):** first-enroll is **serialized** by a
   first-enroll lock (ONE provisional in-flight; a 2nd first-connect rejects/queues), and finalize installs **THIS
   provisional session's** minted code-hashes, **never "the current store".** So a central-login-alone attacker cannot
   race a 2nd provisional to make the persisted codes theirs (valid backup codes without a device → recover/seize =
   defeats Q6). The race-loser never installs → no cross-contamination. The F2 `synchronized` (anchor only) is
   necessary but NOT sufficient — the SAME lock must cover mint→install→anchor.
4. **Hub-authoritative:** `grant.firstEnroll` is the single source of truth for first-vs-recurring. The client obeys it,
   never its own local `isEnrolled` (a client that thinks it's enrolled but whose provisional the hub discarded is told
   `firstEnroll=true` and re-reveals fresh codes).
5. **Provisional discard = completing the FIRST enroll, NOT a re-enroll** → the Q6 recovery seam (never-central-login-alone)
   is untouched: never-re-enroll applies ONLY to Finalized devices.
6. **Fresh codes per attempt:** each provisional `mint()`s its OWN set; only the Finalized one installs — a discarded
   provisional's codes are never installed, so there is no stale-code ambiguity.
7. **CT-2b (from the base enroll):** the device owner = the CpJwt-authenticated operator (`principal.identityId`), never a
   payload claim. Codes are minted only under that authenticated first-enroll; never logged; salted-SHA-256 at rest.

---

## 4. Responsibilities

**▸HUB (Backend, my half):**
- **First-enroll LOCK** (serialize): only ONE provisional in-flight; a 2nd first-connect rejects/queues (H2).
- Defer the anchor until `SavedAck`; hold provisional state (the minted set) **LOCAL to the session**, in-memory only
  (reload-safe discard) — NEVER in a shared mutable "current store".
- `mint()` the codes at provisional (not install); `EnrollResponse{minted.plaintexts}` frame.
- **Finalize = ONE atomic, fsync-durable commit of {THIS session's `minted.entries` + the device anchor} under the
  first-enroll lock.** Emit the CONNECTED grant **only after** the fsync returns. `install()` runs against the session's
  entries, under the SAME lock as the anchor — the standalone `install()` + separate `SecretStoreBackedBackupCodes`
  record from the held groundwork **merge into this combined finalize record**.
- **Verify the durability primitive (testable requirement, NOT an inline "(durable)"):** does `SecretStore` support an
  atomic multi-key write + flush/fsync? If not → wrap: write the combined record + an **explicit flush** before the
  anchor is treated as committed. Cover it with the H1 crash-window test.
- **Durable groundwork (held):** the `BackupCodeStore` mint/consume/durable primitive + `SecretStoreBackedBackupCodes`
  are ok as building blocks, but the finalize **record-shape** (combined) is what this revision fixes → the store's
  persist path is subsumed by the combined commit.
- `:core` (committed `e3385f4d`): `TunnelAuthGrant.firstEnroll` (additive) + `EnrollResponse{backupCodes}` + `SavedAck`.
  The CONNECTED signal reuses `TunnelAuthGrant{granted=true, firstEnroll=false}` (no 4th frame).

**▸CLIENT (Dev — annotate/confirm):**
- Always send `devicePublicKey` (32B) in `TunnelAuthRequest`.
- On `grant.firstEnroll == true`: **H3 —** show RecoveryCodesReveal from `EnrollResponse.backupCodes` **only after
  validating a complete, non-empty code set** (never `SavedAck` on a bare button-press / an empty or partial set),
  **regardless of local `isEnrolled`**; gate `SavedAck` on the user's explicit "saved" confirmation, then send `SavedAck`.
- **CONNECTED = an explicit grant `{granted=true, firstEnroll=false}`** received after the ack — never inferred from the
  byte-bridge bytes.
- **▸ Simplification (ratify):** the server-authoritative `firstEnroll` makes the **persistent** client ack-store
  (`a22aa72f`) **redundant for correctness** — reduce it to **within-session / within-flow** state. The correctness path
  is `hub firstEnroll → re-reveal-fresh-codes on provisional-discard`, NOT a client memory of a prior ack. A persisted
  past-ack is at best inert and at worst harmful (it could suppress a *needed* re-reveal after a hub-side discard, since
  the old codes are already invalidated by `generate()`-replace).

---

## 5. Failure modes (all resolve to no-lockout)

| Event | Outcome |
|---|---|
| Drop/close after `EnrollResponse`, before `SavedAck` | provisional discarded (nothing committed) → next connect `firstEnroll=true` → re-mint FRESH codes |
| Hub restart before finalize | provisional not persisted → discarded → re-mint fresh |
| **★ H1 — power-loss crash mid-finalize** | ONE atomic fsync'd {hashes ∧ anchor} record → **either both durable or neither**; the CONNECTED grant is emitted only after fsync → NEVER (anchor durable ∧ hashes lost) → **closed by the atomic-fsync record** |
| Pre-fsync crash (nothing flushed) | nothing durable → next connect `firstEnroll=true` → re-mint fresh (the orphan window is now empty) |
| **★ H2 — concurrent first-connects (incl. a central-login-alone race)** | serialized first-enroll lock → one provisional in-flight; finalize installs THIS session's codes → **the race-loser never installs → no cross-contamination, no Q6-defeat** → **closed by the serialized, session-bound finalize** |
| Client thinks enrolled, hub discarded provisional | hub says `firstEnroll=true` → client re-reveals fresh codes (hub-authoritative) |
| Finalized device, client restart | hub `firstEnroll=false` → skip reveal → steady-state verify (no client ack-persistence needed) |

---

## 6. Build delta + estimate (unchanged from the ratified estimate)

Hub: first-enroll-lock (serialize) → provisional `mint` (session-local) → `EnrollResponse` → `SavedAck` → **ONE atomic
fsync'd finalize record {session code-hashes + anchor}** → post-fsync CONNECTED grant. + verify the `SecretStore`
atomic-write/flush primitive (wrap if unsupported). **M + combined-record/durability S.** Folds into CYP-525.

Teeth (revised): gen-at-provisional · ack-gate (no-ack → discard → re-mint-fresh) · **H1 crash-window** (a crash before
the finalize fsync → NO finalized-without-codes; ONE record → both-or-neither) · **H2 concurrent-provisional** (a 2nd
first-connect during a provisional → serialized; the loser never installs → no cross-contamination / no Q6-defeat) ·
honest-storage (hash-at-rest, single-use, durable-survives-restart) · no-lockout invariant · CONNECTED-is-an-explicit-
post-fsync-grant.

**Build only after the PO re-ratifies this revision + Reviewer re-reviews + Dev concurs on §4 ▸CLIENT.** The
`BackupCodeStore` mint/consume/durable groundwork is held (uncommitted) until the combined finalize-record shape is
locked here — then the persist path is built as the combined atomic commit, not a standalone `install()`.
