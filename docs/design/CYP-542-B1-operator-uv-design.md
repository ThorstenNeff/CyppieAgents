# CYP-542 / B1 — Real Operator User-Verification (App-PIN baseline + platform-authenticator enhancement)

> Status: **DESIGN-PASS, ratification-ready v1 (design-not-build, security-critical)** · Author: Frontend/Client dev
> Base: `develop @ eb705236` · Epic: CYP-427 M2 · Phase **A** (from the N-scale scope: A = real-UV → deploy → desktop validation)
> Related: CYP-537/535 (N-tunnel milestone, merged `d7bce29d`) · CYP-525 (software-Ed25519 device key) · CYP-443 Slice 2 (UV + PoP seams) · CYP-413 (`SecureSessionStore` DEVICE_SECURE) · UIUX `docs/design/desktop-remote-operator-ux-spec.md`
> No build, no `develop` touch until PO ratifies. Backend/Reviewer/Tester pulled at build.

---

## 1. The problem (B1) — remote is INERT in prod

The N-tunnel milestone (CYP-537) proved the datapath (16 concurrent Noise tunnels, 1-UV-for-N, cross-tunnel anti-replay) — but **remote does not authenticate in prod**. The operator UV in the live assembly is the fail-closed stub:

```kotlin
// RemoteHubMode.jvm.kt:184
private val deferredUserVerification = UserVerification { UvOutcome.Unavailable }
```

`OperatorDeviceKeyStore.sign()` calls `userVerification.verify()` **first** and fail-closes on `Unavailable` ⇒ **no PoP is ever produced ⇒ no tunnel authenticates ⇒ CONNECTED is unreachable in prod.** The joint e2e proved 1-UV-for-N only with a *test-injected* verifying UV (via the `rawUserVerification` seam). **B1 makes the UV real** — the hard gate for dogfood. "Core proven" ≠ "remote usable"; this closes that gap.

## 2. Principles (non-negotiable)

- **P1 — Software baseline, no hardware requirement (`no-hardware-no-shortcuts`).** The **App-PIN** path MUST work on **every** platform/machine with **zero** hardware (Linux/CI included). A platform authenticator (biometric/Secure-Enclave) is an **enhancement, never a requirement** — its absence never blocks the operator.
- **P2 — No shortcuts: the UV cryptographically gates signing.** A UV that is a mere UI gesture while the device key sits **plaintext** on disk (the current CYP-525 `0600` custody) is a shortcut — the key is usable without the PIN by anyone who reads the file. B1 **binds the PIN to the key**: the PIN derives the key-encryption-key that protects the Ed25519 key at rest, so a signature is impossible without the correct PIN. (Reviewer flagged this class in CYP-478/CYP-525.)
- **P3 — Fail-closed, no-plaintext, honest failures.** `Denied`/`Unavailable` ⇒ **no signature** (never a bypass). The PIN is **never stored plaintext** and **never logged**; the key at rest is **never plaintext**. `WRONG_PIN`/`CANCELLED`/`LOCKED_OUT` stay **local + retryable**, distinct from a hub reject (the CYP-443 honesty rail).

## 3. The seam it targets (already built — B1 fills it)

The `UserVerification` seam is injected at the composition root; B1 is its real impl, plugged into the **milestone-proven** path:

```
real UserVerification  →  rawUserVerification (RemoteHubMode.jvm.kt, F⑥-1 test-seam default→this)
                       →  buildOperatorUvCache(rawUv) = ONE shared CachingUserVerification
                       →  the ONE operatorAuth's store.userVerification
                       →  session control tunnel + pool's N workspace tunnels
```

⇒ **1-UV-for-N becomes REAL** (not test-injected): one PIN/biometric ceremony authorizes the session tunnel + all N pool tunnels within the bounded reuse window. The `rawUserVerification` param's prod default flips from `deferredUserVerification` to the real UV; the test-seam override (Assist-gated INERT-in-prod) stays for tests.

## 4. Mechanism

### 4.1 App-Passphrase baseline (the no-hardware path — everywhere)

> **Naming (freeze ②):** the no-hardware secret is an **App-Passphrase ≥ 64 bit** (strength-meter-enforced), NOT a short PIN — a short PIN is offline-GPU-forceable even under Argon2id. A short PIN is allowed ONLY on hardware-backed installs (§4.2). Frozen KDF params: **Argon2id `m≥64 MiB, t≥3, p=1`** (single-sourced const, §8.1).

**At-rest custody (replaces CYP-525 plaintext `0600`):** the Ed25519 device key is stored **AEAD-encrypted under a passphrase-derived key-encryption-key (KEK)**:

