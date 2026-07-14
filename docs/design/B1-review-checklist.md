# CYP-542 / B1 — Operator-UV At-Code Review Checklist (canonical gate reference)

> Author: Reviewer (adversarial crypto/security lens) · Status: **ratification-ready, pre-build** · For: the B1 code gate (enroll / vault / migration / strength slices)
> Companion design: `docs/design/CYP-542-B1-operator-uv-design.md` (ruled `96272949`) · Ratified concretes: Argon2id `m≥64MiB/t≥3/p=1` · no-hardware = App-Passphrase **≥64 bit** (short-PIN only hardware-backed) · corrupt-vault → **fail-closed + OOB, never re-enroll** · dep = BouncyCastle `bcprov-jdk18on` (pin ≥1.78).
>
> **This is the gate's canonical reference.** Every line is a tooth. A tooth is *proven* only when a named mutation makes it **RED** (per-axis, each mutant reds EXACTLY its own tooth). "Present but never reddened" = a vacuous tooth — treat as unproven.

## Method (how the gate runs)

- Real `git merge --no-ff <sha>` onto **current** `origin/develop` (re-check the tip at gate-start + teardown — develop moves).
- Per-axis mutation: for each check below, apply the named mutation and confirm **only** that check's tooth reds.
- Verify XML `tests=N` (== method count) + `skipped=0` (green ≠ ran).
- Cache-defeated (`--rerun-tasks --no-build-cache`); `:core`-src changes rebuild `:core:jvmJar` in the same invocation.
- No secret ever in `local.properties` in a gate worktree (sdk.dir only); teardown-verify develop==origin + no stray worktree.

---

## A. KDF — Argon2id (the *only* offline barrier; must be exact)

| # | Check | Verify at object | Reddening mutation (non-vacuity) |
|---|---|---|---|
| **A1** | Variant `ARGON2_id` (not `_i`/`_d`) + version `ARGON2_VERSION_13` (0x13, not legacy `_10`) | A **golden KAT**: a fixed (pin, salt, params) → a **byte-exact** derived-key vector (RFC 9106 / a pinned reference). Binds variant+version+params in ONE tooth. | Swap to `_i`/`_d` or `_10` → the KAT output bytes change → RED. (A pure constant-assertion would MISS a silent variant drift — the KAT is why "id/1.3" goes from asserted to proven.) |
| **A2** | Params **m ≥ 65536 KB (64 MiB), t ≥ 3, p = 1** | The frozen constants AND the KAT (which encodes them). | Lower m (e.g. 8 MiB) or t=1 → KAT bytes change → RED. Plus a constant-assertion on m/t/p. |
| **A3** | Salt **≥ 16 B, per-install random, from `SecureRandom`** | Salt length + source. | Fixed/zero salt → "two installs → two distinct salts" tooth RED; `Random`/`Math.random` instead of CSPRNG → source assertion RED. |
| **A4** | KDF output length = KEK size (**32 B** for AES-256-GCM) | Output length feeding the AEAD key. | Wrong length → AEAD key-init fails / length assertion RED. |

## B. AEAD — at-rest seal (AES-GCM)

