# CYP-395 — Client Connection & Session Architecture (Design Pass)

> **Epic:** CYP-395 — Verteilte Hub-Architektur, Phase 1 (Lokal-Modus-Hub). **Strand:** Frontend/Client
> connection & session architecture (one of the parallel design passes: Backend = architecture spine,
> Reviewer = crypto/auth/zero-knowledge threat model, UIUX = connection/hub-selection/mode UX).
> **Concept:** `13-cyppie-hub-architektur.md` (Auftraggeber, 2026-07-11).
> **Status:** DESIGN ONLY — **no code**. Design-Pass-first → Auftraggeber ratification → build. Seams to the
> other strands are called out and routed **through the coordinator**, never decided here.
> **Author:** Frontend/Client dev. **Base:** develop `a2f66ae8`.

---

## 0. What this pass owns (and does not)

**Owns (client-side, KMP `:app:shared`):** the *shape* of the connection/session layer — a transport-mode
seam (Local now, Remote later), a hub-session client, event-stream subscription re-parented onto that seam,
consumption of the Control-Plane hub list + the local JWT handshake, and a client keystore seam for session
material. Interfaces, state flow, `:core` consumption, and the open decisions.

**Does NOT own (routed to the other passes via the coordinator):**
- **Wire contract / `:core` DTOs** — Backend owns the shape of `GET /hubs`, the handshake payload, and any new
  server frames. This doc *names the DTOs the client needs* and marks each as a Backend seam; it does not fix
  field names or JSON.
- **Crypto & threat model** — Reviewer owns JWT signing/rotation, the Noise handshake, relay-MITM defence,
  and the zero-knowledge property. This doc only states *where* the client presents/holds material.
- **Visual UX** — UIUX owns the hub-picker screens, the mode chooser, and connection-status visuals. This doc
  defines the *state* those screens bind to, not their look.

**Guiding invariant (from the concept, §Verbindungsmodi):** *business logic once, two transports.* Every VM,
REST repository, and WS live-source must depend on a transport **seam**, never on whether the mode is Local or
Remote. Phase 1 ships the Local actual and a compile-only Remote seam.

---

## 1. Where we are today (the seams we evolve)

Verified against develop `a2f66ae8` (client module `:app:shared`, wire DTOs in `:core`, shared `CommJson`):

| Concern | Today | Evolution |
|---|---|---|
| Endpoint config | `ShellConfig` — a single `hubHttpBaseUrl` / `hubWsBaseUrl` string pair + `agentToken`/`operatorToken`; `defaultShellConfig()` expect/actual per platform | becomes the **output** of a resolved `HubConnection`, not a top-level input |
| HTTP/WS client | one `sharedWsHttpClient(sessionToken)` (`net/SharedHttpClient.kt`); `X-Session-Token` via `DefaultRequest`; WS ping 15 s | owned by the transport; Local reuses it verbatim |
| Every source/repo | uniform ctor `(client: HttpClient, baseUrl: String, token: String)` — ~9 WS live-sources + ~18 REST repos | **unchanged** — they consume `transport.httpBaseUrl/wsBaseUrl/httpClient`; that is the whole point of the seam |
| Reconnect | `Flow<T>.reconnecting(Backoff)` (`net/Reconnect.kt`), `ConnectionStatus{CONNECTING,LIVE,DISCONNECTED}` (`comm/CommReducer.kt`), 1008-revoke (`net/WsClose.kt`) | reused as-is; composed up into a hub-level status |
| Composition root | `AgentShell(...)` with ~25 `xxx: Foo? = null` injection ports + `sessionToken: () -> String?` | gains the transport/session/registry/keystore seams; keeps the stub-injection pattern |
| Auth | Kratos: `AuthRepository`/`HttpAuthRepository`, `AuthGate`, `AuthSessionStore` (**in-memory only**), `AuthFlip` (Stub/Live/Misconfigured), `UserTier`, `isOperatorAccess` | **stays the identity layer**; the hub-session layer sits *after* it |
| Secret storage | `InMemoryAuthSessionStore` — no expect/actual, no keychain (documented gap) | filled by a `SecureSessionStore` expect/actual |
| Mode precedent | `AuthFlip.resolveAuthMode(env)` → Stub/Live/Misconfigured (fail-loud) | the exact precedent for a `TransportMode` flip |

