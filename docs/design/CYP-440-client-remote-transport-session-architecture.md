# CYP-440 — KMP-Client Remote Transport & Session (Design Pass, design-only)

> Status: **Developer design draft for ratification** · Story **CYP-440** under Epic **CYP-427** (Phase 2 — Remote-Modus) · Strand: **Desktop-Native client** (RR6-ii, Team-1 first; Team-2 web follows)
> Builds on: `14-threat-model-auth-krypto.md` (Phase-1 anchors R1–R7, F2/F4/F5/F6) · `16-threat-model-remote-modus.md` (§7 = the ratified **RR2-B + RR6-ii** backend seam template) · `CYP-395-client-connection-session-architecture.md` (the mode-blind `HubTransport` seam) · `remote-operator-ux-spec.md` (CYP-429, Q1–Q5 + honesty rails H1–H8)
> Role: Feature/Client Developer (client strand). **DESIGN ONLY — no code before ratification.**
>
> **[RATIFY]** = Auftraggeber/PO must accept before build. **[NO-GO]** = hard build-stop until the named state holds. Client design points are numbered **CR1…CRn**; they reference the ratified **RR1–RR8** (doc 16) and Phase-1 **R1–R7 / F2–F6** (doc 14), never restate or weaken them.

---

## 0. Thesis — the client is the *counterpart that the CP cannot forge*

Doc 16 §7 finalized the **backend** seams for the ratified outcome **RR2-B** (operator-held device-key PoP *in addition* to the CP identity assertion) **+ RR6-ii** (Desktop-Native app first, crypto/pin **outside** the CP origin). This document designs the **client** side of exactly those seams.

The whole security value of RR2-B + RR6-ii lives on the client:

- The client **holds the operator device-key** the CP does not → it produces the PoP that makes CP identity-forgery insufficient (no operator-seizure, RR2-B).
- The client **holds the TOFU hub-key pin and runs the Noise crypto outside the CP origin** → the CP web-delivery is not in the plaintext path (RR6-ii).
- The CP is reduced to a **pure introducer**: OIDC login (identity assertion), hub registry (an *advisory* directory), relay rendezvous (opaque routing). It never sees plaintext and never confers authority.

So the client's job is not "connect over Noise" — the transport is the easy, low-risk part. The client's job is to **produce, honestly and fail-closed, precisely the three things the hub's `OperatorAssertionVerifier` checks with an AND**: (a) a fresh CP identity assertion, (b) bound to the hub it is actually talking to (`aud==hubId`), (c) a device-key PoP **channel-bound to the same Noise handshake-hash `h`** the client just completed. If the client is sloppy about *which* `h`, *which* hub, or *when* it will sign, it re-opens the seizure/replay vectors the backend AND-gate was built to close. Every seam below is specified to keep that AND honest from the client side.

**The client mirrors the backend AND — it must never present a PoP it cannot stand behind:** sign only over the live session's own `h`, a fresh nonce, the pinned `hubId`, and the literal purpose `"operator-auth"`; refuse to sign (fail-closed) if the handshake did not complete or the hub key is unpinned/changed.

---

## 1. TL;DR — the client's job, invariants, and open ratification points

**Composition (client vantage), one line:** *OIDC-login → fetch registry → rendezvous via relay → Noise_NK handshake against the **pinned** hub static (yields `h`) → in the encrypted channel present `{CpJwt, PoP over H(h‖hubId‖nonce‖"operator-auth")}` → on hub-grant, run the whole existing app stack over the tunnel as the **real** `RemoteHubTransport`.*

**Client invariants (all fail-closed, all AND-composed):**

