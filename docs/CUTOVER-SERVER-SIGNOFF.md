# `:server` Cutover Sign-off

> **Quadrant 4 of the web-ts cutover gate.** The other three quadrants are auditable documents
> (Reviewer security-ledger · Tester parity-plan · UIUX ux-map). This file makes the `:server`
> readiness auditable too — the evidence was previously spread across CYP-31/286/297/409/421/452/462/466/487.
> Each precondition below is stated as **claim + merge-ref + one proof line** so a reviewer can verify
> the commit and the test, not "trust the commits".
>
> **Baseline:** `develop @ d9a94daa` (Merge CYP-486). **Verified:** 2026-07-12 (design/audit, no build).
> **Verdict:** `:server` is **READY** for the tokenless-browser (Kratos-cookie SPA) cutover, with **one
> merge pending** — see §3.

---

## 1. Server preconditions met

Every frontend read is **session-aware** (accepts a verified Kratos-cookie human, not token-only), the
browser has a complete WS-auth path, the contract the client generates against is drift-free, and no
secret leaks in a read response. Refs are the **merge commit** on `develop` unless noted.

| # | Precondition | Merge / ref | Proof line |
|---|---|---|---|
| 1 | **Auth-tier coherence** — all frontend reads resolve a verified human session (`requireCommReader`), not token-only. | rolled-up CYP-320 class; **last landmine = CYP-487 IN REVIEW** (`7158df19`, §3) | `MemberTier403MatrixTest` walks the live tree: every operator route 403s a MEMBER; `requireParticipant` audit shows its **sole** remaining live route caller is `ServerNowRoutes` until CYP-487 lands. |
| 2 | **CSWSH / WS-origin guard** — WS handshakes are checked against an explicit origin allowlist (defense-in-depth over CORS, which no-ops on an empty allowlist). | CYP-31 `5fa93767` | `routing/WsOriginGuard.kt`; `Cyp31WsOriginGuardTest` (7/0) holds the guard across every `/ws/*` route. |
| 3 | **Browser WS auth complete** — read sockets take a short-lived single-use `?ticket=`; `/ws/agent` takes the same-origin Kratos cookie; `/ws/terminal` stays operator-token (desktop, out of the browser cutover). | CYP-286 `817a674c` · CYP-454 `d0144f47` | Ticket is minted bound to the caller's own `requireCommReader` subject and consumed only at `wsReaderOrNull` (`Cyp286WsTicketRoutesTest` binding + drive-WS negative control); `/ws/agent` `tokenAuthorize` cookie branch. No long-lived `?token=` needed by the browser. |
| 4 | **`resolveEventScope` fail-closed, member-correct** — a member with no explicit project set sees the active-project metadata, never a silent empty. | CYP-452 `f618a56b` | `routing/EventScope.kt:24` — `resolveEventScope(requested=null, active, authorized=∅) = active`; content-free metadata, ACL-filtered downstream. |
| 5 | **No secret in a read response** — API keys render masked (`***`+last-4, `****` floor under min-length, `unset` when empty); never logged, never round-tripped. | CYP-96 / D3 masking | `boot/Secrets.kt:49 mask()`; `contract/NoSecretInReadResponseTest` sweeps read responses for plaintext secrets. |
| 6 | **CONTRACT drift-free + export current** — live `/api` routing ↔ `RestContract.REST_OPS` match both directions; the committed `web-ts/contract/openapi.json` + `asyncapi.json` equal a fresh export. | CYP-409 `e85223f8` · CYP-426 `a81e24f2` | `RestContractDriftTest` (2/0, bidirectional — no silent add/drop); `ContractExportDriftTest` (2/0 — committed export == regenerated). The type surface the client generates against is complete and current. |
| 7 | **Error-reason display data** — `AgentErrorCode` is on the `/ws/agent` event surface in the contract, so the client's ERROR-reason line (CYP-446) has its field. | CYP-421 `8816d406` | `AsyncApiContractTest` — the WS frame surface is coherent and includes the typed error code. |
| 8 | **whoami** — `GET /api/auth/me` returns session → role + verified; sufficient for the client's role/verified scope. | P2-i (present pre-baseline) | Session-aware read; on the `MemberTier403MatrixTest` member-allowlist (a MEMBER reaches it 200, not tier-denied). |
| 9 | **Connector catalog** — `GET /api/connectors` is a read-tier, static, secret-free, tenant-free fidelity preview (kinds + declared capabilities); the connector *mutation* stays operator-only. | CYP-462 `6ab2dccf` | `ConnectorCatalogRoutesTest` (caps single-sourced from `ConnectorRouter.capabilitiesForKind`); gated `requireCommReader` so the tokenless picker renders. |
| 10 | **Reprovision-preview** — `GET /api/config/repo/reprovision-preview` gives an honest operator-tier discard-confirm (live per-agent at-risk work, computed on demand, never stashed). | CYP-466 `555600d9` | `ReprovisionPreviewRoutesTest` + `WorktreeManagerUnpushedWorkTest` (real `git status --porcelain` / `log --not --remotes` teeth); operator-tier → MEMBER correctly 403 (not on the allowlist). |
| 11 | **Participant-token write-escalation closed** — read subjects are namespaced (operator / bare-agentId / `participant:x` / human-identityId can't collide); a participant token is READ-tier at the write gate. | CYP-297 `7f79bab2` | Namespace + mint guard + tier=READ enforcement at the send gate. |
| 12 | **INERT terminal-delegation flag is OFF** — member terminal delegation is post-MVP; mounted-but-403 when the flag is disabled, so the contract is honest without exposing the path. | CYP-421c `8816d406` | `CYPPIE_TERMINAL_DELEGATION_ENABLED` defaults off; route mounted, returns 403 when disabled → contract-honest, not a cutover blocker. |
| 13 | **No-credential net** — no `/api` route is unauthenticated by accident. | (standing) | `ProtectedRouteEnumerationTest` (2/0) enumerates the live tree; any accidentally-public route reds it. |
| 14 | **Discard telemetry** — dropped-event count is surfaced server-wide, summed by discarded-event delta (observability for the client's event-log UI). | CYP-364+353 `bf43cd13` | `ReportGenerator`/`log.dropped` server-wide; summed by discarded-event delta, not report-count. |

**Boot-wiring** (code-read on `d9a94daa`): all Phase-2 routes register inside the single unconditional
`routing{}` block of `installPlatform` — `serverNowRoutes` / `connectorCatalogRoutes` / `reprovisionPreviewRoutes`
/ `authMeRoutes` — no missing registration, no dead handler; only `terminalGrantsAdmin` (CYP-421c) is
flag-conditional (inert-but-mounted). Corroborated by `RestContractDriftTest` (bidirectional, green).

---

## 2. Deploy-owned (open — not `:server` code)

These are cutover preconditions the `:server` module does **not** own; they live in the edge / reverse-proxy /
identity-provider / consumer-CI layers. Listed so the gate is complete, and to make explicit that their absence
is **not** a `:server` gap. Each has a **post-deploy verification gate in §4** — the acceptance criterion that
must pass at cutover, so "deploy-owned" is a checked gate, not a trust gap.

| Item | Owner layer | Note |
|---|---|---|
| **CSP header + per-response nonce stamp** | reverse-proxy / edge | Content-Security-Policy with a per-response nonce; `:server` emits no CSP header. |
| **`CONTRACT_REQUIRE_REAL=1` wiring** | web-ts consumer CI (Dev5) | Consumer-side fail-closed flag: a missing real export becomes fatal (exit 1) instead of silently falling back to the fixture. Evidence: `web-ts/scripts/generate-contract-types.mjs`, `web-ts/contract/README.md`. Not `:server` code. |
| **Same-origin reverse-proxy** | deploy topology | Serves the SPA and `:server` under one origin so the Kratos cookie and same-origin `/ws/agent` work and `?token=` is stripped from queries at the edge. |
| **Kratos HttpOnly session cookie** | Ory Kratos config | The browser identity the session-aware gates resolve; `HttpOnly`/`Secure`/`SameSite` are IdP/deploy config, not `:server`. |
| **`X-Content-Type-Options: nosniff`** | reverse-proxy / edge | The `:server` half is proven — every read sets a correct explicit Content-Type (JSON reads `application/json`, avatars `image/png`, no user-data HTML; see the XSS-sink second-axis note). `nosniff` is the edge belt that forecloses content-type sniffing; not `:server` code. |

---

## 3. Status

- **`:server` verdict: READY** for the tokenless-browser cutover, verified on `develop @ d9a94daa`.
- **One merge pending — CYP-487** (`c6c9cf69`, rebased onto the current develop tip past CYP-484/482M; was
  `7158df19`), `GET /api/server-now`: `requireParticipant` → `requireCommReader`. It is the last CYP-320-class
  landmine: the *sole* frontend read still on a token-only gate, so a cookie-SPA would 401 when CYP-346 wires it.
  Fix is mutation-verified (session→200 / bearer→200 / no-cred→401; tier stays PARTICIPANT so no contract change)
  and in PO1's re-gate. Merging it makes row 1 unconditional.
- Everything else `:server`-side is cutover-ready; the rest is deploy-owned (§2), each with a §4 verification gate.

---

## 4. Deploy-gate verification checklist (Team-2 acceptance criteria)

Turns each §2 deploy-owned item into a **verifiable gate**: a concrete post-deploy probe and its pass condition.
**Scope = what Team-2 must be able to verify at cutover** (the acceptance criteria) — **not** the infra config that
satisfies it (PO1/Deploy owns the *how*). Run these against the deployed origin before declaring the cutover done.
Where the `:server` half is already proven it is noted; the probe verifies the **edge/deploy half**.

| # | Deploy-owned item | Post-deploy probe | Pass condition |
|---|---|---|---|
| 1 | **CSP + per-response nonce** | `curl -sI https://<origin>/` — inspect the `Content-Security-Policy` header on two separate responses | Header present; `script-src` carries a `'nonce-<b64>'`; the nonce **differs** between the two responses (per-response, not static); `'unsafe-inline'` **absent** from `script-src`; the served inline `<script nonce=…>` matches the header nonce. |
| 2 | **`CONTRACT_REQUIRE_REAL=1`** | Inspect the release/CI env; negative-control run with the real `:core` export absent | `CONTRACT_REQUIRE_REAL=1` set in the CI/release env; `contract:gen` **exits 1** (build red) when `contract/asyncapi.json` is missing — never silently falls back to the fixture. Anchor: `web-ts/scripts/generate-contract-types.mjs` (CYP-400). |
| 3 | **Same-origin reverse-proxy** | `curl -sI https://<origin>/api/auth/me` vs the SPA index; browser Network panel on `/api`+`/ws`; a request carrying `?token=` to a WS route | SPA + `/api` + `/ws` share one `scheme://host:port`; **no CORS preflight** on same-origin calls; `?token=` is **stripped at the edge** (absent from backend access logs). `:server` half proven: the WS-origin allowlist guard (CYP-31). |
| 4 | **Kratos httpOnly cookie via the browser-flow login** | Perform a same-origin login POST to `/self-service/login/browser` with `Accept: application/json`; inspect the response `Set-Cookie` (and browser → Application → Cookies) | The successful login **natively** returns `Set-Cookie: ory_kratos_session=…; HttpOnly` **and** `Secure` **and** `SameSite=Lax\|Strict`, and the response body carries **no** `session_token` — confirming the cookie-setting browser flow, not the cookieless `/self-service/login/api` flow (the Seam #4 / CYP-515-class regression: an API-flow body token leaves web-ts Tooth ② with no cookie → whoami unauth → nobody can log in). `document.cookie` does **not** reveal the session cookie. |
| 5 | **`X-Content-Type-Options: nosniff`** | `curl -sI` a JSON read (`/api/agents`), the avatar endpoint, and the SPA index | Each response carries `X-Content-Type-Options: nosniff`. `:server` half proven: every read sets a correct explicit Content-Type (JSON `application/json`, avatars `image/png`, no user-data HTML — the XSS-sink second-axis note); `nosniff` is the edge belt. |

**Outcome:** all five green → "deploy-owned" is a **passed gate**, not a trust gap. A red on any row blocks the
cutover with a concrete, named failure — closing the "sign-off HAVE ≠ ENFORCED" gap.

---

**Companion quadrants:** Reviewer security-ledger · Tester parity-plan · UIUX ux-map · **this** `:server` sign-off.
