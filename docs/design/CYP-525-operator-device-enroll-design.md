# CYP-525 — Operator Device-Key Enroll Model (Design Pass)

> Status: **design/analysis only — NOT built.** Prepared for the Auftraggeber ratification package.
> Scope: the Dogfood-CONNECTED blocker. Object-grounded against develop `75bb7b2d`.
> Companion: `docs/design/remote-operator-ux-spec.md` §5 (RR2-B ratified 2026-07-11), CYP-469/472/473/476/477 (server op-auth, merged).

---

## 0. The bug, precisely

RR3 tunnel-auth requires an **operator device-signed PoP** bound to the live Noise handshake hash `h`.
The client mints a **fresh Ed25519 keypair every launch** and never persists it:

- `app/shared/.../connect/RemoteHubMode.jvm.kt:70` (INERT factory) **and** `:111` (LIVE factory):
  `keyPair = KeystoreOperatorDeviceKeyStore.generateDeviceKey()` — a new `KeyPairGenerator("Ed25519")` pair per call.
- The intended at-rest custody (`SecureSessionStore` / `DEVICE_SECURE`, CYP-413) is **named but in-memory-only**
  (`SecureSessionStore.jvm.kt:10`) — there is **no load path**.

Consequence at the hub gate (`Rr3TunnelGate.authorize` → `OperatorAssertionVerifier.verifyAny`):
- The durable store is **constructed empty** at `BootOrchestrator.kt:1002` and **never populated** (no enroll route, no boot
  provisioning, `enrollFirstDevice`/`enrollWithBackupCode` have **zero live callers**). → `no_enrolled_device`.
- Even if a device were enrolled, a per-launch fresh client key → `bad_signature`.

**So CYP-525 has TWO halves, both outside the (done) verify path:**
1. **Client:** persist + reuse one device key across launches (the `keyPair` injection seam).
2. **Hub:** actually write an `EnrolledOperatorDevice` anchor (no code path does this today).

**★ Cross-cutting must-fix (any option):** encoding mismatch. The client exposes `devicePublicKey()` as **X.509
SubjectPublicKeyInfo** (`KeystoreOperatorDeviceKeyStore.kt:24`), but the hub enroll validator + verifier expect a **raw
32-byte** Ed25519 key (`OperatorDeviceEnroll.kt:62`, `RawKeys.ed25519Verify`). Whatever enrolls must convert SPKI→raw
(or both ends must agree on one encoding, single-sourced). This is a silent-mismatch trap and belongs in the fix + a tooth.

---

## 1. Factor axis (RR2): (a) software Ed25519 persist+enroll vs (b) WebAuthn/Passkey

**What already exists (both directions):**
- **Server verify is factor-agnostic and complete for both.** `OperatorAssertionVerifier` verifies **Raw** (Ed25519) *and*
  **Fido2/WebAuthn** (CTAP: rpIdHash == SHA-256(rpId) [CYP-473 H1], **UV flag mandatory**, sig over
  `authenticatorData ‖ SHA-256(challenge)`, ES256 or Ed25519). Wire/PoP types (`DevicePoP.Raw|Fido2`,
  `OperatorPoPWire`) exist on both ends; the channel-bound challenge lives in `:core`.
- **Durable enrolled anchor exists** — `SecretStoreBackedOperatorDeviceStore` (CYP-472), encrypted-at-rest,
  tamper-evident, opt-in (`CYPPIE_MASTER_KEY`-gated).

| | **(a) Software Ed25519 persist+enroll** (RR2-A) | **(b) WebAuthn / Passkey** (RR2-B) |
|---|---|---|
| Client state today | `KeystoreOperatorDeviceKeyStore` **works** (Raw sign); only the *persist/load* is missing | `Fido2OperatorDeviceKeyStore` is a **TODO stub** — Linux `AuthenticatorUnavailable`, `sign()` = `TODO(mac/win): libfido2/FFM` |
| Server verify | **Done** (Raw branch, merged) | **Done** (Fido2 branch, merged — rpIdHash/UV/ES256) |
| Key custody | app-managed secret at rest (needs OS-keystore/`DEVICE_SECURE` wiring) | **hardware/OS authenticator** — private key never leaves the secure element; phishing-resistant, `rpId`-scoped |
| Platform reach | JVM/Desktop now; multiplatform later | browser/OS-native FIDO2; **not reachable on the Linux dogfood host** today |
| Ratification | interim; RR2-A | **already RATIFIED as the end-state** (UX spec §5, 2026-07-11) |
| Threat posture | key is only as safe as the app's at-rest store; malware with app scope can exfiltrate | strongest (UV-gated, non-exportable, origin-bound); a compromised app cannot sign without the user gesture |