| # | Invariant | Anchor |
|---|-----------|--------|
| CI-1 | Handshake against the **pinned** hub-X25519 static — never the CP-registry key once a pin exists; the registry key is used **only** for first-use TOFU. | RR6-ii / F4 |
| CI-2 | The PoP binds the **live session's own `h`** + fresh nonce + pinned `hubId` + purpose `"operator-auth"`. No `h` (handshake incomplete) ⇒ **refuse to sign**. | RR3 §7-c |
| CI-3 | One pinned Noise suite, **no negotiation**; suite/version in the **Prologue**; ephemerals never reused. | RR1 |
| CI-4 | Remote is **always Noise** — no plaintext path, no downgrade, mode is explicit & authenticated (no "remote served as local"). | RR8 |
| CI-5 | Key-change (registry ≠ pin, or re-pin request) = **hard block + OOB re-pin**, never silent; re-pin needs an existing device-key signature or a local/OOB step, **never CP-alone**. | RR6/RR7 |
| CI-6 | Exactly **one active hub**; a hub switch **fully tears down** the current Noise session + clears in-flight before the new session is `ESTABLISHED`. | CYP-429 Q5 |
| CI-7 | In-flight work at a relay-drop is surfaced **uncertain**, never silently assumed done; only the Composer resumes optimistically (message-id idempotent), everything else re-fetches. | CYP-429 H4/H5 |

**Open ratification points (flagged to PO — the headline is CR2):**

| # | Decision | Recommendation |
|---|----------|----------------|
| **CR1** | Noise library for the JVM actual (no Noise exists in-tree). | Wrap a **vetted** JVM Noise impl (candidate: `noise-java`) behind `expect ClientNoiseTransport`; **spike-confirm** it exposes the handshake hash and the exact suite. **Never hand-roll Noise.** |
| **CR2** | **PoP wire shape — raw keystore signature vs WebAuthn assertion.** Doc 16 §7 writes `sign_deviceKey(H(...))` (a *raw* signature); CYP-429 H8 says *native Passkey/WebAuthn*. These are **different wire shapes** and the verifier must accept a discriminated union. | Desktop-JVM (Team-1) = **native-keystore raw key** (UV-gated where the OS allows) → raw PoP exactly as §7. Web (Team-2) = **WebAuthn assertion** (challenge carries the binding). Make `DevicePoP` a **discriminated union**; reconcile with Backend via PO. **[RATIFY]** |
| **CR3** | `RemoteHubTransport` plug-point (CYP-395 open Q5): in-process Ktor engine vs loopback terminator. | **In-process Noise-framing Ktor engine** (plaintext never leaves the process) as the confidentiality-preferred target; loopback terminator only as a documented fallback with a **loopback-only-bind** invariant. **[RATIFY]** |
| **CR4** | Operator device-key custody backing on Desktop. | Native OS keystore with hardware/UV where available (macOS Secure Enclave + Touch ID, Windows Hello/CNG, Linux libsecret+polkit); **fail-closed**, never silent-plaintext (F5). Extends CYP-413 `SecureSessionStore` (`Persistence.DEVICE_SECURE`). |

---

## 2. Composition flow (client vantage, annotated)

```
Desktop-App (operator logged into CP: holds CpJwt for their identity + holds the operator device-key natively)
  0. ControlPlaneClient.login()        → CpJwt (OIDC via Kratos, Doc 14 Strang 1); registry fetch = advisory directory
  1. ControlPlaneClient.rendezvous(hubId) → opaque rotatable rendezvousId (CP checks ownership; RR4)   [state: relayDialing]
  2. ClientNoiseTransport.handshake(pinnedStatic)  Noise_NK initiator over the relay channel            [state: e2eHandshake]
        - uses the PINNED hub-X25519 static (CI-1); wrong/absent key ⇒ handshake_failed (misroute/MITM caught, RR4)
        - ONE pinned suite, Prologue-bound (CI-3); FS via ee; yields the handshake hash  h
  3. HubKeyPin.check(hubId, registryStatic, pinnedStatic)                                                [state: trustCheck]
        - first use: TOFU-adopt + OOB fingerprint prompt; thereafter: registry≠pin ⇒ trust_changed HARD BLOCK (CI-5)
  4. ClientOperatorAuth.prove(h, hubId)   builds  PoP = deviceKey.sign( H(h ‖ hubId ‖ nonce ‖ "operator-auth") )  (CI-2, H8)
        - sends inside the encrypted channel:  { CpJwt , PoP }   → hub.OperatorAssertionVerifier does a∧b∧c (fail-closed)
  5. on OperatorGrant: RemoteHubTransport goes live; existing repos/live-sources run UNCHANGED over the tunnel  [state: connected/LIVE]
        - HubSession parents the project VMs (ProjectVmStoreManager); ConnectionStatus aggregates to HubSessionState
```

