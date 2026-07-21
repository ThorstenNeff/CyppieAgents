# CYP-747 / Model-2 — Compose Trust-UI Seam Analysis (design-pass input)

> Author: Frontend/Client dev · **Design-pass input, NOT a build.** Stand develop `5e2294e0`.
> Purpose: map the EXISTING Compose hub-connection/trust seam so UIUX can design where the new
> `owned-but-issuer-not-trusted` trust state (Backend builds it in S1) surfaces. The state itself is
> **S1-pending** — no client build until Backend's state + the UIUX design-pass land.
>
> Scope note: this analyses the client seam only. The exact `§5-C2` semantics are Backend's spec; here it is
> treated as: *the remote hub is admitted/owned at the Control Plane, but its CP-JWT/cert **issuer** is not
> trusted* — a **degraded-trust** connection, distinct from "untrusted key" and "untrusted operator".

---

## TL;DR

- The whole M1 remote connect flow is **one sealed state machine** (`HubConnectUiState`) driven by **one**
  `HubConnectViewModel.state: StateFlow<HubConnectUiState>`, dispatched by **one** `HubConnectFlow()` composable.
- **Connection status ≠ trust status — and both are firewalled from a THIRD axis (issuer).** The client models
  two deliberately-separated trust axes: **(a) hub-key trust** (`TrustResolution`, TOFU of the hub's Noise DH
  static key) and **(b) operator identity** (`OperatorAuthOutcome`/`OperatorAuthError`). A regression guard
  (`Cyp443TrustAxisSeparationGuardTest`) reddens if they reference each other's types — and its KDoc explicitly
  says it is *"NOT a substitute for the PO HALT on **issuer/revocation** decisions."* ⟹ **`owned-but-issuer-not-
  trusted` is a third concern** (CP-JWT/cert issuer), currently **un-modelled** on the client. Do not fold it
  into axis (a) `TofuHubTrust` or axis (b) `ClientOperatorAuth`.
- The app models a **LIST of hubs** but connects to **exactly ONE at a time** (CI-6). Trust status is
  **per-single-active-hub**, derived live in `RemoteSessionState`; there is **no per-hub-row trust affordance**
  today (rows show advisory registry presence only).

---

## Q1 — Where hub-connection status renders today

**State machine** — `net/hub/remote/RemoteSessionState.kt`:
- `enum class RemoteConnState { RELAY_DIALING, E2E_HANDSHAKE, TRUST_CHECK, AUTHENTICATING, CONNECTED, RECONNECTING, LOST }` (:13)
- `data class RemoteSessionState(hubId, conn: RemoteConnState, failure: RemoteFailure?, latency, inFlightUncertain)` (:73)

**ViewModel** — `connect/HubConnectViewModel.kt`: `state: StateFlow<HubConnectUiState>` (:69); `connectRemote()` (:204) →
`connectRemoteInternal()` (:215); **`surfaceRemote()` (:320)** is the priority reducer mapping session-truth + OOB +
enroll + passphrase into the UI state — **the exact function a new degraded-trust surface priority edits.**

**Composables** — `connect/HubConnectSelection.kt`: `RemoteConnectingView()` (:258) renders `when (remote.conn)` per
phase; **`RemoteFailureView(failure, viewModel)` (:329)** renders the terminal `when (failure)` — **where a new
trust-failure branch renders.** Dispatcher `connect/HubConnectFlow.kt` `HubConnectFlow()` (:75); mounted (flag-INERT)
by `connect/RemoteHubConnectGate.kt` (:28). Post-connect chrome: `workspace/RemoteOperatingChrome.kt` (:53) banners
over `RemoteSessionState` (CONNECTED → context banner; RECONNECTING → relay-drop banner).

**Connection testTags** — `connect/RemoteConnectTags.kt`: `RELAY_DIALING`, `E2E_HANDSHAKE`, `TRUST_CHECK`,
`AUTHENTICATING`, `CONNECTED`, `RELAY_DROP`, `RETRY`, **`fun error(cause)`** (frozen cause taxonomy). Indicator idiom:
LIVE = `● ` + label (never green prematurely; in-progress = neutral spinner).

## Q2 — Where trust/enrollment status renders today; existing taxonomy

**(a) hub-key trust** — `net/hub/remote/RemoteSessionState.kt`:
```
sealed interface TrustResolution { Pinned(hubStatic) · FirstUse(hubStatic, fingerprint) · Changed(expectedFingerprint) }
```
Produced by `net/hub/trust/TofuHubTrust.kt` `resolve()` — TOFU pinning of the hub's **Noise DH static key**, NOT the
CP-JWT/cert issuer (the CP-registry key is "a pure introducer, NEVER trusted on its own").

**Failure taxonomy** — `sealed interface RemoteFailure`: `RelayUnreachable · HubOffline · HandshakeFailed ·
TrustChanged(expectedFingerprint) · TrustRejected · AuthRejected · EnrollCodesUnavailable · DeviceNotEnrolled ·
OperatorUvFailed · EnrollTimedOut` (rendered branch-by-branch in `RemoteFailureView`).

**(b) operator identity** — `OperatorAuthOutcome` (Granted/Rejected/DeviceNotEnrolled/UvFailed/…) +
`net/hub/operator/ui/OperatorAuthTaxonomy.kt` `OperatorAuthError` (WrongPin/LockedOut/BiometricFailed/NeedsEnroll/…,
with `.isTerminal` + `.tag()`).