**Read:** (b) is the ratified *destination* and the server is already ready for it; (a) is the *shortest path to
CONNECTED today* because the only missing piece is client persistence (Raw sign + verify are done), whereas (b) still
needs a real per-platform FIDO2 client signer that isn't reachable on the dogfood host.

---

## 2. Hub-side enroll contract — how operator `CYPPIE_OPERATOR_ID`'s device public reaches the store

(The operator id is env `CYPPIE_OPERATOR_ID` → `Rr3Config.pinnedOperatorId`; the value `ab7c54e3` is the deploy's operator
id, not in the repo.) The enroll primitives exist (`OperatorDeviceEnrollment.enrollFirstDevice`,
`OperatorDeviceRecoveryFlow.enrollWithBackupCode`) but **nothing calls them live**. Three ways to wire the first anchor,
with distinct security envelopes:

### (2-i) Boot / deploy provision
The deploy supplies the operator's device public (e.g. `CYPPIE_OPERATOR_DEVICE_PUBKEY`, raw-32B) and the boot calls
`enrollFirstDevice` once at startup.
- **Security:** strongest / simplest to reason about — the anchor is set **out-of-band by the human** who runs the deploy;
  no online enroll surface, no TOFU window. A compromised central login cannot enroll (there is no online enroll path).
- **Cost:** rigid — re-enroll/rotate = a redeploy (until Q6 recovery lands). Requires the operator to convey their
  persisted pubkey to the deploy once (pairs naturally with (a): generate+persist locally → hand the pubkey to deploy).

### (2-ii) Operator-token-gated self-enroll HTTP route
A new `POST /api/cp/operator-device` (First-Enroll-only) gated by the operator bearer (`CYPPIE_CP_OPERATOR_TOKEN`, already
held) → `enrollFirstDevice` when none enrolled.
- **Security:** the operator-token gate makes it **not a land-grab** (only the token holder can enroll). But it adds an
  authenticated **online enroll surface** and folds device-enroll trust into the operator bearer — a bearer leak then
  enrolls an attacker device. First-Enroll-only (reject-if-enrolled) caps the blast radius; rotation still needs Q6.
- **Cost:** M — a new route + contract op (CYP-234 drift-gate + `exportContract`) + auth wiring.

### (2-iii) TOFU-at-first-PoP, under the CpJwt gate  *(seamless)*
Wire `enrollFirstDevice` **into `Rr3TunnelGate`**: when the store is empty and the tunnel already passed the **CpJwt**
operator check, the first well-formed device PoP's public key becomes the anchor (trust-on-first-use), then normal
verify applies on every subsequent connect.
- **Security — the crux, needs explicit sign-off:** the CpJwt authenticates *an operator session* before the PoP, so the
  first-enroll is **bound to a CpJwt-authenticated operator, not an anonymous land-grab**. BUT the bootstrap trust for the
  *very first* device is then **CpJwt-alone** (there is no prior device to co-sign). So whoever can present a valid CpJwt
  during the empty-store window sets the anchor. This is acceptable **only** under the CYP-512 single-trust-domain
  premise (the operator runs their own hub; hub + operator + CP are one trust domain — MVP-explicit). It is **not**
  acceptable for BYOA/hardened, where a compromised CP/central-login that can mint CpJwt would seize the anchor.
- **Cost:** S — a few lines in the gate + a first-enroll tooth. Zero new surface, zero client change beyond persistence.

**★ The invariant that must survive every option (RR7 / RR2-B):** **re-enroll/recovery must NEVER be central-login-alone.**
First-Enroll (bootstrap, empty store) is the delicate TOFU window; **re-enroll (non-empty store) stays the Q6-gated
recovery seam** (`OperatorDeviceRecoveryFlow`, hub-local OOB + optional offline backup-codes, CP never holds) — already
built, deliberately unwired. A compromised central login must not be able to re-enroll and seize a live hub.

---