The client never asks the CP "who is my operator?" and never trusts the registry key over the pin. Authority is the hub's local decision; the client only *proves possession* to a hub it has *pinned*.

---

## 3. Seams

Each seam: **purpose · interface (expect/actual where platform-bound) · fail-closed invariants · reuse · backend counterpart (via PO) · open Qs.** All interfaces are sketches for ratification, not final signatures.

### 3.1 `ClientNoiseTransport` — Noise_NK initiator, exposes `h` (RR1) · CR1

**Purpose.** Terminate the Noise_NK tunnel from the initiator (client) side against the pinned hub static; expose the handshake hash `h` to the app layer (the **mandatory** seam — without `h`, §3.4 cannot channel-bind the PoP).

```kotlin
// commonMain — expect (Desktop-JVM/native actual first)
expect class ClientNoiseTransport {
    /** NK handshake over [relayChannel] against the PINNED responder static. Fail-closed: a wrong/absent
     *  hub key fails decryption → HandshakeFailed (misroute/MITM caught, RR4). Ephemerals never reused. */
    suspend fun handshake(
        pinnedHubStatic: X25519PublicKey,   // CI-1 — the PIN, not the registry key
        relayChannel: DuplexByteChannel,     // the authenticated relay session (§3.3)
        prologue: ByteArray,                 // suite/version bound here (CI-3), NOT negotiated in-band
    ): NoiseSession
}
interface NoiseSession {
    val handshakeHash: ByteArray             // `h` — per-session unique, relay never sees it (CI-2)
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
    suspend fun close()
}
```

- **Invariants:** exactly one suite (`Noise_NK_25519_ChaChaPoly_BLAKE2s` per RR1), pinned by the protocol name; all version/param bytes in `prologue` so a MITM cannot down-negotiate (CI-3/CI-4); FS via `ee`, ephemerals fresh each session; `handshakeHash` exposed only after the handshake completes (else the session does not exist → §3.4 fails closed, CI-2).
- **Reuse:** consumes the Phase-1 X25519 static (R2=i) in the exact Noise-ready raw format (F2/S-C).
- **Backend counterpart:** `NoiseTransport` (responder), doc 16 §7-1 — same suite, same Prologue, same `h`.
- **CR1 open:** pick + spike a vetted JVM Noise lib (candidate `noise-java`); confirm it surfaces the handshake hash and the suite. **Never hand-roll.** Native (macOS/Linux) and later JS/wasm actuals follow; web will bridge to WebCrypto/a wasm Noise build (Team-2).

### 3.2 `HubKeyPin` — TOFU pin store, hard-block on change (RR6-ii / F4) · CI-1/CI-5

**Purpose.** Own the `hubId → pinned X25519 static` binding **outside the CP origin** (native store). This is what makes "CP cannot read plaintext" true: the pin (and the crypto in §3.1) are not CP-served.

```kotlin
expect class HubKeyPin {
    fun pinned(hubId: HubId): X25519PublicKey?                 // fail-closed: no pin ⇒ null
    /** First-use TOFU adopt (after an OOB fingerprint confirm). NEVER silently overwrites an existing pin. */
    fun adopt(hubId: HubId, static: X25519PublicKey, oobConfirmed: Boolean)
    /** Re-pin (rotation) — requires a signature by an EXISTING device-key OR a local/OOB step; NEVER CP-alone (RR7). */
    fun rePin(hubId: HubId, newStatic: X25519PublicKey, authorization: RePinAuthorization)
    fun fingerprint(hubId: HubId): String                     // for OOB compare (hex + word-list, CYP-429 remote.trust.*)
}
sealed interface TrustCheck { object Pinned; object FirstUse; data class Changed(val expected: X25519PublicKey) }
```