**Nothing above is greenfield.** The design is four additive seams stacked on top of these, plus a re-parenting
of the existing sources. The 7 default agents / live behaviour are untouched because Local mode is behaviourally
today's direct-connect with the endpoint chosen at runtime instead of hardcoded.

---

## 2. Layer model (the client, top to bottom)

```
┌──────────────────────────────────────────────────────────────────────┐
│ IDENTITY (unchanged)   AuthGate → AuthRepository (Kratos/OIDC)          │
│   whoami / login / githubStart; session token (X-Session-Token/cookie)  │
└───────────────┬────────────────────────────────────────────────────────┘
                │ authenticated identity + session token
┌───────────────▼────────────────────────────────────────────────────────┐
│ CONTROL-PLANE (new, thin)   ControlPlaneClient                          │
│   GET /hubs → List<HubDescriptor>{hubId,name,online,defaultPort,lastSeen}│
│   issueHubTicket(hubId) → HubSessionTicket (CP-signed JWT)  [seam→BE/CP] │
│   (Phase 2) hubPublicKey(hubId) for remote hub-identity                  │
└───────────────┬────────────────────────────────────────────────────────┘
                │ chosen HubDescriptor + mode + ticket
┌───────────────▼────────────────────────────────────────────────────────┐
│ TRANSPORT SEAM (new)   HubTransport   ── the "business logic once" line │
│   ┌ LocalHubTransport   (actual, Phase 1) direct HTTP/WS on default port │
│   └ RemoteHubTransport   (expect/empty seam, Phase 2 — Noise via CP)     │
│   exposes: httpBaseUrl, wsBaseUrl, httpClient, sessionToken()           │
└───────────────┬────────────────────────────────────────────────────────┘
                │ resolved endpoint + client + token provider
┌───────────────▼────────────────────────────────────────────────────────┐
│ SESSION (new)   HubSession   — one established connection to one hub    │
│   holds transport + ticket + active project/team + HubSessionState      │
│   exposes the same event streams (re-parented live-sources)            │
└───────────────┬────────────────────────────────────────────────────────┘
                │
┌───────────────▼────────────────────────────────────────────────────────┐
│ BUSINESS LOGIC (unchanged)   AgentShell + all VMs / repos / WS sources  │
│   fed transport.httpBaseUrl / wsBaseUrl / httpClient — mode-blind        │
└──────────────────────────────────────────────────────────────────────── ┘
                │  keystore seam (cross-cutting)
        SecureSessionStore (expect/actual) — session-scoped secret material
```

The single most important line is the **transport seam**: everything above it is mode-aware (it chose Local vs
Remote), everything below it is mode-blind (it just consumes `httpBaseUrl`/`wsBaseUrl`/`httpClient`).

---

## 3. The transport-mode seam (`HubTransport`)

The pivot of the whole pass. Today `ShellConfig` hands a URL pair straight to every constructor. We interpose a
seam that *produces* that pair (plus the client + token provider), so the same sources run under either mode.

```kotlin
// commonMain — new: net/hub/HubTransport.kt
/**
 * The one line between mode-aware and mode-blind code. All REST repos and WS live-sources consume ONLY these
 * four members, so they are identical in Local and Remote mode ("Geschäftslogik einmal, zwei Transporte").
 */
interface HubTransport {
    val httpBaseUrl: String          // what repos pass as baseUrl today
    val wsBaseUrl: String            // what live-sources pass as wsBaseUrl today
    val httpClient: HttpClient       // the shared Ktor client (or a tunnel-backed one in Remote)
    fun sessionToken(): String?      // presented as X-Session-Token / ?token= exactly as today
    suspend fun close()
}

enum class HubTransportMode { LOCAL, REMOTE }

/** Phase 1 actual: today's direct-connect, with the endpoint chosen at runtime instead of hardcoded. */
class LocalHubTransport(
    endpoint: HubEndpoint,                     // host + port resolved from the HubDescriptor (§5)
    ticket: () -> String?,                     // the CP-signed JWT presented to the local hub (§5)
) : HubTransport { /* httpBaseUrl="http://host:port", wsBaseUrl="ws://host:port", shared client */ }

/** Phase 2 seam — compiled, not implemented in Phase 1. Terminates a Noise-E2E tunnel via the Control Plane. */
expect class RemoteHubTransport : HubTransport   // actual = NotYetAvailable stub in Phase 1
```