- **Enrollment / set-passphrase:** generate the Ed25519 key (CYP-525) → operator sets a passphrase (≥64-bit, meter-enforced) → `KEK = Argon2id(passphrase, per-install random salt, m≥64MiB/t≥3/p=1)` → `sealed = AEAD_encrypt(KEK, pkcs8_privkey, aad = {salt‖kdf-params‖version‖key-id})` (H-2 AAD binding) → persist `{salt, kdf-params, aead-nonce, sealed, x509-pub, attempt-state, vault-version}` owner-only (the CYP-525 `writeOwnerOnly` atomic pattern) in the DEVICE_SECURE store. **No plaintext key, no plaintext passphrase** (only salt + params + ciphertext).
- **Verify (`sign` path):** prompt passphrase → `KEK = Argon2id(passphrase, salt, …)` → `AEAD_decrypt(KEK, sealed, aad)`; **decrypt+tag success == correct passphrase** (the AEAD tag is the verifier — no separate hash) → `UvOutcome.Verified` + the decrypted key held (as `ByteArray`, H-1) for the bounded window (§4.4). Decrypt/tag failure ⇒ `Denied(WRONG_PIN)`; cancel ⇒ `Denied(CANCELLED)`; lockout ⇒ `Denied(LOCKED_OUT)`; no vault/authenticator ⇒ `Unavailable`.
- **Rate-limiting (LOCKED_OUT):** a persisted, tamper-evident attempt counter → after N failures, escalating backoff → `LOCKED_OUT`. Bounds **online** guessing ONLY (H-4 — an offline attacker with the file ignores it; the offline barrier is Argon2id + the ≥64-bit floor, §6).
- **Corrupt vs missing (freeze ③):** a **missing** vault ⇒ first-enroll (OK). A **corrupt/tampered** vault ⇒ `Unavailable` + OOB-recovery, **NEVER** auto-re-enroll/new-key (that would be a key-substitution vector — an attacker corrupts the vault to force a key they control; CYP-525-H1b class).

### 4.2 Platform-authenticator enhancement (where present, never required)

