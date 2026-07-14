# CYP-576 — Desktop GitHub-OIDC Deploy→Verify Runbook

> Owner: Backend. **Post-deploy verification for the CYP-576 native-OIDC (RFC-8252 session-token-exchange) desktop login**,
> against staging `api.cyppie-agents.com` / Hub `hub_c1d6f5ffd892a03d`. Companion to the M1-M2 readiness runbook.
> Every step = a check + **expected result** + fail-closed note. Substitute `<...>` per the staging env.
>
> **Secrets discipline (unchanged, load-bearing):** `session_token`, the exchange code halves (`init_code`/`return_to_code`),
> and the operator token are **secrets** — NEVER paste their VALUES into the channel/logs. Token-gated + host-side checks
> run **deploy/Dev-side** (where the creds legitimately live); they paste the **masked verdict** (HTTP + PASS/FAIL only) and
> the backend agent **arbitrates** against this runbook (Path B). A successful exchange mints a REAL operator session — it
> must not land on the backend-agent box.
>
> **Kratos = 0 change (verified).** All Kratos endpoint/param names below are LIVE-VERIFIED against staging **v1.3.0**.

---

## 0. Preconditions (must already be true)

- The M1-M2 readiness runbook §1–§5 is green (CP/Relay/Kratos up, hub admitted+owned, boot-env-gate live-not-INERT).
- CYP-576 client build merged+deployed: `githubStart()` drives the **api-flow** (not browser-flow); `armLoopbackListener`
  reads the `?code=` query; `onGithubReturn(code)` exchanges → `session_token` → `X-Session-Token` plumbing.
- `allowed_return_urls` contains `http://127.0.0.1:47472/callback` (CYP-562, §4.1 of M1-M2 runbook).

---

## 1. Post-deploy health / boot markers (run first; each fail-closed)

| # | Check | Command / source | Expect | Fail-closed |
|---|---|---|---|---|
| 1.1 | CP health | `curl -fsS <CP>/api/health` | `200`/ok | — |
| 1.2 | Kratos public ready | `curl -fsS <KRATOS_PUBLIC>/health/ready` | `200` | operator can't OIDC |
| 1.3 | Relay health | `curl -fsS http://<RELAY>:<PORT>/health` | `ok` | no tunnel |
| 1.4 | **Hub self-admit LIVE (not INERT)** | boot log | `hub self-admitted … now discoverable` present; `CYP-524 hub admission not granted` / self-admit-INERT WARN **absent** | 502 non-JSON boot race |
| 1.5 | **Remote connector LIVE** | boot log | `CYP-526 dialing relay as role=hub` + `CYP-526 relay responder established — bridging tunnel` (≥1); `CYP-459 … INERT`/`InertRelayConnector` **absent (count 0)** | half-config → no dial |
| 1.6 | **Hub owned at CP** | `curl -fsS -H "Authorization: Bearer <OP>" <CP>/api/cp/hubs` | `hub_c1d6f5ffd892a03d` present, `ownerId == ab7c54e3-…` (owned, not just present) | owner-gate → no dial |
| 1.7 | **Default agents 7/7 byte-identical** | `GET /api/agents` (operator) | the 7 seeded default agents present, ids unchanged (governor roster-floor CYP-442; preserve-safe import default-agents-no-reset) | roster drift = boot regression |
| 1.8 | CSRF Secure over TLS | `curl -sI <CP>/api/health \| grep -i set-cookie` | `cyppie_csrf=…; Secure; SameSite=Strict` | — |
| 1.9 | No-auth fail-closed | `curl -s -o /dev/null -w '%{http_code}' <CP>/api/cp/hubs` | `401` | — |

---

## 2. OIDC-contract harness hops (Tester `github-oidc-verify.sh`) — the api-flow legs

> Defines what the harness asserts; order + expected status. All against `<KRATOS_PUBLIC> = <CP>/.ory/kratos/public`.
> **Hops 1-2 + 4 are LIVE-VERIFIED read-only (2026-07-14).** Hop 3 (the browser callback→loopback) needs the real login (§3).

| Hop | Request | Expect |
|---|---|---|
| 1 | `GET <KRATOS_PUBLIC>/self-service/login/api?return_session_token_exchange_code=true&return_to=http://127.0.0.1:47472/callback` | `200` + body field **`session_token_exchange_code`** (64ch) + `return_to` echoed |
| 2 | `POST <KRATOS_PUBLIC>/self-service/login?flow=<id>` · JSON `{"method":"oidc","provider":"github"}` (api-flow → **no csrf**) | **`422`** + top-level **`redirect_browser_to`** = `https://github.com/login/oauth/authorize…` + `error.id=browser_location_change_required` |
| 3 | *(browser)* GitHub consent → `<KRATOS_PUBLIC>/self-service/methods/oidc/callback/github` | `302` → `return_to` loopback with **`?code=<return_to_code>`** (+ `&state=` if the client pins one) |
| 4 | `GET <KRATOS_PUBLIC>/sessions/token-exchange?init_code=<…>&return_to_code=<…>` | pre-completion: `404` "no session yet for this code"; **post-completion: `200` `{ session_token, session }`** |

**Fail-closed:** missing `return_to_code` on hop 4 → `400`. Hop 2 with a bad provider → the flow re-renders with an error node, never a redirect.

---

## 3. The real GitHub round-trip (human — the end-to-end proof)

The human runs the desktop client and signs in with GitHub. Server-observable chain (each must hold in order):