**Design choices & why:**
- **Keep the `(client, baseUrl, token)` contract.** By having the transport *expose* those three, zero source
  code changes below the seam. This is the cheapest possible re-parenting and preserves every reconnect/1008
  behaviour.
- **`HubEndpoint`** (host, port, scheme) is derived from the chosen `HubDescriptor` + mode. Local: `defaultPort`
  from the registry (or the platform default 8787, keeping `ShellConfig`'s current fallback). This subsumes
  today's `ShellConfig.dev()/forOrigin()` endpoint logic; those become *one* `HubEndpoint` producer.
- **Remote is a seam, not a stub-with-behaviour.** Phase 1 ships `RemoteHubTransport` as an `expect` with a
  `NotYetAvailable` actual that fails loud (mirroring `AuthConfigException`/`AuthFlip.Misconfigured`). The point
  is that the *types compile* so Phase-2 work is additive, and that the UI can render "Remote — coming soon"
  honestly rather than pretending.
- **Where the Remote tunnel plugs in (OPEN, →Backend/Reviewer):** two candidate shapes — (a) the transport hands
  a **custom Ktor client** whose engine Noise-frames + relays each request/frame (base URLs stay logical); or
  (b) the transport stands up a **loopback terminator** and the base URLs point at `127.0.0.1:<ephemeral>` while
  a local coroutine bridges to the CP relay. (a) is cleaner for KMP (no loopback server on wasm/iOS); (b) reuses
  the existing sources with zero engine work. This is a Phase-2 decision but the seam must not foreclose either —
  hence `httpClient` is a member of the transport, not a shell-global.

**Mode selection precedent:** extend `AuthFlip`'s pattern — a `TransportModeResolver` picks LOCAL/REMOTE (+ a
`Stub` for tests) from the user's choice in the hub-picker, not from env. Tests inject a `StubHubTransport`
(pointing at an embedded Ktor test server) exactly as they inject `StubCommLiveSource` today.

---

## 4. Session-manager client + event-stream subscription (`HubSession`)

The concept's **Session Manager** lives *in the hub* (server-side). The client's counterpart is a thin
`HubSession` that represents "I am connected to hub X" and owns the client-side lifecycle.

```kotlin
// commonMain — new: net/hub/HubSession.kt
class HubSession(
    val descriptor: HubDescriptor,
    val mode: HubTransportMode,
    val transport: HubTransport,
    // active project/team context already exists client-side (ProjectRepository/activeProjectId) — HubSession
    // becomes its parent scope so a hub switch tears the project VMs down cleanly (reuses ProjectVmStoreManager).
) {
    val state: StateFlow<HubSessionState>    // hub-level status, composed from per-socket ConnectionStatus
    suspend fun close()
}

enum class HubSessionState { CONNECTING, ESTABLISHED, REAUTHENTICATING, LOST }
```

**Event-stream subscription = the existing live-sources, re-parented.** There is *no new streaming machinery*.
`AgentWsClient` (`/ws/agent`), `CommWsClient` (`/ws/comm`), `EventsWsClient`, `BusyStateLiveSource`,
`TokenUsageLiveSource`, `TerminalControlLiveSource`, `WsTerminalSession`, lifecycle — all keep their
`channelFlow{ webSocket(...) } .reconnecting()` shape; they are simply constructed from
`hubSession.transport.{wsBaseUrl, httpClient, sessionToken()}` instead of from a raw `ShellConfig`. In Remote
mode the *same* flows run over the tunnelled client, so the concept's "E2E-getunnelter Stream" needs no source
changes — only the transport differs.

**Hub-level status** composes the per-socket `ConnectionStatus` (already emitted by `AgentWsClient.connection`
and derivable via `CommReducer.statusOf`): if the primary sockets are `DISCONNECTED` and reconnect backoff is
active → `HubSessionState.LOST`; a 1008 access-revoke (`isAccessRevoked`) on the session channel → a terminal
`LOST` with a re-auth prompt rather than silent reconnect. This reuses `net/Reconnect.kt` + `net/WsClose.kt`
wholesale; it only *aggregates* them to hub granularity for the UIUX connection banner.