| # | Check | Verify at object | Reddening mutation (non-vacuity) |
|---|---|---|---|
| **B1** | AES-GCM-256 + **fresh random 96-bit nonce per seal** | Nonce source + freshness. (Seal is O(1): only enroll/change-PIN/migration — signing **decrypts**, never re-seals — so reuse risk is already low; still verify fresh-per-seal.) | Fixed/reused nonce → "two seals → two distinct nonces" tooth RED. |
| **B2** | **AAD binds `version ‖ salt ‖ params ‖ pub ‖ nonce`** | A **per-field tamper tooth**: seal, then flip each AAD field independently → decrypt MUST fail-close. | Tamper any field → decrypt→fail → RED, one tooth per field. **★ Anti-vacuity trap:** REMOVE a field from the AAD construction → its tamper-tooth goes vacuously GREEN → confirm **all 5** fields are really in the AAD. **`pub` is the load-bearing catch** (binds identity to the sealed private key → blocks pub/priv mix-and-match substitution). `nonce`/`params` are GCM-inherently bound (nonce→tag; wrong params→wrong KEK→tag-fail) → for those, AAD is defense-in-depth / tamper-evident, not the sole guard. The `pub`-in-AAD tooth must really bite. |
| **B3** | **Order: seal → fsync+rename → round-trip VERIFY (decrypt with the just-set passphrase) → THEN delete plaintext.** Never delete-before-verify. | Failure-injection: force the seal-verify to fail → assert the **plaintext is NOT deleted (recoverable)**; a successful verify → plaintext deleted. | Reorder to delete-before-verify → the failure-injection tooth shows plaintext gone + seal unverified = **key-loss** → RED. (Note: plaintext secure-delete on SSD/CoW-fs is best-effort — historical plaintext may persist in unallocated blocks; a documented residual, not worsened vs pre-B1.) |

## C. Zeroize (bounded in-memory exposure, §4.4)

| # | Check | Verify at object | Reddening mutation (non-vacuity) |
|---|---|---|---|
| **C1** | Key material held as a mutable **`ByteArray`** (NOT `SecretKeySpec`) and explicitly `fill(0)` on **all** paths (window-expiry / session-close / teardown / exception); **PIN as `char[]`** end-to-end, never a `String`. | A **spy** on the key-holder recording `clear()` per exit path. Grep: no `String` PIN, no `SecretKeySpec` as the zeroize target (`SecretKeySpec.destroy()` is a JVM no-op). | Remove a `clear()` on one path → the spy assertion for that path RED. |

> **Honest scope (H-1):** the tooth proves `clear()` is called on all paths (best-effort). GC-managed copies — the parsed Ed25519 `PrivateKey`, any `BigInteger` scalar, intermediate buffers — cannot be reliably cleared on the JVM. This is **documented, not tested**; do not sell "zeroize complete."

## D. Corrupt-vault fail-closed (the ③ key-substitution guard — Slice-1 already mutation-verified; re-bite at object)

| # | Check | Verify at object | Reddening mutation (non-vacuity) |
|---|---|---|---|
| **D1** | **`corrupt ≠ missing`.** A present-but-corrupt/tampered vault → **fail-closed (`Unavailable` + OOB-recovery signal), never re-enroll / new-key.** A missing vault → first-enroll proceeds. | Two distinct outcomes: (corrupt → `Unavailable`, no new key generated) vs (missing → first-enroll). The discriminator IS the guard. | Remove the guard → corrupt routes to enroll (new key) → the "corrupt → `Unavailable`, no new key" tooth RED. (Else vault-corruption becomes a key-substitution/seizure vector — CYP-525 H1b class.) |

## E. Passphrase strength enforcement (4 teeth — each REAL fail-closed, not merely present)

| # | Check | Verify at object | Reddening mutation (non-vacuity) |
|---|---|---|---|
| **E1** | Generated **Diceware = uniform-random, `SecureRandom` index draw over a ≥7776-word list, ≥6 words (~77 bit)** — the strong one-click default. | List size, word count, RNG = CSPRNG, uniform draw. | 4 words / biased draw (`Random`+modulo bias) / truncated list → entropy or uniformity assertion RED. |
| **E2** | **≥64-bit floor REALLY enforced on type-your-own** — under-floor is **rejected** (enrollment fails, no vault sealed), not merely warned. | Below-floor input → enroll BLOCKED (no key sealed); at-floor → proceeds. | Change `reject if <64` → `warn if <64` (or remove the reject) → the "below-floor → enroll fails" tooth RED. |
| **E3** | **Blocklist rejects common/breached phrases despite structural strength.** | Input must be **structurally strong but blocklisted** (passes the meter, blocklist catches it) → rejected. | Remove the blocklist check → "correct horse battery staple" (structurally ≥64, on the list) is accepted → the "rejected" tooth RED. **★ The test input must be struct-strong-but-blocked** — proving the blocklist is load-bearing, not the meter. |
| **E4** | **The structural meter never overrides the floor or the blocklist** (its score is necessary-not-sufficient; the floor + blocklist are independent hard gates). | No path accepts a passphrase solely on the meter's "strong" verdict. | Make the meter's "strong" verdict bypass the floor/blocklist → a blocklisted-but-meter-strong input is accepted → RED. |