`Fido2OperatorDeviceKeyStore` already exists (stubbed). Where the OS offers a platform authenticator (macOS Touch ID / Secure Enclave, Windows Hello), the device key is **non-exportable in hardware** → the at-rest question is sidestepped (CYP-525 KDoc's "ratified end-state") and the UV is the OS biometric prompt. **Selection at the composition root:** platform-authenticator **iff** enrolled + available on this OS; **else** the App-PIN vault (§4.1). Absence of a platform authenticator NEVER blocks (P1).

### 4.3 expect/actual layout (desktop-first jvm; iOS/web = ②)

| Layer | commonMain | jvmMain (now) | iOS / web (②, deferred) |
|---|---|---|---|
| `UserVerification` orchestrator (select platform-auth vs PIN; map outcomes) | ✓ | — | — |
| `PinVault` (interface: enroll/verify/isEnrolled/lockState) | interface | JCE actual (AES-GCM + KDF + owner-only store) | Keychain / IndexedDB-② |
| KDF + AEAD + secure-random | `expect` seams | JCE/`java.security` actual | later ② |
| Platform authenticator availability + prompt | `expect fun platformAuthenticator(): PlatformAuthenticator?` | Fido2 (macOS/Win) — later even on jvm | Keychain/WebAuthn-② |
| PIN-entry dialog (set/enter/change/locked) | Compose (UIUX) via a `PinPrompt` seam | injected at root | injected at root |

Web has no persistent local key custody in the same sense (WebAuthn-only) → a **separate ② epic** (matches the remote-desktop-first Path-A; non-jvm `buildRemoteHubTransport` actuals are already `null`).

### 4.4 The 1-UV-for-N ↔ encrypted-key reconciliation (KEY point — Reviewer)

The milestone's **1-UV-for-N** means the `CachingUserVerification` caches the `Verified` assertion for a bounded window (`OPERATOR_UV_REUSE_WINDOW_MS = 120s`). With a PIN-**encrypted** key this implies: the **decrypted key (or the KEK) is held in memory for the SAME window** so the N cached signings can run without a re-prompt. Consequence + mitigation:

- **Bounded in-memory exposure:** the plaintext key lives in memory only for the reuse window (≤120s), then the cache expires → **zeroize** the key material + drop it → the next tunnel re-prompts. This is the honest, bounded cost of 1-UV-for-N with at-rest encryption.
- **Zeroization:** hold the key in a `ByteArray`/`SecretKey` that is explicitly cleared on window-expiry / session-close / teardown (never rely on GC).
- **Alternative (stricter, worse UX):** decrypt-per-sign (no key cache) — but then 1-UV-for-N would require caching the **KEK** anyway (to avoid re-KDF per sign), so the exposure is equivalent; caching the decrypted key is simpler. Recommend the bounded-window hold + zeroize.

## 5. Composition with CYP-525 (software-Ed25519)

CYP-525's `PersistentOperatorDeviceKey` (plaintext PKCS#8, `0600`, KDoc: "OS-keystore binding is the later hardening") is **that hardening**: B1 wraps the same Ed25519 key's at-rest custody in the §4.1 PIN-AEAD vault and adds the real UV prompt. The key identity/enrollment/TOFU (CYP-525) is **unchanged** — the hub still TOFU-pins the same raw-32B public key; B1 only changes **how the private key is protected + how signing is authorized**. Migration: an existing plaintext `operator-device.key` → on first B1 run, prompt to **set a PIN** and re-seal the same key (preserving the hub-enrolled anchor — no re-enroll), then delete the plaintext file (atomic). (A cleaner cutover option: treat the plaintext key as "not yet PIN-protected" → set-PIN flow; discuss in §8.)

## 6. Security analysis

- **Offline brute-force (the no-hardware floor):** an attacker with the file guesses PINs offline; the ONLY barrier is **KDF hardness** → mandate **Argon2id** (memory-hard, high cost) — NOT a fast hash/PBKDF2-low. Because a short numeric PIN + any KDF is weak offline, **allow a passphrase** (not only a 4–6-digit PIN) and document the trade-off; the platform-authenticator enhancement (§4.2) removes this floor where present.
- **Online guessing:** bounded by §4.1 rate-limiting / `LOCKED_OUT` (persisted, tamper-evident; a reset of the counter must not reset faster than the cooldown).
- **In-memory exposure:** bounded window + zeroize (§4.4).
- **Fail-closed:** `Denied`/`Unavailable`/lockout ⇒ no signature; a corrupt/again-plaintext/missing vault ⇒ `Unavailable` or the enroll/recovery flow (never a silent bypass).
- **No-plaintext-log:** PIN, KEK, decrypted key, salt-with-context are NEVER logged (extend the CYP-525 "never logged" rule).
- **Anti-replay / server-verify UNCHANGED:** the PoP is the same Ed25519 signature over the channel-bound challenge; the hub verify + cross-tunnel anti-replay (WS1) are UV-source-independent (the milestone e2e already proved this). B1 is purely the client-local authorization + at-rest custody.

## 7. UIUX convergence (route via PO → UIUX; home = `desktop-remote-operator-ux-spec.md`)

Seams UIUX must fill (B1 is UI-agnostic; the `PinPrompt` seam is injected):
1. **Set-PIN / enrollment** (first-run, alongside CYP-525 device-key enroll): PIN vs passphrase policy copy, confirm, strength hint.
2. **Enter-PIN** (the UV prompt at connect): reason-bound copy (`UvReason.OPERATOR_AUTH`), retry on `WRONG_PIN`, `CANCELLED` = user-abort (retryable, not a reject).
3. **LOCKED_OUT / backoff** messaging (honest cooldown, never "hub denied").
4. **Change-PIN** (re-seal the key under a new PIN).
5. **Biometric prompt** (platform-authenticator path) + graceful **fallback-to-PIN**.
6. **"No UV available"** fail-closed state (Unavailable) — honest, actionable.
The existing CYP-525 honesty rails (UvFailed ≠ hub reject) already scope the distinct-cause copy.

## 8. Decisions — ✅ RATIFIED (PO final, 2026-07-14, post Reviewer crypto-lens + UIUX grounding)

> **BUILD GO** (client lane; Backend=none; Reviewer at-code; Tester teeth). The 7 rulings stand; the Reviewer crypto-lens froze 3 blocker-class values + H-1..H-5 build-time items; UIUX grounding reduces scope (the UV-UI already exists).

### 8.1 Frozen blocker-class values (Reviewer crypto-lens)
- **① Argon2id params FROZEN:** `m ≥ 64 MiB, t ≥ 3, p = 1`, **single-sourced const** — stronger than login minima because this is an **offline grind** and the KDF is the ONLY offline barrier.
- **② Entropy floor FROZEN (D3):** the **no-hardware path = App-PASSPHRASE ≥ 64 bit** (6-word diceware / 12+ random chars), **strength-meter-enforced**. **NO short PIN** on no-hardware (8 digits ≈ 26 bit is offline-GPU-forceable even under Argon2id). **Rename "App-PIN" → "App-Passphrase" for the no-hardware path.** A short PIN is allowed **only** on hardware-backed installs (platform authenticator present → the Enclave rate-limits).
- **③ Corrupt-vault behavior FROZEN:** **fail-closed (`Unavailable` + OOB-recovery), NEVER re-enroll/new-key.** Distinguish `missing` (first-enroll OK) vs `corrupt` (tamper → a **seizure / key-substitution** vector, the CYP-525-H1b class). Auto-re-enroll on a corrupt vault would let an attacker delete/corrupt the vault to force a new key they control → this closes that hole.

### 8.2 Build-time hardening (H-1..H-5, Reviewer reviews at code)
- **H-1 — zeroize honesty:** hold key material in `ByteArray` (not `SecretKey`), the passphrase in `char[]` (not `String`); minimize copies; explicit clear (never GC-reliance).
- **H-2 — AEAD AAD binding:** bind context (salt/kdf-params/version/key-id) into the AEAD **AAD** so ciphertext can't be transplanted across params/installs.
- **H-3 — seal → verify → delete order:** on re-seal/migration, write the sealed vault, **verify it decrypts**, THEN atomically delete the plaintext (never delete-before-verify).
- **H-4 — rate-limit honesty:** the counter bounds **online** guessing only (an offline attacker with the file ignores it) — the doc/code must NOT overclaim it as offline protection (that's ① + ②).
- **H-5 — downgrade resistance:** **pin the enrolled method**; a platform-auth install must NOT silently fall back to a weaker PIN — a method downgrade requires an **explicit re-enroll**.

### 8.3 UIUX grounding (scope reduction — the UI already exists)
The UV-UI **already exists** (CYP-460: `OperatorAuthDialog` / `OperatorAuthTags` / `remote_pop_*` keys). **B1 = the crypto IMPL + wiring (flip the `rawUserVerification` prod default) + UIUX's thin 4-gap delta (U1–U4), NOT a from-scratch UI.** Build against the existing dialog; **U1 enroll-field = Passphrase** (per ②, strength-meter). U2–U4 = the remaining UIUX delta (converge via PO — the ratification message tail was truncated at "Konvergie…"; the exact U2–U4 spec is requested before the UI-wiring step; the crypto core + `rawUserVerification` wiring do not depend on it).

### 8.4 Original decision framing (now ratified above — retained for context)

- **D1 — KDF:** Argon2id (needs a KMP/jvm crypto dep — e.g. a bundled Argon2 lib) **[recommended]** vs JDK-only PBKDF2-HMAC-SHA256 high-iteration (no new dep, weaker). Security ↔ dependency trade-off.
- **D2 — Key-binding variant:** PIN-KEK-AEAD-encrypts-the-key (§4.1) **[recommended, no-shortcut]** vs UV-as-gate-only + rely on an OS keystore for at-rest (simpler, but trusts the OS keystore + leaves the "UI-gesture" concern on Linux/CI where no strong keystore exists).
- **D3 — PIN vs passphrase policy** (min length; allow passphrase) — drives the offline-brute-force floor (§6).
- **D4 — Rate-limit params** (attempts before backoff, cooldown curve, lockout persistence + tamper-evidence).
- **D5 — 1-UV-for-N in-memory window** (§4.4): accept the 120s decrypted-key hold + zeroize, or a shorter window for the auth path.
- **D6 — Migration** of an existing plaintext CYP-525 key: in-place re-seal (preserve anchor) vs treat-as-unprotected → set-PIN (§5).
- **D7 — Scope:** jvm-desktop only now; iOS/web UV = ② (deferred with the transport ②). Confirm.

## 9. Build scope (my client lane, post-ratification)

commonMain: the `UserVerification` orchestrator + `PinVault` interface + KDF/AEAD/secure-random `expect` seams + lock-state logic + the `PinPrompt` seam. jvmMain: JCE actuals (Argon2id/PBKDF2 per D1, AES-GCM, owner-only DEVICE_SECURE store, secure-random) + platform-authenticator availability (Fido2 stub → real later). Wiring: flip `rawUserVerification`'s prod default to the real UV; enrollment sets/persists the PIN vault around the CYP-525 key. Teeth (with Tester/Reviewer): fail-closed on Denied/Unavailable/lockout; wrong-PIN ≠ hub-reject; rate-limit → LOCKED_OUT; no-plaintext at rest + no-plaintext-log; zeroize-after-window; 1-UV-for-N through the real vault; migration preserves the anchor. Backend: none (server-verify unchanged). Reviewer: KDF/at-rest/rate-limit/zeroize security review.