**Two frontends on one hub** (epic open point #2) is a *hub* concern, but the client must tolerate displacement:
`HubSession` treats a policy-close (1008) as `LOST` + a user-facing "session taken over elsewhere", never an
infinite reconnect loop. Flagged to Backend for the close-code contract.

---

## 5. Control-Plane consumption: hub selection + local JWT handshake

A new, deliberately thin `ControlPlaneClient` — the *only* client code that talks to `api.cyppie-agents.com`.
It sits between identity (Kratos) and transport.

```kotlin
// commonMain — new: net/cp/ControlPlaneClient.kt
interface ControlPlaneClient {
    suspend fun hubs(): List<HubDescriptor>                 // GET /hubs   [DTO shape → Backend/CP]
    suspend fun issueHubTicket(hubId: String): HubSessionTicket  // CP-signed JWT for the hub handshake [→BE/CP]
    // Phase 2 only:
    suspend fun hubPublicKey(hubId: String): HubPublicKey   // to authenticate the hub over the relay
}

// :core (Backend/CP-owned wire DTOs — client CONSUMES, does not define):
// data class HubDescriptor(hubId, name, online: Boolean, defaultPort: Int, lastSeen: Long)
// data class HubSessionTicket(jwt: String, expiresAt: Long)
```

**Hub picker (client state, UX by UIUX):**

```kotlin
// commonMain — new: connect/HubPickerViewModel.kt
class HubPickerViewModel(cp: ControlPlaneClient) {
    val hubs: StateFlow<HubListState>        // Loading / Loaded(List<HubDescriptor>) / Error(retryable)
    fun choose(hub: HubDescriptor, mode: HubTransportMode)   // → produces a HubSession (§4)
}
```
Reuses the established load-state pattern (CYP-288 load-error-retry) and fail-closed honesty: an offline hub is
selectable only in Remote (Phase 2) and shows "offline" from `online`/`lastSeen`; in Phase 1 offline hubs are
shown but Remote is gated "coming soon".

**Local-mode JWT handshake (client side of Sequenz B):**
1. Identity already established (Kratos session, unchanged).
2. `cp.issueHubTicket(hubId)` → a **CP-signed JWT** scoped to that hub/owner. *(Whether this is a distinct CP
   exchange or the Kratos session token itself is a Backend/CP decision — see Open Q1.)*
3. `LocalHubTransport` presents the JWT to the hub's Local API on connect (as `X-Session-Token` / `?token=` —
   the exact transport the sources already use).
4. The **hub** verifies the JWT against the CP's public signature key, which the *hub* has cached (offline-capable
   per the concept). **The client does not verify its own token.** The client's only "cached CP key" duty is
   Phase-2 *hub-identity* verification (`hubPublicKey`), so a `ControlPlaneKeyCache` seam is defined now but only
   *used* in Remote mode:

```kotlin
// commonMain — new: net/cp/ControlPlaneKeyCache.kt
interface ControlPlaneKeyCache {                 // NON-secret public keys → may persist durably (offline)
    suspend fun cpVerificationKey(): PublicKeyBytes?      // Phase 2: to trust hubPublicKey provenance
    suspend fun put(...)
}
```
I flag explicitly (Open Q2) that the PO's phrase *"JWT-Handshake gegen gecachten CP-Schlüssel clientseitig
konsumieren"* resolves, on the client, to **presenting** the CP-issued JWT in the local handshake — the
verifying party is the hub. If the intent is that the *client* also pins the hub via a cached CP key in Local
mode, that is a stronger (mutual-auth-on-LAN) model to confirm with Reviewer.

---

## 6. Client keystore seam (`SecureSessionStore`, expect/actual)

Fills the documented `AuthSessionStore` gap. The frontend trust-zone rule (concept §Vertrauenszonen) is
**"persistiert keine Geheimnisse über die Sitzung hinaus."** The seam encodes that as a *contract*, not a hope.

```kotlin
// commonMain — new: auth/SecureSessionStore.kt   (replaces InMemoryAuthSessionStore behind AuthSessionStore)
expect class SecureSessionStore() {
    suspend fun sessionMaterial(): SessionMaterial?     // JWT/ticket; Phase 2: Noise session keys
    suspend fun put(material: SessionMaterial, persistence: Persistence)
    suspend fun clear()                                  // on logout AND on session end
}
enum class Persistence { SESSION_ONLY, DEVICE_SECURE }   // DEVICE_SECURE only where hardware-backed
```