## 3. Client persist seam — the load path sketch (option a)

The seam is the `KeystoreOperatorDeviceKeyStore(keyPair = …)` **constructor arg** (there is no `loadOrGenerateDeviceKey`
function; the tests inject via this arg). The minimal load path:

1. A durable device-key store behind the existing `DEVICE_SECURE` intent — the JVM actual backs it with the **OS keystore**
   (or, interim, an app-dir secret at 0600, gitignored, mirroring the `.cyppie` at-rest pattern; **never** the tracked tree).
2. `loadOrGenerate()` composition root: on launch, **load** the persisted Ed25519 key; if absent, **generate once, persist,
   then use** (first-run enroll). Replace both `generateDeviceKey()` call sites (`RemoteHubMode.jvm.kt:70` INERT & `:111`
   LIVE) with `keyPair = deviceKeyStore.loadOrGenerate()`.
3. First-run only: hand the raw-32B public (SPKI→raw conversion, §0 must-fix) to the chosen enroll path (§2).
4. Keep the seam **injectable** (the ctor arg) so tests drive a fixed key — the existing test seam is unchanged.

Custody note: the interim app-dir secret is app-scope-exfiltratable; the OS-keystore/`DEVICE_SECURE` binding is the
hardening. (WebAuthn (b) sidesteps this entirely — the private key is non-exportable in the authenticator.)

---

## 4. Scope / effort + security tradeoffs + recommendation

| Option | Client | Hub | Effort | Security |
|---|---|---|---|---|
| **(a) persist + (2-iii) TOFU@PoP** | persist Ed25519 (`loadOrGenerate`) + SPKI→raw | `enrollFirstDevice` in the gate (empty-store TOFU under CpJwt) | **S** | MVP-acceptable under CYP-512 single-trust-domain; bootstrap = CpJwt-alone |
| **(a) persist + (2-i) boot-provision** | persist Ed25519 + SPKI→raw + convey pubkey OOB | env → `enrollFirstDevice` at boot | **S–M** | strongest of the (a) set — OOB human provision, no online enroll surface |
| **(a) persist + (2-ii) gated route** | persist Ed25519 + SPKI→raw + call enroll | new operator-token-gated route + contract op | **M** | online surface; enroll-trust folded into the operator bearer |
| **(b) WebAuthn/Passkey** | real FIDO2 client signer (libfido2/FFM per OS) + credential enroll | enroll credentialId+pubkey (route or provision) | **L** | strongest (non-exportable, UV-gated, origin-bound); the ratified end-state |

**Recommendation:**

1. **Dogfood-CONNECTED unblock now: (a) software-Ed25519-persist + enroll.** The only missing pieces are client
   persistence and writing the anchor — Raw sign + verify + durable store are already merged. **S.**
2. **Enroll wiring — recommend (2-iii) TOFU-at-first-PoP under the CpJwt for the dogfood** (seamless, zero new surface,
   no client-side enroll UX), **explicitly accepting the CYP-512 single-trust-domain envelope** for the bootstrap window.
   If the Auftraggeber prefers a hard OOB boundary over TOFU, fall back to **(2-i) boot-provision** (S–M) — same client
   work, a stricter enroll. *(This enroll-trust choice is a security-envelope decision for ratification — surfaced, not
   assumed.)*
3. **Ratified end-state stays (b) WebAuthn/Passkey (RR2-B).** It is **additive**: the server already verifies Fido2, so
   the future work is the per-platform FIDO2 client signer + credential enroll — no server rework. Sequence (a)→(b), not
   either/or.
4. **Recovery/re-enroll stays the Q6-gated `OperatorDeviceRecoveryFlow` seam** (never central-login-alone). Out of scope
   for the CONNECTED unblock; must not be short-cut by whatever first-enroll path is chosen.
5. **Must-fix in the fix regardless of option: the SPKI↔raw-32B encoding reconciliation**, single-sourced, with a
   round-trip tooth (enroll-then-verify with a real persisted key).

**One-line ask for ratification:** approve **(a)+persist** as the CONNECTED unblock, and choose the first-enroll trust —
**(2-iii) TOFU-under-CpJwt** (seamless, single-trust-domain) **vs (2-i) boot-provision** (OOB, stricter) — with (b)
WebAuthn as the ratified follow-on and Q6 recovery untouched.
