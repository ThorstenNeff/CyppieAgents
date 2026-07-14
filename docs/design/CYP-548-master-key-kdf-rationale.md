# CYP-548 — Hub Master-Key Custody KDF: PBKDF2-600k vs Argon2id (rationale)

> Status: **DECISION — keep PBKDF2-HMAC-SHA256 @ 600k (documented rationale), Argon2id deferred behind a stated trigger.** Docs-only. Server-lane, ungated eval; no B1 build. Object of evaluation: `server/.../crypto/MasterKeyCustody.kt` (`PassphraseMasterKeyCustody` / `Pbe`, CYP-434).

## Question

Should the hub master-key custody move from **PBKDF2-HMAC-SHA256 @ 600,000 iters** (CYP-434) to **Argon2id** for product consistency (Kratos hashes end-user passwords with Argon2id), or deliberately stay PBKDF2-600k?

## Decision

**Stay PBKDF2-600k for now.** It is OWASP-current and correct for *this* asset's threat model; Argon2id's advantage (memory-hardness) targets a threat this custody does not primarily face, and adopting it would add a native dependency disproportionate to a non-default option. A clean `v2:` Argon2id migration remains free later (see §Migration-readiness), so deferring locks nothing in.

## Why — the threat models differ (the crux)

The "product consistency" pull is skin-deep: Kratos and the hub master-key custody solve **different** problems.

| | **Kratos end-user passwords** (Argon2id) | **Hub master-key custody** (PBKDF2-600k) |
|---|---|---|
| Secret entropy | **Low** — user-chosen passwords | **High** — an operator passphrase (peer of the `ANTHROPIC_API_KEY` / env-keyset secrets) |
| Attack | **Online, N-guess** — no account lockout by design (a lockout is a victim-DoS, RC2 flag-1), so the **per-attempt KDF cost floor IS the brute-force defense** | **Offline** — brute-force of a **stolen `0600` wrapped-keyset file** (`PassphraseMasterKeyCustody.wrappedPath`), reachable only after host compromise |
| KDF invocation | per login attempt (hot path) | **once, at boot** (single-boot-unlock) |
| Where the defense lives | the KDF cost (memory-hard raises per-online-guess cost) | **the passphrase entropy** (primary) + `0600` at-rest + host security; the KDF is *secondary* defense-in-depth |

For low-entropy, online-guessable secrets with no lockout (Kratos, and the B1 client-device-PIN case), **Argon2id's memory-hardness is essential** — it is the per-attempt cost floor. For a **high-entropy operator passphrase** sealed in a `0600` file, the entropy already makes offline brute-force infeasible regardless of KDF; PBKDF2-600k is sufficient secondary hardening. Same word "password", different security problem.

## Supporting factors (keep PBKDF2-600k)

1. **OWASP-compliant.** 600,000 iterations is OWASP's current PBKDF2-HMAC-SHA256 recommendation. AES-256-GCM KEK, random 16-B salt, 96-b nonce, 128-b tag, fail-closed on tag mismatch (no oracle) — a textbook, reviewed construction (CYP-434).
2. **Not the default path.** The MVP default is `EnvKeysetMasterKeyCustody` — a Tink keyset injected out-of-band (`CYPPIE_MASTER_KEY`), **no passphrase KDF at all**. `PassphraseMasterKeyCustody` is a Phase-1 *option*. Hardening a non-default option is low-yield.
3. **JDK-only, zero new dependency.** PBKDF2 ships in the JDK (`SecretKeyFactory("PBKDF2WithHmacSHA256")`). Argon2id requires a 3rd-party lib with **native binaries** (e.g. `argon2-jvm`) or BouncyCastle — a supply-chain + per-target deploy surface cost that is disproportionate for a non-default option, and one the JDK-only custody deliberately avoids.
4. **Single-boot-unlock ⇒ cost is not the constraint.** The KDF runs once at boot, so neither PBKDF2-600k nor a heavier Argon2id has a hot-path cost — meaning the choice is driven purely by the threat model above, not by performance.

## Migration-readiness (deferring is free)

The sealed envelope is **versioned and self-describing**: `v1:iters:salt:nonce:ct`, and `open()` reads the stored `iterations` from the blob (`SealedKeyset`). A future Argon2id adoption is a clean **`v2:algo:params:salt:nonce:ct`**: read `v1` with PBKDF2, write `v2` with Argon2id (re-seal on next unlock) — no forced migration, no lock-in. So this decision is reversible at zero cost when a trigger below fires.

## Trigger to revisit (adopt Argon2id when ANY holds)

1. **`PassphraseMasterKeyCustody` is promoted to the default/primary path** (operators routinely unlock with a passphrase instead of an env-keyset) — the offline-brute-force surface then becomes primary, and memory-hardness earns the native-lib cost.
2. **Low-entropy operator passphrases become plausible** (e.g. a human-memorable phrase policy) — the passphrase-entropy assumption that carries PBKDF2-600k no longer holds.
3. **The project adopts an Argon2 library for another reason** (a self-hosted auth path beyond Kratos) — fold the hub custody into it for *genuine* consistency at no marginal dependency cost.

## Scope note

This is the **hub master-key custody** only. B1's client-device credential (the N-guess-device case) is a separate track and correctly uses a memory-hard / rate-limited posture there — nothing in this decision touches it.