- **Invariants:** once pinned, the client **handshakes against the pin, not the registry** (CI-1); a registry static that differs from the pin ⇒ `TrustCheck.Changed` ⇒ **hard block** + `trust_changed` alarm (CYP-429 `remote.trust.changedAlarm`), never a silent re-pin (CI-5); first-use adopt is gated on an OOB fingerprint compare (`remote.trust.{fingerprint,wordlist,hex,qr,pinPrompt}`); re-pin is **never** CP-authorized (RR7).
- **Reuse:** CYP-413 `SecureSessionStore`/`SecretStore` custody pattern (`Persistence.DEVICE_SECURE`, native keystore actuals) — the pin is a *public* key so confidentiality is not the concern; **integrity/authenticity of the pin store** is (a swapped pin = MITM), so it lives in the tamper-resistant native store, not CP-reachable web storage.
- **Backend counterpart:** `HubKeyPin` (doc 16 §7-5) — same fingerprint encoding for OOB compare.

### 3.3 `ControlPlaneClient` — OIDC / registry / rendezvous (pure introducer) · extends CYP-395

**Purpose.** The CP as **introducer only**: authenticate the operator (OIDC), list hubs (advisory directory), broker an opaque relay rendezvous. Extends the CYP-395 `ControlPlaneClient` seam.

```kotlin
interface ControlPlaneClient {                 // CYP-395 seam, Phase-2 methods added
    suspend fun login(): CpJwt                  // OIDC via Kratos (Doc 14 Strang 1); short-lived, aud-scoped
    suspend fun hubs(): List<HubDescriptor>     // ADVISORY directory — name/online/lastSeen; NOT authority, NOT the trusted key
    suspend fun hubStatic(hubId: HubId): X25519PublicKey   // registry key — used ONLY for first-use TOFU (§3.2), never trusted over a pin
    suspend fun rendezvous(hubId: HubId): RelayRendezvous   // opaque rotatable id (RR4); CP checks ownership; session-open needs the CpJwt
}
data class RelayRendezvous(val rendezvousId: OpaqueId, val relayUrl: String)  // OpaqueId ≠ hubId/ownerId (metadata minimization)
```

- **Invariants:** the CpJwt is **short-lived + `aud`-scoped** (F6/RR3); the registry is treated as **untrusted for keys** (CI-1 — a CP key-swap is caught by §3.2 pin-compare and by the handshake against the pin); rendezvous id is **opaque + rotatable** (RR4), never `hubId`/`ownerId`; the relay is a public endpoint → session-open requires the authenticated CpJwt (no anonymous flood).
- **Backend counterpart:** the hub's `RelayConnector` (outbound-only, Ed25519-authenticated, RR5) + the CP registry/rendezvous. Consistency via PO.

### 3.4 `ClientOperatorAuth` + `OperatorDeviceKeyStore` — device-key custody + PoP (RR2-B / H8) · CR2/CR4

**Purpose.** Hold the operator device-key **natively** (CP cannot) and produce the channel-bound PoP that defeats CP identity-forgery. This is the heart of the client's anti-seizure role.

```kotlin
expect class OperatorDeviceKeyStore {
    fun hasEnrolledKey(): Boolean
    /** Provisioning-time enroll (Device-Code/R3 era). The private key never leaves the native store. */
    suspend fun enroll(): DevicePublicKey
    /** Sign the channel-binding digest with the native, UV-gated device-key. Fail-closed: no key ⇒ throws. */
    suspend fun sign(digest: ByteArray): DevicePoP
    fun publicKey(): DevicePublicKey?
}

object ClientOperatorAuth {
    /** CI-2: bind to THIS session's own h. purpose is the literal "operator-auth". nonce fresh, never reused. */
    suspend fun prove(store: OperatorDeviceKeyStore, h: ByteArray, hubId: HubId): OperatorProof {
        val nonce = freshNonce()
        val digest = hash(h + hubId.bytes + nonce + PURPOSE)   // H(h ‖ hubId ‖ nonce ‖ "operator-auth")
        return OperatorProof(cpJwt = /* from §3.3 */, pop = store.sign(digest), nonce = nonce)
    }
    const val PURPOSE = "operator-auth"
}

// CR2 — the PoP is a DISCRIMINATED UNION (raw keystore signature vs WebAuthn assertion); the backend
// OperatorAssertionVerifier must accept both, each channel-bound to h via the digest/challenge.
sealed interface DevicePoP {
    data class Raw(val signature: ByteArray) : DevicePoP                       // Desktop-JVM: sign(digest) exactly per §7
    data class WebAuthn(val authenticatorData: ByteArray,                      // Web (Team-2): challenge == digest
                        val clientDataJson: ByteArray, val signature: ByteArray) : DevicePoP
}
```

