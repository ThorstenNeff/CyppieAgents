# CYP-525 — Operator Device Enroll: Completion Semantics (JOINT design record)

> Status: **ratified design (PO 2026-07-13) — no build yet.** Design-pass-first for a security-critical seam.
> JOINT: Backend (hub) authored; **Dev annotates the client-half sections** (marked ▸CLIENT). Gate evidence + durable record.
> Ratified branch state: CYP-525 hub `c788e92c`/`7203208b` (F2 atomic enroll) + Dev's client `def6ddc7…` (Inc-3a) on the shared branch.

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
        hub  : BackupCodeStore.generate(10)                               // FRESH codes; replaces any prior provisional set
        hub → client : EnrollResponse { backupCodes }                     // E2E over Noise, relay blind; the ONE reveal
        ▸CLIENT      : show RecoveryCodesReveal ; user confirms "saved"
        client → hub : SavedAck                                            // user-saved, NOT mere receipt
        hub  : FINALIZE — persist code-hashes (durable) THEN set the anchor (enrollFirstDevice, the atomic commit)
      hub          : proceed to CONNECTED / start the byte-bridge
```
- On `firstEnroll=false` (already Finalized): no EnrollResponse, no ack — straight to CONNECTED (steady-state verify).
- **Anchor is ALWAYS the last write.** A crash between code-hash-persist and anchor leaves orphaned hashes with no
  anchor → the next connect is still `firstEnroll=true` → TOFU re-gens (`generate()` replaces the orphans) → safe.

---

## 3. Invariants (the security core)

1. **★ No-lockout (headline):** there is **no reachable state** where (Finalized / never-re-enroll) ∧ (no usable recovery
   codes). The anchor is set ONLY at finalize, and finalize requires code-hashes-persisted + user-saved-ack.
2. **Hub-authoritative:** `grant.firstEnroll` is the single source of truth for first-vs-recurring. The client obeys it,
   never its own local `isEnrolled` — resolving any client/hub desync (a client that thinks it's enrolled but whose
   provisional the hub discarded is told `firstEnroll=true` and re-reveals fresh codes).
3. **Provisional discard = completing the FIRST enroll, NOT a re-enroll** → the Q6 recovery seam (never-central-login-alone)
   is untouched: never-re-enroll applies ONLY to Finalized devices.
4. **Fresh codes per provisional attempt:** `BackupCodeStore.generate()` **replaces** the prior set, so a discarded
   provisional's codes are invalidated — no stale-code ambiguity.
5. **CT-2b (from the base enroll):** the device owner = the CpJwt-authenticated operator (`principal.identityId`), never a
   payload claim. Codes are generated only under that authenticated first-enroll; never logged; salted-SHA-256 at rest.

---

## 4. Responsibilities

**▸HUB (Backend, my half):**
- Defer `enrollFirstDevice` (the anchor) from immediate → until `SavedAck` (change vs today's `firstEnroll​ThenGrant`).
- Hold provisional state in-memory only (reload-safe discard).
- Generate + deliver codes (`BackupCodeStore.generate()` at provisional; `EnrollResponse{codes}` frame).
- Finalize atomically: persist code-hashes THEN anchor (anchor = the F2 `synchronized` atomic commit).
- **Make `BackupCodeStore` durable** (salted-hash-at-rest, SecretStore-backed like the device store) — REQUIRED for honest
  post-restart recovery. (+S sub-item.)
- `:core`: `TunnelAuthGrant.firstEnroll: Boolean = false` (additive) + `EnrollResponse{backupCodes}` + `SavedAck` wire types.

**▸CLIENT (Dev — annotate/confirm):**
- Always send `devicePublicKey` (32B) in `TunnelAuthRequest`.
- On `grant.firstEnroll == true`: show RecoveryCodesReveal from `EnrollResponse.backupCodes`, **regardless of local
  `isEnrolled`**; gate `SavedAck` on the user's explicit "saved" confirmation (within-flow), then send `SavedAck`.
- Treat CONNECTED as gated on the hub's post-ack finalize.
- **▸ Simplification (ratify):** the server-authoritative `firstEnroll` makes the **persistent** client ack-store
  (`a22aa72f`) **redundant for correctness** — reduce it to **within-session / within-flow** state. The correctness path
  is `hub firstEnroll → re-reveal-fresh-codes on provisional-discard`, NOT a client memory of a prior ack. A persisted
  past-ack is at best inert and at worst harmful (it could suppress a *needed* re-reveal after a hub-side discard, since
  the old codes are already invalidated by `generate()`-replace).

---

## 5. Failure modes (all resolve to no-lockout)

| Event | Outcome |
|---|---|
| Drop/close after `EnrollResponse`, before `SavedAck` | provisional discarded → next connect `firstEnroll=true` → re-TOFU, FRESH codes |
| Hub restart before finalize | provisional not persisted → discarded → re-TOFU, fresh codes |
| Crash between code-hash-persist and anchor | orphaned hashes, no anchor → next connect `firstEnroll=true` → re-gen replaces orphans |
| Client thinks enrolled, hub discarded provisional | hub says `firstEnroll=true` → client re-reveals fresh codes (hub-authoritative) |
| Finalized device, client restart | hub `firstEnroll=false` → skip reveal → steady-state verify (no client ack-persistence needed) |

---

## 6. Build delta + estimate (unchanged from the ratified estimate)

Hub: provisional-hold → SavedAck → atomic finalize + durable BackupCodeStore + the 3 wire types. **M + durable-store S**,
folds into CYP-525. Teeth: gen-at-first-enroll · ack-gate (no-ack → discard → re-TOFU-fresh-codes) · atomic-finalize
(anchor-last; crash-between → re-gen) · honest-storage (hash-at-rest, single-use, durable-survives-restart) · no-lockout
invariant. **Build only after Dev concurs on §4 ▸CLIENT + the PO ratifies the joint semantics.**