1. Client → hop 1 (api-flow init, keeps `session_token_exchange_code` **app-private**) → hop 2 (`redirect_browser_to`) → **system browser opens** (CYP-575 launch-fallback: URL to stdout + `xdg-open`).
2. Browser: GitHub consent → Kratos callback → **`302` to `127.0.0.1:47472/callback?code=…`**. → loopback listener fires (single-shot).
3. Client → hop 4 exchange → **`200` `session_token`** → stored, sent as **`X-Session-Token`** (header only — see §5).
4. `GET <KRATOS_PUBLIC>/sessions/whoami` (X-Session-Token) → `200`, identity = **`ab7c54e3-2f27-46f4-ab5e-0b881abab07d`**.
5. `ab7c54e3` resolves to **OPERATOR** (RoleStore/`bootstrapOperatorIdentityId` pin — confirm deploy §6/#2b).
6. `GET /api/cp/hubs` (now operator) → lists **`hub_c1d6f5ffd892a03d`** owned by `ab7c54e3` → **the Hub appears in the desktop UI**. ✅ = e2e green.

**Fail-closed diagnostics:** whoami `500` (not 401) → both-creds poison (§5). whoami `200` but hub absent → `ab7c54e3` is MEMBER not OPERATOR (§6/#2b). No loopback fire → `?code=` leg / `return_to` allowlist.

---

## 4. F1 security live-test — init∧return_to pairing (a) + single-use (b)  [Dev/deploy-side]

Run **`scratchpad/cyp576_f1_ab_test.sh`** with the real codes from 2–3 completed logins. **Order: (a)-mixed FIRST** (must
reject, must not consume), **then (b)** single-use. **Runs Dev/deploy-side** (a successful exchange mints a real operator
session_token → must not touch the backend box); pastes the **masked** verdict; backend arbitrates.

| Check | Exchange | Expect | On failure |
|---|---|---|---|
| F1(a) | `(init_A, return_B)` and `(init_B, return_A)` | **rejected, HASTOKEN=0** | **MINTED a session → account-takeover → STOP/REDESIGN, escalate immediately** |
| F1(b)#1 | `(init_C, return_C)` (or reuse A) | `200`, HASTOKEN=1 (legit) | codes/order wrong |
| F1(b)#2 | replay `(init_C, return_C)` | **rejected, HASTOKEN=0** (single-use) | **replay minted 2nd session → STOP/REDESIGN** |
| F1(c) | any code after >10 min | rejected | (TTL read-only-verified = 10 min flow lifespan) |

**Primary defense is independent of F1(a):** the `init_code` is app-private (never touches browser/GitHub/loopback) +
the client `state`-nonce is verified at the loopback → takeover needs Kratos-pairing-break **AND** the private init_code.
F1(a)/(b) confirm the Kratos leg; the client defenses are the belt-and-suspenders.

---

## 5. both-creds → 500 check (v1.3.0-specific)

Kratos v1.3.0 whoami **500s if `X-Session-Token` header AND `ory_kratos_session` cookie both arrive** (`IdentityProvider.kt:10`, `KratosIdentityProvider.kt:24`).

| Check | How | Expect |
|---|---|---|
| 5.1 | `GET <KRATOS_PUBLIC>/sessions/whoami -H "X-Session-Token: <tok>"` (NO cookie) | `200` (not `500`) |
| 5.2 | App sends header-only | on the OIDC switch the app expired stale Kratos-host cookies from its Ktor jar (pattern: `HttpAuthRepository:282`) → whoami carries ONLY the header | 
| 5.3 | Backend needs nothing | `sessionCredential()` (`Principal.kt:73`) prioritizes the header; the 500 is an app→Kratos edge, not a backend defect | — |

---

## 6. Pre-demo checklist — OPERATOR_TOKEN non-exposure (Backend2 caveat)

The static write-tier `OPERATOR_TOKEN` must never surface. Re-confirm before the demo:

| # | Check | Expect |
|---|---|---|
| 6.1 | `GET /api/config/apikey` (participant) | `{ set:true, masked:"…last4" }` — never the raw key |
| 6.2 | Static operator token over a **tunnel** auth channel | `401` (god-token-per-tunnel; static token refused on the tunnel connector; `200` only on the public connector — port-scoped) |
| 6.3 | Log scan | `grep -RiE 'ory_st_\|ANTHROPIC\|OPERATOR_TOKEN=\|BEGIN .*PRIVATE KEY\|session_token' <LOGDIR>` → **empty** |
| 6.4 | `ab7c54e3` = OPERATOR pin | `bootstrapOperatorIdentityId == ab7c54e3` (or an explicit RoleStore OPERATOR assignment) — else authed-but-MEMBER → hub invisible (#2b) |

---

## Appendix — fail-closed quick reference

- Hop 2 no `redirect_browser_to` → github provider mis-wired (check `SELFSERVICE_METHODS_OIDC_CONFIG_PROVIDERS` env).
- Loopback never fires → `?code=` leg / `return_to` allowlist / browser launch (CYP-575).
- whoami `500` → both-creds poison (§5); `401` → token invalid/expired; `200`-but-no-hub → not OPERATOR (§6.4).
- F1(a) mints a session → **STOP** — do not demo; client `state`-nonce + init_code privacy become mandatory, escalate.
- F2 (codes in access log): Kratos redacts (`leak_sensitive_values:false`); deploy strips the Caddy-forward query for `/sessions/token-exchange`.