> **Why this shape (the B-1 subtlety):** the Diceware default is the *proof* (uniform-random → structural == real ~77 bit). A **structural** meter over-counts common phrases, and **Argon2id does not compensate** — a dictionary attack has *few* guesses, so the expensive per-guess cost is irrelevant. Hence "structural ≥64" ≠ "≥64-bit guessing-entropy" for type-your-own; the floor (E2) + blocklist (E3) are the hard gates.

## F. Rate-limit (H-4) — honest scope

| # | Check | Verify at object | Reddening mutation (non-vacuity) |
|---|---|---|---|
| **F1** | Escalating backoff → `LOCKED_OUT`; a persisted, tamper-evident (0600 + monotonic timestamp) attempt counter whose **reset must not bypass the cooldown**. | After N wrong attempts → `LOCKED_OUT`; a counter reset does not shorten the active cooldown. | Make the reset clear the cooldown → the "reset does not bypass cooldown" tooth RED. |

> **Honest scope (H-4):** the rate-limit is an **online / app-path** control only. A file-access attacker bypasses it entirely via **offline** brute-force (deriving KEK + trying decrypt, never touching the counter), and the counter cannot be cryptographically tamper-proof without the passphrase-key. Do **not** over-rely on it — the KDF (§A) + passphrase floor (§E) are the real offline defense. Document it as such.

## G. Downgrade-resistance (H-5) — platform-authenticator path

| # | Check | Verify at object | Reddening mutation (non-vacuity) |
|---|---|---|---|
| **G1** | If enrolled with a platform authenticator, a subsequent "platform-auth unavailable" must **not silently downgrade to the weaker PIN/passphrase path** — the enrolled method is pinned; a downgrade requires explicit re-enrollment. | Enrolled-method recorded in the vault; "platform-auth absent" with a platform-enrolled vault → fail-closed, not a silent PIN fallback. | Allow a silent fallback-to-PIN when the enrolled method was platform-auth → the "no silent downgrade" tooth RED. (Else the enhancement is bypassable by *forcing* its absence.) |

## H. Cross-cutting

- **Fail-closed on all paths:** `Denied` / `Unavailable` / lockout / corrupt / missing-authenticator → **no signature, no bypass**. Every non-`Verified` outcome yields no PoP.
- **No-plaintext-log:** PIN, KEK, decrypted key, and salt-with-context are **never logged** (extend the CYP-525 rule). Grep the diff for any log statement touching these.
- **Server-verify unchanged:** the PoP is the same Ed25519 signature over the channel-bound challenge; the hub verify + cross-tunnel anti-replay are UV-source-independent (milestone-proven). B1 is purely client-local authorization + at-rest custody — confirm no server change.
- **Supply-chain (RR5):** BouncyCastle pinned ≥1.78 (no version range) in the version catalog; cross-check which library provides Ed25519 (CYP-525) — CVE-2024-30172 (Ed25519 infinite-loop ≤1.77) matters if that path is BC.

## Scope — what this checklist does NOT cover

- **The live-relay real-network datapath** (all M2 e2e so far uses an in-memory relay) — see the separate loopback live-relay test track (`Cyp536LiveRelayLoopbackE2eTest` design); proves datapath correctness on loopback, no staging needed. WAN/TLS(`wss`)/NAT = ops-validation, Auftraggeber-deploy-gated.
- **iOS/web UV** — deferred to the ② epic (jvm-desktop only now, D7).