- **Invariants (CI-2, the anti-replay/anti-seizure core):** the digest binds the **live `h`** (never a stale or other session's), a **fresh nonce** each attempt, the **pinned `hubId`**, and the literal purpose; the client **refuses to sign** if there is no completed handshake (no `h`) or no pinned hub — fail-closed. The private key never leaves the native store; a compromised CP forges the CpJwt (a) but **never** the PoP (c).
- **CR2 (the headline flag):** doc 16 §7 writes `sign_deviceKey(H(...))` (raw), CYP-429 H8 says Passkey/WebAuthn. A raw keystore signature and a WebAuthn assertion are **not the same wire shape** — WebAuthn signs `authenticatorData ‖ H(clientDataJSON)` with the binding carried in `clientDataJSON.challenge = digest`. On **Desktop-JVM (Team-1)**, `navigator.credentials` is absent, so a **native-keystore raw key** is the practical, §7-matching path; **WebAuthn is the web-build (Team-2) shape**. The `OperatorAssertionVerifier` is surface-agnostic in *intent* but must branch on the PoP variant in *shape*. **This must be reconciled with Backend via the PO before either side builds the verifier.** [RATIFY]
- **CR4:** Desktop custody = native OS keystore with UV where available (Secure Enclave/Touch ID, Windows Hello, libsecret+polkit); extends CYP-413 `SecureSessionStore` `DEVICE_SECURE`; fail-closed, never silent-plaintext (F5).
- **Backend counterpart:** `OperatorAssertionVerifier.verify(cpJwt, devicePoP, h, hubId): OperatorGrant?` and `OperatorPinStore` (doc 16 §7-2/§7-3). The client-side enroll (`OperatorDeviceKeyStore.enroll`) is the counterpart of `OperatorPinStore.pin(identity, deviceKey)` and of RR7 device-add (each new device authorized by an existing key or an OOB step — **never** "any CP-asserted device").

### 3.5 `RemoteHubTransport` — the real `HubTransport` over the tunnel (CYP-395 S-1) · CR3

**Purpose.** Make the Phase-1 mode-blind `HubTransport` seam *real* for REMOTE, so **every existing repo and live-source (Comm, Events, Terminal, ACL, lifecycle…) runs unchanged** over the Noise tunnel. Replaces today's fail-loud stub.

The Phase-1 seam (on develop, CYP-411): `interface HubTransport { httpBaseUrl; wsBaseUrl; httpClient; sessionToken(); close() }`. REMOTE fills it so `httpBaseUrl`/`wsBaseUrl`/`httpClient` transparently traverse the tunnel; `sessionToken()` evolves to the CP-issued hub-scoped ticket JWT (CYP-395 S-K).

**CR3 — the plug-point (CYP-395 open Q5), two options:**
- **(a) In-process Noise-framing Ktor engine (recommended).** A custom Ktor `HttpClientEngine` that frames each request/WS via `NoiseSession.encrypt/decrypt` and relays it. Logical base URLs stay stable; **plaintext never leaves the process** — the strongest RR6-ii posture. More work (a Ktor engine + WS-over-Noise), but it is the honest realization of "crypto outside the CP origin *and* off the wire in the clear."
- **(b) Loopback terminator (fallback).** A local Noise-terminating proxy at `127.0.0.1:<ephemeral>`; the standard Ktor client talks plain HTTP/WS to it, the proxy Noise-wraps to the relay. Maximal reuse (standard client), but plaintext transits a loopback socket. **[NO-GO] on binding anything but `127.0.0.1`** (a client-side echo of RR5/F8 — never `0.0.0.0`); acceptable only on true single-user loopback, and it has a strictly larger plaintext surface than (a). Documented so the seam does not foreclose it, **not** the default.

The seam **must not foreclose either** (CYP-395 kept `httpClient` a transport member for exactly this). Recommendation: **(a)**.

- **Invariants:** remote is **always Noise** (CI-4) — the transport has no plaintext fallback and no suite negotiation; the mode is explicit (the app knows it is REMOTE, never spoofed as LOCAL); a relay-drop surfaces as `ConnectionStatus.DISCONNECTED` and the transport **does not auto-replay non-idempotent mutations** (CI-7).

### 3.6 `RemoteHubSession` — connection/session state machine (RR8 · Q3/Q5) · CI-6/CI-7

**Purpose.** Own the remote session lifecycle over `RemoteHubTransport`, extending the CYP-395 `HubSession`/`HubSessionState` with the remote states from CYP-429 §7.

```kotlin
// extends CYP-395 HubSessionState with the remote sub-states of the ConnectingView idiom
enum class RemoteConnState { RELAY_DIALING, E2E_HANDSHAKE, TRUST_CHECK, AUTHENTICATING, CONNECTED,
                             RECONNECTING, LOST }
sealed interface RemoteFailure {                    // CYP-429 typed failures (remote.error.<cause>)
    object RelayUnreachable; object HubOffline; object HandshakeFailed
    data class TrustChanged(val expected: String)   // key ≠ pin — WARN/BLOCK, not a mere error (H1/H2)
    object AuthRejected                              // a∧b∧c failed at the hub (fail-closed)
}
```

- **Reconnect (H4):** transient relay-drop → `RECONNECTING` via `net/Reconnect.kt` `Backoff` + **gapless cursor-resume** (`lastSeq`); **ONE** workspace-scoped relay-drop surface ("Remote-Verbindung zu Hub X unterbrochen — verbinde neu…"), **not** N per-agent chips (`remote.relayDrop`).
- **Q5 hub-switch (CI-6):** "Auf Hub Y wechseln" **fully tears down** the current `NoiseSession` + `RemoteHubTransport` + the parented project VMs (`ProjectVmStoreManager`) and **clears in-flight** *before* Y is `CONNECTED`; **exactly one active hub**; active flips only after Y establishes (reject → stay on X + honest error); copy "Trenne von X … verbinde mit Y" (`remote.switch.transition`). No silent two-hub multiplex in MVP.
- **Q3 latency (H7):** a subtle, damped, **neutral** latency hint in the context banner (`remote.context.latency`) — a hint not a guarantee, no per-second flicker, escalates to a neutral "Verbindung langsam/instabil" **only** on real degradation; high latency ≠ disconnected, **never** red/amber.
- **In-flight honesty (CI-7 / H4-H5):** on drop, only the Composer resumes optimistically (temp-id, "sendet…", confirm-or-drop, **message-id idempotent**); ACL = optimistic-visual + server-echo + watchdog (revert on missing echo); everything else (project/hub switch, agent-CRUD, connector, settings, lifecycle, hand-off) is **confirmed/re-fetch**. The session marks in-flight state **uncertain**, never silently done.

---

## 4. The client-side AND-gate (why each seam is fail-closed)

The hub verifies `a ∧ b ∧ c` (doc 16 §7-2). The client must feed that AND without ever creating an OR:

- **Never sign without `h`** (CI-2): if §3.1 did not complete, there is no `NoiseSession.handshakeHash` → §3.4 throws → no PoP is sent. A PoP without a real completed handshake is the replay bug; the client structurally cannot produce one.
- **Never sign for the wrong hub** (CI-1): the digest binds the **pinned** `hubId`; §3.2 blocks a changed key before §3.4 runs. A misrouted rendezvous fails the handshake (§3.1) *before* any PoP.
- **Never reuse a nonce / never sign another purpose** (CI-2): fresh nonce per attempt; the literal `"operator-auth"` purpose only.
- **Never downgrade** (CI-4): no plaintext path exists in `RemoteHubTransport`; the mode is explicit.
- **Never silent-adopt a key change** (CI-5): registry ≠ pin is a hard block, not a quiet re-pin.

If any seam cannot satisfy its invariant, it **fails closed** (no session, no grant) — the honest state is "not connected", surfaced via the typed failures in §3.6, never a partial/optimistic "connected".

---

## 5. Honesty rails (CYP-429 H1–H8) → client responsibilities

| Rail | Client responsibility |
|---|---|
| **H1/H2/H3** | Two separate trust truths: an "E2E via Relay" (confidentiality) indicator (`remote.trust.e2eIndicator`) must **never** read as "hub trusted" — hub authenticity is the **TOFU pin** (§3.2). Keep the two surfaces distinct. |
| **H4** | Remote is fragile: **one** global relay-drop surface (§3.6), in-flight marked **uncertain** (CI-7). |
| **H5** | Only the Composer is truly optimistic (message-id idempotent); ACL optimistic+echo+watchdog; everything else confirmed/re-fetch (CI-7). |
| **H7** | Latency is advisory, damped, neutral (Q3, §3.6) — never an alarm. |
| **H8** | The native Passkey/WebAuthn (or keystore) PoP step is **mandatory + fail-closed** (§3.4, CR2) — never skippable, never "assumed". |

State→testTag map (client owns the states; UIUX/Tester own the render): `remote.connect.{relayDialing,e2eHandshake,trustCheck,connected}`, `remote.error.<cause>`, `remote.relayDrop`, `remote.context.{banner,hub,latency,degraded,reconnecting}`, `remote.trust.{fingerprint,wordlist,hex,qr,pinPrompt,changedAlarm,e2eIndicator}`, `remote.authStep.{popPrompt,enroll,error}`, `remote.switch.transition`, `hubConnect.mode.remote`.

---

## 6. Seam-consistency table (client ↔ backend — reconcile via PO)

| Client (CYP-440) | Backend (doc 16 §7) | Shared contract that must match |
|---|---|---|
| `ClientNoiseTransport` (initiator) | `NoiseTransport` (responder) | suite `Noise_NK_25519_ChaChaPoly_BLAKE2s`, Prologue bytes, `h` derivation |
| `ClientOperatorAuth` PoP | `OperatorAssertionVerifier` a∧b∧c | digest `H(h‖hubId‖nonce‖"operator-auth")`, **`DevicePoP` variant shape (CR2)**, nonce/exp freshness window |
| `OperatorDeviceKeyStore` enroll | `OperatorPinStore.pin/rePin` | device-key public format; RR7 device-add authorization (existing-key/OOB, never CP) |
| `HubKeyPin` | `HubKeyPin` (§7-5) | X25519 raw format (F2), fingerprint encoding for OOB compare |
| `ControlPlaneClient.rendezvous` | `RelayConnector` (§7-4) | opaque rendezvous-id shape, CpJwt session-open, Ed25519 outbound bind (RR5) |
| `RemoteHubTransport.sessionToken()` | hub ticket verify (F6) | CP-issued hub-scoped ticket JWT (`aud==hubId`, alg-pin, kid) — CYP-395 S-K |

---

## 7. Reuse map (build on, do not rebuild)

- **`net/hub/{HubTransport,LocalHubTransport,TransportModeResolver,HubEndpoint}`** (CYP-411) — REMOTE fills the existing seam; `RemoteHubTransport` replaces the fail-loud stub.
- **CYP-395** `HubSession`/`HubSessionState`/`ControlPlaneClient`/`HubPickerViewModel` design — CYP-440 is the Phase-2 realization of its REMOTE branch.
- **CYP-413** `SecureSessionStore` (expect/actual, `Persistence.DEVICE_SECURE`) — the native custody pattern for `HubKeyPin` + `OperatorDeviceKeyStore`.
- **`net/Reconnect.kt`** (`Backoff`, `.reconnecting()`, cursor-resume), **`net/WsClose.kt`** (1008), **`ConnectionStatus`** — reused for §3.6 reconnect/state aggregation.
- **`ProjectVmStoreManager`** — the parented-VM teardown for the Q5 hub switch (CI-6).
- **`SecretStore`/`SecretCipher` pattern** (server CYP-434) — the design analog for the client native store; the client supplies its own `actual`.

---

## 8. Trust boundary — what the client defends, what it cannot

- **Defends against a compromised CP:** no seizure (RR2-B — CP lacks the device-key, §3.4) and no plaintext (RR6-ii — pin+crypto outside the CP origin, §3.1/§3.2, best realized by CR3-a). This is the whole point of the Desktop-Native strand.
- **Defends against a malicious relay / network MITM:** Noise_NK + TOFU pin (a wrong key fails the handshake; a swapped registry key is caught by the pin-compare).
- **Cannot defend against:** a compromised **client device** (malware with keystore access can use the device-key while the OS session is unlocked — mitigated by UV-gating, not eliminated); traffic-analysis metadata at the relay (RR4 scopes ZK to *payload*, not timing/sizes); a lost device (mitigated by `revokeDevice` + last-key-gone lockout, RR7). These are stated, not silently implied covered.

---

## 9. Open ratification points & flags (for PO / Auftraggeber)

1. **CR2 [RATIFY] — the headline.** `DevicePoP` wire shape: raw keystore signature (Desktop-JVM, §7-literal) vs WebAuthn assertion (Web, H8-literal). Make it a **discriminated union**; the `OperatorAssertionVerifier` must branch on the variant. Reconcile with Backend **before** either side codes the verifier.
2. **CR3 [RATIFY].** `RemoteHubTransport` plug-point: **in-process Ktor engine (recommended)** vs loopback terminator (fallback, loopback-only-bind [NO-GO] otherwise). Resolves CYP-395 open Q5.
3. **CR1.** Noise library for the JVM actual: adopt + **spike** a vetted impl (candidate `noise-java`); confirm handshake-hash exposure + the exact suite. Never hand-roll.
4. **CR4.** Desktop device-key custody backing (Secure Enclave/Hello/libsecret); UV-gating policy; fail-closed, never silent-plaintext (F5).
5. **Enroll/provisioning timing.** Doc 16 §7-3 pins `(identity, deviceKey)` at Device-Code/R3 provisioning time. The **client enroll flow** (first-run operator-device enrollment + OOB device-add per RR7) needs its own UX slice — flag whether it rides CYP-429 (`remote.authStep.enroll` exists) or a new ticket.
6. **Registration/Phase-1 prerequisites.** This design assumes the Phase-1 anchors are *built*, not just designed: the hub X25519 static (F2, server slice S-C) and the `CpJwtVerifier`/CP-signed token (F1-A/F6) are **named-not-yet-code** per the inventory. CYP-440 client build must not start before those land (dependency, not a client task).

---

## 10. Build-increment order (design-only; each slice adversarially gated)

The client build (post-ratification) should land in dependency order, each slice gated against the §4 invariants (per doc 16 §7: *PoP is really verified, binding really binds `h`, checks are AND never OR*):

1. **CR-S1 `ClientNoiseTransport`** (JVM actual, CR1) — handshake + `h`, against a test responder; gate: wrong-key ⇒ fail, `h` matches both ends.
2. **CR-S2 `HubKeyPin`** (TOFU + hard-block) — gate: registry≠pin ⇒ block, no silent re-pin, re-pin needs existing-key/OOB.
3. **CR-S3 `ControlPlaneClient`** remote methods (rendezvous/registry) — gate: opaque id, CpJwt-gated session-open.
4. **CR-S4 `OperatorDeviceKeyStore` + `ClientOperatorAuth`** (CR2/CR4) — gate: PoP binds the live `h` + fresh nonce; no-`h` ⇒ refuse; variant shape matches Backend.
5. **CR-S5 `RemoteHubTransport`** (CR3-a) — gate: existing repos/live-sources run unchanged over the tunnel; no plaintext path; loopback (if fallback) binds 127.0.0.1 only.
6. **CR-S6 `RemoteHubSession`** state machine — gate: Q5 teardown (exactly one hub, in-flight cleared), Q3 latency (damped/neutral), H4/H5 in-flight-uncertain, reconnect cursor-resume.

**Verdict:** the client transport is well-anchored by the ratified RR2-B + RR6-ii and the CYP-395 mode-blind seam — the hard part is *not* the Noise wire but keeping the **AND honest from the client side** (sign only over the live `h`, only for the pinned hub, always fail-closed) and getting **CR2 (PoP wire shape)** reconciled with Backend before build. **No code before ratification of CR2/CR3 and the Phase-1 prerequisites (§9-6).**
