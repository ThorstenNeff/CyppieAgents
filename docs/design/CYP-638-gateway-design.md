# CYP-638 — Server-Side Gateway (Option A2 · isolated process): Design v2

> Status: **DESIGN v2 — §8 trust boundary RATIFIED by the Auftraggeber at the design gate; build authorized.** This doc (v2) + the §11 story breakdown → PO review → build.
> Author: Backend/Team-1. Grounded in `develop 4cfc40d9` via a read-only route/auth/tunnel survey (every claim cites `file:line`).
> Contract: CYP-638 Gateway Requirements (Backend2/po2, Deliverable 1 §1–§6). Deliverable 2 (web-ts parity) = context (server is parity-complete).

### Changelog v1 → v2 (ratified decisions + PO refinements folded)
- **★ A2 — ISOLATED PROCESS** (not A1/in-process). The gateway is its own deployable process in front of the hub (relay-precedent), isolating the cleartext termination into a dedicated **auditable** process = the stronger trust boundary the Auftraggeber chose. Cost accepted: credential forwarding across a process boundary.
- **★ `/ws/terminal` IS in the first cut** (Auftraggeber overruled the R2 defer — he wants PTY parity from day 1). ⟹ the first §8 boundary **includes PTY-exec cleartext** (explicitly accepted). **S5 (terminal auth survival) is first-cut; S6 (cleartext-boundary hardening) is first-cut MANDATORY, covering PTY bytes.** No "later §8 delta."
- **R1** — the WS allowlist is **single-sourced from `ContractGenerator`** (AsyncAPI), closing the WS-drift gap (v1 had a hand-list).
- **R4** — the gateway proxies the **full** Kratos self-service surface (login/registration/recovery/verification/settings/logout), not just login/logout.
- **R3** — SPA served **same-origin** is a hard **requirement** (not an open question).
- **Q4** — path-aware edge filtering is **required** (contract §5 "MUST-NOT-EXPOSE at the edge"), not belt-and-suspenders.

---

## 0. Summary

