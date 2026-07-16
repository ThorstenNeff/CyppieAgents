# CYP-638 — Server-Side Gateway (Option A): Design Pass

> Status: **DESIGN — no build.** Deliverable = this doc → PO review → **★ Auftraggeber trust-boundary ratification** → stories → build.
> Author: Backend/Team-1. Grounded in `develop 4cfc40d9` via a read-only route/auth/tunnel survey (every claim below cites `file:line`).
> Contract: CYP-638 Gateway Requirements (Backend2/po2, consumer-driven, Deliverable 1 §1–§6). Deliverable 2 (web-ts parity delta) is **context only** — the server is parity-complete; that delta is Dev5's client-porting, not this design.

---

## 0. One-paragraph summary

The workspace browser must reach the hub over **plain, same-origin HTTPS/WSS** (that is the only way the httpOnly `ory_kratos_session` cookie is set + auto-sent, which is what makes `/ws/agent`'s cookie-only auth work). The **machine / remote-operate surface** (`/api/cp/*`, `/ws/hub`, `/mcp/hub`, relay, Noise/mux framing) must be **unreachable** from the browser. Today the tunnel bridge is a *dumb byte-pump* that forwards **everything** to the hub and relies purely on per-route auth (`LoopbackBridge.kt:70-121`) — there is no edge that *refuses* the control surface. The **Gateway is that edge**: a same-origin front-door that proxies exactly the §1–§2 data-plane **verbatim** (method + path + body + auth), **default-denies** everything else, terminates the operator's Noise/mux tunnel (reusing the CYP-458/620 seam), and preserves the reconnect/close contract. Its single load-bearing security fact — **the Noise tunnel terminates at the gateway, so decrypted operator↔hub cleartext is visible server-side** — is **not mine to accept**; it is framed in §8 as the explicit decision the Auftraggeber ratifies.

---

## 1. Grounded topology (what exists today)

`Application.kt:52-58` binds **two Netty connectors on one shared platform**:

- **Public connector** — `config.hub.port` (default **8787**), `config.hub.host`. "Local operator UI + agents."
- **Tunnel-scoped connector** — `config.hub.tunnelPort` (default **8786**), hard-bound `127.0.0.1`; the static God-token is **refused** here (`installTunnelGodTokenGuard`, `TunnelGodTokenGuard.kt:53-68`).
- **Relay** — a *separate deployable process* on **8788** (`relay/RelayServer.kt:95-99`), never mounted on this Application.

The whole HTTP/WS surface is installed by `installPlatform`/`bootPlatform` in `routing/PlatformWiring.kt` (one `routing{}` block, `PlatformWiring.kt:91-254`). The authoritative REST inventory + auth-tier per op is hand-declared in `contract/RestContract.kt:83-177` and drift-tested against the live routes — **this is the allowlist data structure the gateway single-sources from** (§4).

Server-side Noise termination already exists (INERT unless `CYPPIE_REMOTE_RELAY_URL`, `RemoteRelayWiring.kt:143`): relay leg → `NoiseJavaServerTerminator` → `Rr3TunnelGate.authorize` (CpJwt + operator PoP, `RemoteRelayWiring.kt:99-105`) → **`LoopbackBridge`/`MuxBridge`** → a `127.0.0.1:tunnelPort` loopback socket (`PlatformWiring.kt:391`). **This is the reusable Option-A precedent** — but it is a *dumb byte-pump*, not path-aware.

---

## 2. Goal & non-goals

**Goal.** A same-origin front-door that:
1. proxies exactly the §1–§2 **data-plane** surface verbatim (auth-tier enforcement stays server-owned);
2. **default-denies** the machine/remote-operate surface at the edge (defense-in-depth over the hub's own gates);
3. terminates the operator's Noise/mux tunnel (reusing the termination seam) **with path-awareness** instead of a dumb pump;
4. preserves the reconnect/close contract (`?since` cursor, close-1008 unmasked).

**Non-goals.** No new hub endpoints (server is parity-complete). No auth **re-implementation** (the gateway forwards credentials verbatim; the hub's `AuthGuard`/`wsReaderOrNull` enforce tiers). No change to the tunnel crypto (Noise NK / Rr3) itself.

---

## 3. Architecture

The browser always speaks **plain same-origin HTTPS/WSS to the gateway**; the **gateway↔hub** hop is either a direct loopback (co-located) or the Noise/mux tunnel (remote operator) — invisible to the browser either way.

```
            ┌─────────── same origin (plain HTTPS/WSS) ───────────┐
  Browser ──┤  GET /  · /api/*  · /ws/*  · Kratos login flow      ├──► GATEWAY ──►(loopback OR Noise/mux tunnel)──► hub public routes
            └──────────────────────────────────────────────────────┘        │
                                                                             ├─ allowlist filter (default-deny §5)
                                                                             ├─ verbatim auth passthrough (§6)
                                                                             └─ tunnel terminate + Rr3 gate (reuse §9)
```

**Where the gateway lives — two shapes, one recommendation:**

| | A1 — in-process Ktor front-door (recommended for MVP) | A2 — separate proxy process |
|---|---|---|
| Form | A same-origin edge listener + an allowlist reverse-proxy layer in `:server`, reusing `RemoteRelayWiring` for the tunnel hop. | A thin standalone reverse-proxy (own process) in front of the hub, like the relay is today. |
| Reuse | Maximal — one auth model, one build, direct reuse of the terminator/bridge/guards. | Lower — re-plumbs credential forwarding across a process boundary. |
| Trust-boundary audit (§8) | Cleartext termination shares the hub process. | **Isolates** the cleartext-termination into its own auditable process — a stronger posture for the ratified boundary. |
| Recommendation | **Start here** (simplest, most reuse). | **Flag as the hardening path** — its process-isolation advantage is exactly a §8 ratification input; the Auftraggeber may prefer it. |

Recommendation: **A1 for the first cut**, with A2 explicitly surfaced as the isolation-hardening option because it bears on the trust-boundary decision (§8) — that choice is the Auftraggeber's, not mine.

**The genuinely new part (either shape):** a **path-aware** allowlist at the termination point. Today's bridge forwards every byte; the gateway must parse the request line, match the path against the single-sourced allowlist, and **refuse** the control surface at the edge (§5).

---

## 4. Exposed surface — the allowlist (default-deny)

**Single-source it.** `RestContract.REST_OPS` (`RestContract.kt:83`) already enumerates every legitimate frontend REST op with its tier, and `EXCLUDED_API_PATHS` (`RestContract.kt:78`) already marks the control surface. The gateway allowlist **derives from `RestContract`** so the two can never drift (a hand-copied list is the classic drift bug — CLAUDE.md single-source rule).

**REST** — dual-mounted under `/api` **and** `/api/v1` (`PlatformWiring.kt:141`). The data-plane set (per contract §1, all confirmed live):
agents (`/api/agents`, `/api/agents/{id}` GET/PUT/DELETE, `/mode`, `/{start,stop,restart}`, `/connector`, `/avatar`(+`/preview`), `/claude-md`, `/terminal-grants`); comm (`/api/channels`, `/channels/writable`, `/channels/{id}/messages` GET+POST, `/channels/{id}/share`); `/api/acl` GET+PUT; `/api/inbox`; `/api/events`; `/api/connectors`; `/api/config/{repo,apikey}` GET+PUT + `/config/repo/reprovision-preview`; `/api/compact/{status,config}`; `/api/reports`(+`/{id}`); `/api/projects`(+`/switch`,`/{id}`); `/api/capacity`; `/api/workspace/members`, `/api/audit`; `/api/server-now`; `POST /api/ws-ticket`; `/api/participant-tokens`; `/api/auth/me`, `/api/auth/register`, `/api/auth/settings/{password,email}`; **`/api/health` (PUBLIC — the one no-auth pass)**. (Installers cited in the survey; e.g. `CommRoutes.kt:103`, `AgentMgmtRoutes.kt:53`, `ProjectRoutes.kt:45`, `WsTicketRoutes.kt:19`.)

**WebSocket — the 8 data-plane sockets** (`PlatformWiring.kt:96-131`):
`/ws/comm`, `/ws/events`, `/ws/lifecycle`, `/ws/token-usage`, `/ws/busy-state`, `/ws/terminal-state` (the **six read-sockets**, §6), `/ws/agent` (drive), `/ws/terminal` (operator PTY — see §6 caveat).

**Kratos login flow** (must be same-origin, §6): proxy `/.ory/kratos/public/self-service/login/browser` (GET), `/self-service/login?flow=` (POST `{method:password,identifier,password,csrf_token}`), and logout.

Pass-through query params: `agentId, since, token, ticket, projectId`; pass-through client frames: `Subscribe` / `SubscribeEvents`.

---

## 5. Refused surface — default-deny at the edge (the trust boundary, concretely)

Everything not on the §4 allowlist is refused at the edge (404/403), **before** it reaches the hub. Explicitly:

- **`/api/cp/*`** (prefix) — CP control plane: `rendezvous/{hubId}`, `hubticket`, `challenge`, `admit`, `hubs` (`RendezvousRoutes.kt:47`, `HubTicketRoutes.kt:30`, `HubAdmissionRoutes.kt:47-50`, `HubDiscoveryRoutes.kt:34`). OPERATOR + owner-scoped in-depth; the browser must never even probe them.
- **`/ws/hub`** (`HubWireRoutes.kt:74`) — external Hub-Wire-Protocol, **agent-token only**.
- **`/mcp/hub`** (`HubMcpRoutes.kt:43`) — in-process Hub-MCP `hub_send`, **agent-token only**, localhost-intended.
- **`/relay` + relay `/health`** (`RelayServer.kt:41-43`) — separate process; never reaches the hub anyway, but named for completeness.
- Any **Noise/mux/tunnel framing** — terminated at the gateway; never surfaced as a browser-reachable path.
- Also not exposed: `GET /` (dev-only), `GET /api/admin/db/metrics` (defined but **unwired**, `AdminMetricsRoutes.kt:18` — dead on this tip).

These are already OPERATOR/agent-gated in the hub, so the gateway's refusal is **defense-in-depth**, not the sole gate — but it is the contract's stated raison d'être (§5 of the contract): the browser edge sees **only** §1–§2.

---

## 6. Auth preservation per hop (load-bearing — the gateway forwards, never re-implements)

The hub resolves principals via `resolvePrincipal` (`Principal.kt:95`, machine-bearer axis then verified-Kratos-human axis, `verified==true` required `Principal.kt:118-121`) and gates REST structurally by mounting (`Route.authenticatedApi` → `AuthGuard` plugin, `Principal.kt:169-208`, incl. CSRF). The gateway must hand that machinery the **exact same credentials**:

- **Same-origin Kratos cookie (CYP-515 / Seam-4).** The gateway MUST be same-origin with the Kratos login flow so `ory_kratos_session` (httpOnly) is set + auto-sent; forward `Cookie` / `X-Session-Token` **verbatim** to `/api/auth/me` (→ hub whoami → `/sessions/whoami`, `PlatformWiring.kt:433-435`; cookie read at `Principal.kt:72-75`). Without the same-origin cookie nobody logs in and `/ws/agent` is dark.
- **REST auth.** Forward `credentials:include` cookie + optional `Authorization: Bearer <operatorToken>`.
- **WS auth.** `?token=<operatorToken>` for read/write channels; **cookie-only for `/ws/agent`** (no token — the browser can't hold the agent/operator secret, `AgentSocket.kt:62-70`, CYP-230); **`?ticket=`** (single-use from `POST /api/ws-ticket`, `WsTicketRoutes.kt:36`) at the **six read-sockets** consumed by `wsReaderOrNull` (`Auth.kt:180-186`, CYP-286). **Do NOT strip the cookie on the WSS upgrade handshake** — the browser sends it automatically on same-origin WS; a gateway that drops it on `Upgrade` breaks every cookie-authed socket.
- **★ `/ws/terminal` caveat (contract §6).** A privileged **operator-only, WRITE-tier PTY code-exec** socket (`TerminalSocket.kt:68`). Its operator gate **and** kill-on-revoke (mid-session `close(1008, "grant_revoked")`, `TerminalSocket.kt:126`) MUST survive the proxy hop — the termination must not downgrade its auth. If the gateway proxies it at all, this is the highest-risk path; note it is INERT unless `CYPPIE_TERMINAL_DELEGATION_ENABLED` (deploy toggle) — an open question (§10) is whether it is in browser-gateway scope at first.

---

## 7. Reconnect & close-code fidelity

- **`?since` cursor.** Pass through untouched: `/ws/agent` reads `?since` → `agentEvents.subscribe(agentId, since)` (`AgentSocket.kt:132-134`); REST `/api/channels/{id}/messages` + `/api/inbox` use `?since` (`CommRoutes.kt:178,198`); `/ws/events` carries it inside the `SubscribeEvents` frame → `EventFilter.since` (`EventSocket.kt:101-108`). The gateway must not swallow or rewrite it.
- **Close-code 1008 (auth-revoked).** Emitted as `VIOLATED_POLICY`(=1008) at every read-socket auth-null branch (`EventSocket.kt:49`, `LifecycleRoutes.kt:71,98,117,137`, `CommRoutes.kt:248`, `AgentSocket.kt:110`) and terminal grant-revoke (`TerminalSocket.kt:126`). It drives the client's offline/revoked banner — **propagate it cleanly, never mask/rewrite**. (Precedent: the loopback bridge already relays inner 1008 as a clean EOF, `LoopbackBridge.kt:105,117-118` — so the fidelity is achievable.)
- **Transparent frame relay**; no server-side reconnect masking. Same-origin default; support `CYPPIE_API_BASE` / `CYPPIE_WS_BASE` cross-origin override + CORS if the gateway isn't same-origin — but same-origin is **strongly preferred** (it is what makes the cookie + `/ws/agent` work).

---

## 8. ★★ Trust boundary — THE decision the Auftraggeber ratifies (I do not set this)

**The fact.** When the operator is remote, the Noise/mux tunnel **terminates at the gateway**. Between Noise decryption (`NoiseJavaServerTerminator` → `Rr3TunnelGate`, `RemoteRelayWiring.kt:99-112`) and the loopback socket (`RealBridgeSocket.write/read`, `LoopbackBridge.kt:184-189`, → `127.0.0.1:8786`), **cleartext operator↔hub HTTP/WS is visible in-process**: the operator's session cookie, any operator bearer token in transit, all agent traffic, and **PTY bytes on `/ws/terminal`**.

**Threat model (for the ratification):**
- *Who sees cleartext:* the gateway process memory; whoever operates that process; host root. Not the browser, not the network (the tunnel is encrypted on the wire).
- *What mitigates it today:* the loopback origin grants **zero implicit trust** — the unmodified hub routes **re-verify** the real bearer/Kratos credential carried inside the tunnel (`LoopbackBridge.kt:36-39`); T3 loopback-only fail-closed (`require(isLoopbackAddress)`, `LoopbackBridge.kt:64`); the God-token guard refuses the static operator token on the tunnel port (`TunnelGodTokenGuard.kt:53-68`).
- *What the gateway must guarantee at this boundary (auditable):* (1) the allowlist never leaks a control-surface path (§5); (2) auth is **forwarded, never downgraded** (§6), especially `/ws/terminal`; (3) **no logging** of tokens / cookies / PTY bytes or handshake material at the cleartext point (precedent: CYP-190 Kratos-log hardening, CYP-607 diagnostic logging is temporary `Auth.kt:169-219`); (4) memory hygiene / no cleartext spill to disk.

**The ratification item, stated plainly:** *the gateway host is a trusted crypto-termination point with server-side cleartext visibility of all operator↔hub traffic (including PTY exec bytes).* Accepting that posture — and choosing A1 (shared process) vs A2 (isolated process) for it — is the **Auftraggeber's** call at the design gate. I surface it; I do not decide it.

---

## 9. Reuse vs new-build

**Reuse (do not rebuild):** the tunnel terminator + `Rr3TunnelGate` auth gate (`RemoteRelayWiring.kt:95-112`); the `LoopbackBridge`/`MuxBridge` loopback-socket pattern (`LoopbackBridge.kt:51`, `mux/MuxBridge.kt:30`); `WsOriginGuard` (`PlatformWiring.kt:417`); `installRestrictedCors` (`:413`); `installTunnelGodTokenGuard` (`:421`); the `wsReaderOrNull`/`resolvePrincipal` credential model; **`RestContract.REST_OPS` as the allowlist source**; the already-unmasked `?since` + close-1008 plumbing.

**New:** the **path-aware allowlist filter** at the termination point (today's bridge is a dumb byte-pump — this is the core new work); the **Kratos login-flow same-origin proxy**; the **same-origin SPA-serving front-door**; the WSS-handshake **cookie/query passthrough under the allowlist**.

---

## 10. Open questions (for PO + Auftraggeber, before stories)

1. **A1 vs A2** (in-process vs isolated proxy) — tied to the §8 boundary posture. *Auftraggeber input.*
2. **`/ws/terminal` in first scope?** It is the highest-risk path and is deploy-gated (`CYPPIE_TERMINAL_DELEGATION_ENABLED`). Ship the gateway without it first, add behind the flag? *PO.*
3. **TLS termination location** (gateway owns HTTPS/WSS; deploy-owned per `RelayServer.kt:93`). *Deploy/Auftraggeber.*
4. **Path-aware vs dumb-pump + hub-auth-only:** the hub already gates the control surface, so is edge path-filtering *required* or belt-and-suspenders? The contract says MUST-NOT-EXPOSE **at the edge** → treat as required; confirm. *PO.*
5. **SPA serving:** does the gateway also serve the static SPA (same-origin), or proxy only? Contract implies front-door → likely serves. *PO/Dev5.*
6. **Cross-origin fallback:** if `CYPPIE_API_BASE`/`CYPPIE_WS_BASE` override is used, the CORS + cookie `SameSite` posture (cf. CYP-563 CSRF/Secure). *Backend/PO.*

---

## 11. Proposed stories (post-ratification — indicative sizes)

| ID | Story | Size |
|---|---|---|
| S1 | Allowlist REST reverse-proxy, single-sourced from `RestContract`; default-deny edge; verbatim method/path/body/header forwarding. | M |
| S2 | WS upgrade passthrough for the 8 data sockets; query (`token/ticket/since/agentId/projectId`) + cookie preserved on the handshake; `Subscribe`/`SubscribeEvents` frames transparent. | M |
| S3 | Kratos login-flow same-origin proxy (login browser GET / login POST / logout) + cookie set/forward; `/api/auth/me` whoami passthrough. | M |
| S4 | Reconnect/close fidelity: `?since` pass-through + close-1008 unmasked, verified with a real revoke. | S |
| S5 | `/ws/terminal` operator-gate + kill-on-revoke survival across the hop (behind `CYPPIE_TERMINAL_DELEGATION_ENABLED`). | M |
| S6 | Cleartext-boundary hardening: no token/cookie/PTY logging, memory hygiene, audit hooks at the termination point (§8). | M |
| S7 | Deploy/config: TLS, same-origin default, `CYPPIE_API_BASE`/`CYPPIE_WS_BASE` + CORS, A1/A2 packaging. | S–M |

Each story ends with a **real proof** (a browser/`curl`/`wscat` run showing the allowed path works, the refused path 404s at the edge, the cookie/ticket survives, the 1008 propagates) — not a unit pass. The allowlist + the §8 hardening are the merge-blocking security teeth.

---

## 12. Definition of done for the design pass

This doc → PO review → **Auftraggeber ratifies the §8 trust boundary (and A1/A2)** → the §11 stories are opened → build. **No code until the §8 sign-off.**