| Target | `actual` (Phase 1) | Later |
|---|---|---|
| Web (wasm/js) | **in-memory only** — never `localStorage` for secrets (matches today's cookie/in-memory model) | — (web stays session-only by construction) |
| Desktop (JVM) | in-memory | macOS Keychain / Linux libsecret (Secret Service) |
| Android | in-memory | **Android Keystore** (EncryptedSharedPreferences / DataStore) |
| iOS | in-memory | **iOS Keychain** |

Precedent: `ui/ThemePreferences.*` is the existing expect/actual platform-storage pattern — mirror its shape,
but for secret material with the session-scoped clear contract. The **CP verification public key** (§5) is
*non-secret* and goes in `ControlPlaneKeyCache` (durable OK), kept deliberately separate from
`SecureSessionStore` so the trust boundary is legible in the type system.

**Tension to resolve (Open Q3):** the strict reading is *session-only in-memory everywhere*. A `DEVICE_SECURE`
"remember me" (keychain-backed, survives app restart, revocable) is a real mobile UX need but weakens "no
secrets beyond the session". Product/Reviewer call — the enum keeps both expressible without committing.

---

## 7. Relation to the existing Kratos auth client

The identity layer is **unchanged and remains the entry gate**: `AuthGate` → `AuthRepository` (Kratos OIDC /
email-magic-link / GitHub) → a session (native `X-Session-Token`, browser `ory_kratos_session` cookie) →
`UserTier`. The new layers sit *after* a successful auth:

```
AuthGate(Kratos)  →  HubPicker(ControlPlaneClient.hubs)  →  HubSession(transport+ticket)  →  AgentShell(hubSession)
   unchanged              new                                   new                              evolved signature
```

- `AgentShell` evolves from taking a raw `ShellConfig` to taking a `HubSession` (or, minimally, the transport +
  descriptor); its ~25 `Foo? = null` injection ports **stay** — they now default to `injected ?: LiveRepo(
  hubSession.transport.httpClient, hubSession.transport.httpBaseUrl, hubSession.transport.sessionToken() ?: "")`.
  This is a mechanical change at the composition root, invisible to VMs.
- The **CP JWT** vs the **Kratos session token**: today `sessionToken` (Kratos) is presented to the *single*
  server. In the hub model the token presented to a *hub* should be the CP-issued, hub-scoped `HubSessionTicket`,
  not the raw Kratos session (a hub must not receive a credential valid against the CP). Mapping Kratos-session →
  CP-ticket is **Open Q1** (Backend/CP). Until resolved, Local dev keeps presenting the existing token so nothing
  regresses.
- The `operatorToken` break-glass path stays independent for now (each repo's `Authorization: Bearer`), but it
  should converge onto the ticket model eventually so there is one credential per hub session (Open Q4).

---

## 8. End-to-end state flow (Phase 1, Local mode)

```
 ┌ not authenticated ┐
 │  AuthGate (Kratos) │  ── login / githubStart ──►  authenticated (session token, UserTier)
 └─────────┬──────────┘
           ▼
 ┌ hub selection ┐   ControlPlaneClient.hubs()  ─►  [HubDescriptor…]   (Loading/Loaded/Error, retryable)
 │  HubPicker     │   user picks hub + mode
 └───────┬────────┘        │ LOCAL                              │ REMOTE (Phase 2)
         ▼                 ▼                                    ▼
 issueHubTicket(hubId) ─► HubSessionTicket(jwt)          RemoteHubTransport = NotYetAvailable
         │                 │                              (UI: "Remote — coming soon", honest)
         ▼                 ▼
 HubEndpoint(host, defaultPort) ─► LocalHubTransport(endpoint, ticket)
         ▼
 HubSession(CONNECTING) ─ present JWT on first socket ─► hub verifies vs cached CP key ─► ESTABLISHED
         ▼
 AgentShell(hubSession):  all live-sources subscribe over transport.wsBaseUrl  (mode-blind)
         ▼
 reconnect via net/Reconnect.kt;  1008 → HubSessionState.LOST + re-auth;  logout → SecureSessionStore.clear()
```

The only new decision points are *hub selection* and *mode*; everything after `AgentShell(...)` is today's app.

---

## 9. `:core` (a.k.a. "`:protocol`") consumption

The shared wire module is **`:core`** (there is no `:protocol` module; `:core` applies kotlinx.serialization and
owns `CommJson`). The client keeps consuming existing DTOs unchanged. **New DTOs required from Backend/CP** (the
client consumes; Backend owns the shape — routed via coordinator):

| DTO / surface | Purpose | Owner |
|---|---|---|
| `HubDescriptor{hubId,name,online,defaultPort,lastSeen}` + `GET /hubs` | hub picker | CP/Backend |
| `HubSessionTicket{jwt,expiresAt}` + `issueHubTicket` | local handshake credential | CP/Backend |
| local-handshake ack/error frame | establish/deny a `HubSession` | Backend (hub Local API) |
| (Phase 2) `HubPublicKey`, relay envelope | remote hub-identity + Noise | CP/Backend/Reviewer |

No client-defined wire types — consistency with the existing "one `:core` DTO, `CommJson` both sides" rule that
keeps client/server from drifting.

---

## 10. Open decisions (for the coordinator / other passes)

1. **Ticket vs Kratos session (→Backend/CP, Reviewer).** Does the hub receive a distinct CP-signed, hub-scoped
   JWT (`issueHubTicket`), or the raw Kratos session token? Security says the former (a hub must not hold a
   CP-valid credential). Confirm the exchange + token audience/lifetime.
2. **"Cached CP key" locus (→Reviewer).** In Local mode the *hub* verifies the client JWT against the cached CP
   key. Does the *client* also pin the hub via a cached CP key on LAN (mutual auth), or is localhost/LAN trust
   sufficient for Phase 1? Determines whether `ControlPlaneKeyCache` is used in Local or only Phase-2 Remote.
3. **Secret persistence policy (→Reviewer/Product).** Strict "no secrets beyond the session" (in-memory only,
   everywhere) vs a hardware-backed `DEVICE_SECURE` "remember me" on Android/iOS. The `Persistence` enum keeps
   both open; product must choose the default.
4. **`operatorToken` convergence (→Backend).** Fold the break-glass operator token into the per-hub ticket model,
   or keep it a separate independent path? Affects whether there is one credential per hub session.
5. **Remote transport plug-point (→Backend/Reviewer, Phase 2).** Custom-Ktor-engine (Noise-framing client) vs
   loopback-terminator. The `HubTransport` seam keeps both open; decide before Phase 2 build.
6. **`ShellConfig` migration (→Backend).** `ShellConfig` becomes the *output* of a `HubEndpoint` producer.
   Confirm the platform default port stays 8787 and that `defaultShellConfig()` actuals collapse into the
   endpoint resolver without regressing JVM env (`HUB_HOST`/`HUB_PORT`) or web-origin derivation.
7. **Overlap with CYP-220 (secret consumers) & CYP-234 (frontend-agnostic contract) (→coordinator).**
   `SecureSessionStore` and the transport seam touch both; reconcile ownership so we don't build two keystores or
   two endpoint models.
8. **Multi-hub persistence (→UIUX/Product).** Does the client remember the last hub + mode across launches
   (a non-secret preference, `ThemePreferences`-style), or always re-pick? Shapes the `HubPicker` entry state.

---

## 11. Build increments (proposed, post-ratification — NOT part of this pass)

Sequencing only, to show the seam is incrementally shippable and low-risk:
1. `HubTransport` seam + `LocalHubTransport` wrapping today's `ShellConfig` endpoint → `AgentShell` consumes the
   transport (pure refactor, behaviour-identical; full gate proves no regression).
2. `SecureSessionStore` expect/actual (in-memory actuals first) behind `AuthSessionStore` (no behaviour change).
3. `ControlPlaneClient.hubs()` + `HubPicker` (behind a flag, `AuthFlip`-style) → single-hub still works.
4. `issueHubTicket` + local JWT handshake wired into `LocalHubTransport` (needs the Backend ticket contract).
5. `RemoteHubTransport` = `NotYetAvailable` seam + honest UI gate (closes Phase 1; Phase 2 fills the actual).

Each step is additive, guarded by the existing stub-injection pattern, and independently gate-able. **No step
starts before Auftraggeber ratification of this design.**