**Trust composables** — `connect/OobFingerprintConfirmScreen.kt` (:46, first-use OOB fingerprint confirm; wordlist+hex+QR)
+ `TrustAbortedView()` (:111); TRUST_CHECK branch in `RemoteConnectingView` (:275–295); `TrustChanged`/`TrustRejected`
alarm rows + `DeviceNotEnrolled` enroll-CTA in `RemoteFailureView` (:331–385); `net/hub/operator/ui/OperatorAuthDialog.kt`;
enroll/passphrase in `connect/RemoteOperatorAuthSteps.kt`.

**The issuer dimension exists as a seam, not a UI state** — `net/hub/operator/ClientOperatorAuth.kt` `fun interface
CpJwtProvider` (:148): the CP-minted, hub-scoped ticket whose **issuer (`iss`)** is exactly the trust dimension the new
state concerns. Impl `net/hub/operator/HttpCpJwtProvider.kt`. Today the client verifies/uses the CP-JWT but does **not
surface an "issuer-not-trusted" verdict** — there is no UI state for it.

**⟹ Where `owned-but-issuer-not-trusted` slots in (recommendation for the design pass):**
1. **A new terminal-or-degraded surface**, NOT a fold into axis (a)/(b) — respect the `Cyp443…` firewall.
2. Most natural home: **a new `RemoteFailure` variant** (e.g. `IssuerNotTrusted(...)`) surfaced via `RemoteFailureView`
   + a `surfaceRemote()` priority branch, **if** the verdict is terminal/blocking; **or** a new `RemoteConnState`
   value / a non-terminal "degraded but connected" banner in `RemoteOperatingChrome` **if** UIUX wants
   owned-but-issuer-untrusted to still connect with a persistent warning (the "marked, not hidden" pattern already
   used for context-token staleness). **This connect-vs-block choice is the core UIUX/PO decision.**
3. Fail-closed default (safe-but-silent family, as CYP-789): an unknown/untrusted issuer must NOT render in the
   trusted look.

## Q3 — State hooks / testTags a new variant needs

- **StateFlows:** `HubConnectViewModel.state: StateFlow<HubConnectUiState>`; inner `RemoteSessionState` flow (combined
  in `connectRemoteInternal`); `RemoteOperatingChrome(sessionState: StateFlow<RemoteSessionState>?)`.
- **Status types to extend:** `RemoteFailure` (sealed) and/or `RemoteConnState` (enum) and/or `TrustResolution` (sealed,
  if it must gate before handshake). New variant ⟹ a new `surfaceRemote()` priority branch + a `RemoteFailureView`/
  chrome branch.
- **Tags:** a new **`RemoteConnectTags.error("issuerNotTrusted")`** value (the `error(cause)` frozen taxonomy is the
  established home) — plus, if a per-row badge is wanted, a new tag beside `HubConnectTags.hubPresence(hubId)` (see Q4).
  Tag convention: dotted `<area>.…​.<element>`, camelCase; `error.<cause>` carries the fail-closed cause.

## Q4 — One hub or many?

**A list, connected one-at-a-time.** `HubConnectUiState.HubList(hubs: List<HubDescriptor>)` (from
`ControlPlaneClient.hubs()`), rendered `HubListView → HubRow → PresenceRow` — each row shows **advisory registry
presence only** (`hub.online`), never a connection/trust verdict. **Exactly one active connection (CI-6):**
`connectRemoteInternal()` tears down any active session first (`HubConnectViewModel.kt:216`, KDoc :199). Switching =
`backToHubList()` → reload → `selectHub()` → `connectRemote()`. `HubDescriptor` carries `hubId/name/online/defaultPort/
lastSeen/dhPubKey` — **no trust/issuer field**; trust is derived per-active-connection, not per row. (Projects ≠ hubs —
`ProjectViewModel` is unrelated; do not conflate.)

**⟹ Design decision for UIUX:** trust status (incl. the new state) is naturally **per-single-active-hub** through the
`HubConnectViewModel.state` machine. **If** the degraded-trust verdict must also be visible **at hub-list time** (a
per-row "owned but issuer untrusted" badge on `HubRow`), that is **net-new**: it needs a new field on `HubDescriptor`
(⟹ a Backend/CP `ControlPlaneClient.hubs()` contract change) + a new per-row tag. No per-row trust affordance exists
today. Recommend confirming with UIUX whether the state is connect-time-only (cheaper, fits the existing machine) or
also list-time (needs the DTO + row change).

---

## Open questions for the UIUX design-pass (I did not pre-decide)
1. **Connect-and-warn vs. block?** Is `owned-but-issuer-not-trusted` a **terminal** failure (→ `RemoteFailure` +
   `RemoteFailureView`, like `TrustRejected`) or a **degraded-but-connected** state (→ persistent warning banner in
   `RemoteOperatingChrome`, "marked, not hidden")? This is the load-bearing UX call.
2. **Connect-time only, or also hub-list-time?** (Q4 — the latter needs a CP DTO + per-row affordance.)
3. **Recovery path?** Does the operator have an in-app action (trust the issuer / re-pin / abort), analogous to the
   `TrustChanged → re-pin` and `DeviceNotEnrolled → enroll` CTAs, or is it PO/out-of-band only (issuer decisions are
   the PO-HALT boundary per the axis-separation guard KDoc)?