The workspace browser reaches the hub over **plain, same-origin HTTPS/WSS** (the only way the httpOnly `ory_kratos_session` cookie is set + auto-sent, which is what makes `/ws/agent`'s cookie-only auth work). The **machine/remote-operate surface** (`/api/cp/*`, `/ws/hub`, `/mcp/hub`, relay, Noise/mux framing) must be **unreachable** from the browser. Today the tunnel bridge is a *dumb byte-pump* that forwards **everything** and relies purely on per-route auth (`LoopbackBridge.kt:70-121`). The **Gateway is a separate, auditable process** (A2) that: accepts the browser's plain same-origin connection, serves the SPA same-origin, proxies the full Kratos self-service flow, **allowlist-filters** to exactly the §1–§2 data-plane (default-deny), terminates the operator's Noise/mux tunnel to the hub (reusing the CYP-458/620 seam), and preserves the auth + reconnect/close contract — including `/ws/terminal`. Its ratified security fact — **the tunnel terminates in the gateway process, so decrypted operator↔hub cleartext (incl. PTY bytes) is visible there** — is isolated into that dedicated process by the A2 choice.

---

## 1. Grounded topology (what exists today)

`Application.kt:52-58` binds **two Netty connectors on one platform**: public `config.hub.port` (default **8787**); tunnel-scoped `config.hub.tunnelPort` (default **8786**, `127.0.0.1`, static God-token refused — `TunnelGodTokenGuard.kt:53-68`). **Relay is a separate process** on **8788** (`relay/RelayServer.kt:95-99`) — the precedent for A2's separate-process shape.

The HTTP/WS surface is installed in `routing/PlatformWiring.kt:91-254`. Authoritative contracts (the gateway's allowlist sources): REST = **`RestContract.REST_OPS` (`RestContract.kt:83`)** + `EXCLUDED_API_PATHS` (`:78`); WS = **`ContractGenerator` AsyncAPI channel list (`ContractGenerator.kt:43-55`)** + `EXCLUDED_WS_PATHS = {"/ws/hub"}` (`:59`) — both already drift-tested.

Noise termination exists (INERT unless `CYPPIE_REMOTE_RELAY_URL`, `RemoteRelayWiring.kt:143`): relay leg → `NoiseJavaServerTerminator` → `Rr3TunnelGate.authorize` (`RemoteRelayWiring.kt:99-105`) → **`LoopbackBridge`/`MuxBridge`** → `127.0.0.1:tunnelPort` (`PlatformWiring.kt:391`). Reusable, but a *dumb byte-pump* — not path-aware.

---

## 2. Goal & non-goals

**Goal.** A **separate gateway process** that: (1) serves the SPA + proxies the §1–§2 data-plane **verbatim** same-origin (auth-tier enforcement stays hub-owned); (2) **default-denies** the machine surface at the edge (path-aware, required); (3) terminates the operator Noise/mux tunnel (reuse) and forwards to the hub with credentials preserved across the process boundary; (4) preserves the reconnect/close contract (`?since`, close-1008), including `/ws/terminal`.

**Non-goals.** No new hub endpoints (server parity-complete). No auth **re-implementation** (forward verbatim; hub `AuthGuard`/`wsReaderOrNull` enforce). No change to the tunnel crypto (Noise NK / Rr3).

---

## 3. Architecture — A2, an isolated gateway process

```
                      same origin (plain HTTPS/WSS)                    Noise/mux tunnel (or loopback if co-located)
  Browser ── GET / · /api/* · /ws/* · Kratos self-service ──►  GATEWAY PROCESS  ──────────────────────────────────►  HUB
                                                               │  serves SPA (same-origin, R3)                        (public/tunnel
                                                               │  Kratos self-service same-origin proxy (R4)           connector,
                                                               │  ALLOWLIST filter (REST=RestContract, WS=ContractGenerator) — default-deny
                                                               │  verbatim credential forwarding (cookie/bearer/?token/?ticket/?since)
                                                               │  tunnel terminate + Rr3 gate (REUSE RemoteRelayWiring)
                                                               └─ ★ cleartext operator↔hub (incl. PTY) lives HERE — isolated + auditable (§8)
```

**Shape (ratified A2):** a new deployable process — precedent `:server:relayRun` (`RelayServer.kt`). It owns the browser-facing plain listener (TLS/WSS), same-origin SPA serving, the Kratos self-service proxy, the allowlist filter, and the gateway→hub link. The **cleartext wrap/unwrap** of operator↔hub traffic happens in this process only — the hub process never sees the browser directly, and the cleartext boundary is a single, isolated, auditable surface.

**Credential forwarding across the process boundary (the A2 cost):** the gateway is a reverse-proxy — it forwards the browser's `Cookie`/`X-Session-Token`, `Authorization: Bearer`, and `?token`/`?ticket`/`?since` **verbatim** to the hub; the hub re-verifies (loopback grants zero implicit trust, `LoopbackBridge.kt:36-39`). The gateway adds **no** trust of its own and holds **no** long-lived secret of the operator's beyond the in-flight forward.

**The genuinely new part:** a **path-aware** allowlist at the termination point. Today's bridge forwards every byte; the gateway parses the request line / WS upgrade, matches the path against the single-sourced allowlist, and **refuses** the control surface at the edge (§5).

---

## 4. Exposed surface — the allowlist (default-deny, single-sourced)

**REST** — dual-mounted `/api` + `/api/v1` (`PlatformWiring.kt:141`); allowlist **derived from `RestContract.REST_OPS`** (no hand-copy). The data-plane set (contract §1, all live): agents (`/api/agents`, `/{id}` GET/PUT/DELETE, `/mode`, `/{start,stop,restart}`, `/connector`, `/avatar`(+`/preview`), `/claude-md`, `/terminal-grants`); comm (`/api/channels`, `/channels/writable`, `/channels/{id}/messages` GET+POST, `/channels/{id}/share`); `/api/acl` GET+PUT; `/api/inbox`; `/api/events`; `/api/connectors`; `/api/config/{repo,apikey}` GET+PUT + `/config/repo/reprovision-preview`; `/api/compact/{status,config}`; `/api/reports`(+`/{id}`); `/api/projects`(+`/switch`,`/{id}`); `/api/capacity`; `/api/workspace/members`, `/api/audit`; `/api/server-now`; `POST /api/ws-ticket`; `/api/participant-tokens`; `/api/auth/me`, `/api/auth/register`, `/api/auth/settings/{password,email}`; **`/api/health`** (PUBLIC).

**WebSocket — the 8 data sockets, single-sourced from `ContractGenerator` (R1)** (`ContractGenerator.kt:43-55`): `/ws/comm`, `/ws/events`, `/ws/lifecycle`, `/ws/token-usage`, `/ws/busy-state`, `/ws/terminal-state` (the 6 read-sockets), `/ws/agent` (drive), **`/ws/terminal` (operator PTY — first-cut, §6)**. A new hub socket that isn't in `ContractGenerator` + isn't in `EXCLUDED_WS_PATHS` fails a drift-test rather than being silently edge-refused.

**Kratos self-service — the FULL browser surface, same-origin (R4)** — the client drives all of these (`HttpAuthRepository.kt:35-36,191,241,310`, CYP-181 P2): `login`, `registration`, `recovery`, `verification`, `settings`, `logout` (`/.ory/kratos/public/self-service/{flow}/browser` + `/self-service/{flow}?flow=` + the `action` submits + csrf). (po2/Dev5 confirm the web-ts specifics at build.)

Pass-through query: `agentId, since, token, ticket, projectId`; pass-through frames: `Subscribe`/`SubscribeEvents`.

---

## 5. Refused surface — default-deny at the edge (path-aware, REQUIRED)

Everything off the §4 allowlist is refused (404/403) **before** it reaches the hub: `/api/cp/*` (prefix — rendezvous/hubticket/challenge/admit/hubs); `/ws/hub` (agent-token wire, also `EXCLUDED_WS_PATHS`); `/mcp/hub` (agent-token MCP, also `EXCLUDED_API_PATHS`); `/relay` + relay `/health` (separate process); any Noise/mux/tunnel framing; `GET /` (dev-only), `GET /api/admin/db/metrics` (unwired). These are OPERATOR/agent-gated in-depth in the hub already, so the edge refusal is defense-in-depth — **but the contract requires it AT THE EDGE** (Q4), so it is a hard gate, not optional.

---

## 6. Auth preservation per hop (forward, never re-implement)

- **Kratos cookie (CYP-515/Seam-4).** Same-origin so `ory_kratos_session` (httpOnly) is set + auto-sent; forward `Cookie`/`X-Session-Token` verbatim (`Principal.kt:72-75`) → hub whoami (`PlatformWiring.kt:433-435`). The full self-service proxy (§4/R4) is what establishes + refreshes that cookie.
- **REST.** Forward cookie + optional `Authorization: Bearer <operatorToken>`.
- **WS.** `?token` for read/write; **cookie-only for `/ws/agent`** (`AgentSocket.kt:62-70`, CYP-230); **`?ticket`** at the 6 read-sockets (`wsReaderOrNull`, `Auth.kt:180-186`, CYP-286). **Never strip the cookie on the WSS upgrade** (same-origin WS sends it automatically).
- **★ `/ws/terminal` — FIRST-CUT (Auftraggeber-ratified).** Operator-only, WRITE-tier PTY code-exec (`TerminalSocket.kt:68`). Over the A2 process hop the design MUST preserve: (a) the operator gate (no downgrade by termination); (b) **kill-on-revoke** — the mid-session `close(1008, "grant_revoked")` (`TerminalSocket.kt:126`) propagates through the gateway and tears the browser socket promptly. This is the highest-risk path and is now in from day 1 → its cleartext PTY bytes are inside the §8 boundary and covered by the S6 hardening.

---

## 7. Reconnect & close-code fidelity

`?since` passed through untouched (`AgentSocket.kt:132-134`; `SubscribeEvents` frame `EventSocket.kt:101-108`; REST `CommRoutes.kt:178,198`). Close-1008 `VIOLATED_POLICY` propagated **unmasked** (`EventSocket.kt:49`, `LifecycleRoutes.kt:71,98,117,137`, `CommRoutes.kt:248`, `AgentSocket.kt:110`, terminal grant-revoke `TerminalSocket.kt:126`) — drives the offline/revoked banner (precedent: bridge relays inner-1008 as clean EOF, `LoopbackBridge.kt:105,117-118`). Transparent frame relay; no reconnect masking. Same-origin default; `CYPPIE_API_BASE`/`CYPPIE_WS_BASE` cross-origin override + CORS supported but same-origin strongly preferred.

---

## 8. ★★ Trust boundary — RATIFIED (Auftraggeber, at the design gate)

**The fact.** The Noise/mux tunnel **terminates in the gateway process**; between decryption (`NoiseJavaServerTerminator`→`Rr3TunnelGate`) and the loopback socket (`RealBridgeSocket.write/read`, `LoopbackBridge.kt:184-189`), **cleartext operator↔hub HTTP/WS is visible in the gateway process** — the operator session cookie, any operator bearer in transit, all agent traffic, **and PTY-exec bytes on `/ws/terminal`** (in first-cut, ratified).

**Ratified posture (A2):** the cleartext lives in a **dedicated, isolated, auditable gateway process** — not shared with the hub. This is the stronger boundary the Auftraggeber chose over A1.

**First-cut mandatory hardening at this boundary (S6 — no longer deferable):**
- **No logging** of tokens / cookies / PTY bytes / handshake material at the cleartext point (precedent CYP-190; the CYP-607 diagnostic logging `Auth.kt:169-219` is temporary and must not ship in the gateway path).
- **Memory hygiene** — no cleartext spill to disk/swap/core; bounded buffers (the bridge's ≤1-CHUNK / ≤8-frame windows, `LoopbackBridge`/`ClientLoopbackBridge`).
- **Auth never downgraded** across the hop (§6), especially `/ws/terminal`.
- **Allowlist never leaks** a control-surface path (§5).
- **Audit hooks** — the gateway process is the one place to audit the operator↔hub boundary; expose structured, secret-free audit of allow/deny + terminate events.

Standing mitigations (unchanged): loopback = zero implicit trust, hub re-verifies the real credential (`LoopbackBridge.kt:36-39`); T3 loopback-only fail-closed (`:64`); God-token guard on the tunnel port (`TunnelGodTokenGuard.kt:53-68`).

---

## 9. Reuse vs new-build

**Reuse:** tunnel terminator + `Rr3TunnelGate` (`RemoteRelayWiring.kt:95-112`); `LoopbackBridge`/`MuxBridge` loopback pattern; the separate-process deployable shape (`RelayServer.kt` / `:server:relayRun`); `WsOriginGuard`, `installRestrictedCors`, `installTunnelGodTokenGuard`; `wsReaderOrNull`/`resolvePrincipal` credential model; **`RestContract.REST_OPS` + `ContractGenerator` AsyncAPI** as the two allowlist sources; the already-unmasked `?since`/close-1008 plumbing.

**New:** the **isolated gateway process** + its browser-facing plain listener + same-origin SPA serving; the **path-aware allowlist filter**; the **full Kratos self-service same-origin proxy**; verbatim credential forwarding **across the process boundary**; the S6 cleartext-boundary hardening covering PTY.

---

## 10. Remaining open questions (post-ratification)

1. **TLS termination location** — the gateway owns browser-facing HTTPS/WSS; cert provisioning is deploy-owned (`RelayServer.kt:93`). Confirm the deploy story (self-signed / ACME / operator-provided). *Deploy/Auftraggeber.*
2. **Cross-origin fallback** — if `CYPPIE_API_BASE`/`CYPPIE_WS_BASE` override is used, the CORS + cookie `SameSite`/`Secure` posture (cf. CYP-563). Same-origin is the default + strongly preferred. *Backend/PO.*
3. **Kratos web-ts specifics** — the client (CMP) drives the full self-service surface; po2/Dev5 confirm the web-ts SPA hits the same `/self-service/{flow}` proxy paths (expected). *po2/Dev5.*
4. **Gateway↔hub link in the co-located deploy** — direct loopback vs always-tunnel. (Tunnel reuse is the remote case; a co-located gateway may forward straight to the hub loopback — a build detail, not a boundary change.) *Backend.*

(Resolved at the gate: A1/A2 → **A2**; `/ws/terminal` → **in**; SPA same-origin → **required**; path-aware → **required**.)

---

## 11. Story breakdown (first-cut = S0–S7; `/ws/terminal` S5 + hardening S6 both FIRST-CUT)

| ID | Story | First-cut? | Size |
|---|---|---|---|
| **S0** | **Gateway process scaffold** — new deployable (relay-precedent): browser-facing plain listener, same-origin SPA serving, forward-to-hub over the tunnel/loopback, verbatim credential forwarding across the process boundary. Foundational for A2. | ✅ | L |
| S1 | REST allowlist reverse-proxy, single-sourced from `RestContract`; default-deny; verbatim method/path/body/header forwarding. | ✅ | M |
| S2 | WS upgrade passthrough for the 8 data sockets, **single-sourced from `ContractGenerator`** (R1); query (`token/ticket/since/agentId/projectId`) + cookie preserved on the handshake; `Subscribe`/`SubscribeEvents` transparent. | ✅ | M |
| S3 | **Full** Kratos self-service same-origin proxy (login/registration/recovery/verification/settings/logout, R4) + cookie set/forward + `/api/auth/me` whoami. | ✅ | M |
| S4 | Reconnect/close fidelity: `?since` pass-through + close-1008 unmasked, proven with a real revoke. | ✅ | S |
| **S5** | **`/ws/terminal` operator-gate + kill-on-revoke survival across the A2 hop — FIRST-CUT (ratified, no flag-defer).** | ✅ | M |
| **S6** | **Cleartext-boundary hardening — FIRST-CUT MANDATORY incl. PTY bytes:** no token/cookie/PTY logging, memory hygiene, audit hooks (§8). | ✅ | M |
| S7 | Deploy/config: TLS, same-origin default, `CYPPIE_API_BASE`/`CYPPIE_WS_BASE` + CORS, A2 process packaging/launch. | ✅ | S–M |

Every story ends with a **real proof** (browser/`curl`/`wscat`: allowed path works · refused path 404s **at the edge** · cookie/ticket survives · 1008 propagates · terminal revoke tears the socket) — not a unit pass. The allowlist (S1/S2), the terminal auth survival (S5), and the S6 hardening are the merge-blocking security teeth.

---

## 12. Status / next

§8 **ratified** (A2 + `/ws/terminal` in). This v2 + the S0–S7 breakdown → **PO review** → build. First build target: **S0** (the process scaffold) — everything else forwards through it.
