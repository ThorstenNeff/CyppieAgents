# CYP-576 — Desktop OIDC Auth Flow: As-Shipped Security Posture

> **Status:** SHIPPED to `develop` — plumbing `9e67f2ca` + follow-on `d06001a6` (2026-07-15).
> **Author:** Reviewer (adversarial security review, F1/F2 interception+handling axis).
> **Audience:** Auftraggeber + the future **public-flip** decision. This is the security memory of the
> deliverable **and** the gate condition that must not be forgotten.
> **Companion gates:** CYP-532 / S1b (the existing public-flip gate for live-Kratos changes).

---

## 1. What shipped

Desktop GitHub OIDC login moved from a **browser self-service flow driven by the app client** (which
orphaned the `ory_kratos_continuity` cookie in the app jar → Kratos 400, and left the session as a
cookie in the *system browser* the desktop can't read) to the **canonical Ory native API flow with
session-token-exchange** (RFC 8252):

1. `HttpAuthRepository.githubStart(state)` → `GET /self-service/login/api?return_session_token_exchange_code=true&return_to=http://127.0.0.1:47472/callback?state=<nonce>`, then submits `oidc/github`. Keeps two things from the response: `redirect_browser_to` (→ opened in the system browser) and `session_token_exchange_code` (the **init half**, app-private).
2. The desktop host (`main.kt armLoopbackListener`) runs a **single-shot loopback HTTP server** on `127.0.0.1:47472`; after the browser completes the GitHub round-trip, Kratos redirects there with `?code=<return_to_code>&state=<nonce>` (and `?error=` on cancel).
3. `AuthViewModel.onGithubReturn(code, state, error)` verifies the `state` nonce, then `HttpAuthRepository.githubTokenExchange(init, code)` → `GET /sessions/token-exchange?init_code=<init>&return_to_code=<code>` → native `session_token` → stored in the existing `X-Session-Token` plumbing → `session()` resolves the real auth state through the **normal verified-gate**.

**No Kratos/Backend code change** — the same GitHub provider serves the API and browser flows; the
loopback `return_to` is already allowlisted.

The security of the handoff rests on a **two-code split** (init half, app-private, never leaves the
process ∧ return_to half, from the callback — both required to exchange) plus a **client-side
`state`-nonce** (128-bit CSPRNG) that the callback must echo.

---

## 2. Covered / fail-closed (the green posture)

All verified adversarially against the shipped code (`9e67f2ca` + `d06001a6`), not on author's word.

| # | Property | How it's enforced (seam) |
|---|----------|--------------------------|
| G1 | **`state`-nonce enforcement, one-shot** | `AuthViewModel.onGithubReturn`: rejects wrong / missing state / no-pending-attempt / bind-failure-`(null,null)` **before** any auth path; `clearPendingOidc()` runs on **both match and mismatch** ⇒ ≤1 guess per flow against a nonce that then changes. Non-constant-time `!=` is **not** exploitable given one-shot + fresh-per-attempt. |
| G2 | **Security ordering** | The `state`-check runs **first**; the `?error=` branch and the token-exchange run **only** for a state-valid callback ⇒ a forged callback cannot even reach the cancel-UI or the exchange. |
| G3 | **`init_code` app-private** | Parsed from the flow-init response body; held in `pendingExchangeInitCode`; used **only** in the exchange GET — never in `return_to` (only `state` goes there), never sent to the browser/GitHub; **nulled** after use (`clearPendingOidc`). |
| G4 | **Exchange fail-closed** | `githubTokenExchange` wrapped in `failClosed(SessionState.None)`; `parseKratosSessionToken(...).ifBlank { null }` ⇒ no / blank / malformed token → `None` → `Error`. **Never fabricates a session.** |
| G5 | **Verified-gate preserved** | Exchange yields a **token, not an authorization** — `session()` re-reads whoami → `Verified` / `Unverified` (S2 "verify your email" gate) / `None`. An OIDC identity with `verified=false` is **not** one-click access. Not short-circuited on a non-null token. |
| G6 | **`clearKratosCookies` at both poison points** | Before flow-init (a prior broken browser-flow attempt may have left stale cookies) **and** before the post-exchange whoami (Kratos v1.3.0: `X-Session-Token` + a stale `ory_kratos_session` cookie both reaching whoami = 500). The native token is the sole credential. |
| G7 | **`session_token` handling** | In the exchange **response body** (not the URL query → not URL-logged); stored in the existing `X-Session-Token`/`sessionStore` plumbing; never rendered/logged (honesty contract). |
| G8 | **F2 — query-string log leak CLOSED (ops)** | The exchange sends `init_code`+`return_to_code` as query params (the Ory-documented endpoint shape). Mitigated at deploy: **Caddy redacts** `init_code`/`return_to_code` in access logs + **Kratos `leak_sensitive_values: false`**. Confirmed pre-deploy. |
| G9 | **Retry robustness (C1) FIXED** | `armLoopbackListener` returns a **stop-handle**; `AuthViewModel` registers it (`setLoopbackStopper`) and `clearPendingOidc()` invokes `server.stop(0)` on **every** abandon path (timeout / error / cancel / retry / `onCleared`), so port 47472 is freed and a retry re-binds cleanly. |
| G10 | **Cancel ≠ error (C3) FIXED** | `?error=access_denied` → neutral `Cancelled` (no alarm); any other `error` → `Error`. |
| G11 | **Double-launch guard** | An in-flight attempt disables the button (`isBusy`/`Starting`); `clearPendingOidc()` stops any prior attempt's loopback before re-arming ⇒ no two colliding flows/listeners. |
| G12 | **Backend2 fail-open axis** | Re-audited clean (separate axis, po2-verified). |

---

## 3. THE HARD GATE CONDITION (must not be forgotten)

> **F1(a) + F1(b) — Kratos `session_token_exchange` pairing + single-use — MUST be confirmed live-green
> BEFORE any step beyond the controlled dogfood circle (public / multi-user / untrusted hosts).**
> This is a **hard precondition for the public flip, parallel to CYP-532 / S1b.**

**Why (necessary-but-not-sufficient — the client `state`-nonce does *not* make this redundant):**

The threat model splits in two:

- **Account-takeover *of the victim*** (attacker logs in *as* the victim): **fully covered by
  init-privacy, regardless of Kratos.** A takeover needs `victim_init`, which never leaves the app
  process. The `state`-nonce and G3 carry this completely. ✔ Covered today.

- **Login-CSRF** (the victim is logged into the *attacker's* account → the victim's actions/data land
  in the attacker's account): here the `state`-nonce is **only a layer, not a substitute** for
  server-side pairing. **The nonce is not secret from a local callback-observer** — it travels
  `app → Kratos (return_to) → browser redirect → loopback`, so it appears in the callback URL /
  browser history. A local attacker in the exact loopback-interception threat model can **observe**
  the victim's nonce and race `(attacker_code ∧ victim_state)` into the victim's loopback before the
  real callback; the victim's app then exchanges `(victim_init ∧ attacker_code)`, at which point
  **Kratos F1(a) pairing is the last line of defense.** If Kratos pairs the two halves loosely,
  login-CSRF is open.

**General lesson (durable):** a client-side CSRF/echo nonce that *travels through the redirect chain*
defends the **secret-you-hold** threat (init-privacy → victim-takeover) but **not** the
**secret-you-echo** threat (login-CSRF) against a local observer. The server-side pairing check
remains the **non-redundant backstop** for login-CSRF. Do not accept "client nonce ⇒ server pairing
check redundant."

**Why dogfood is nonetheless green:** the login-CSRF residual requires a *local* attacker + observing
the fresh per-attempt nonce + a valid `attacker_code` + winning the **one-shot** race to the loopback,
on a **single controlled host**. That is a razor-thin local window — acceptable for the dogfood,
unacceptable to ship to untrusted multi-user hosts without F1(a)/(b) confirmed.

---

## 4. To verify (live, 2026-07-15)

| Item | What | Owner | Blocks |
|------|------|-------|--------|
| F1(a) | Kratos rejects a **mismatched** `init_code`/`return_to_code` pair | Backend `cyp576_f1_ab_test.sh` at first real login | **Public flip** (not dogfood) |
| F1(b) | `return_to_code` is **single-use** (no replay → 2nd token) | Backend `cyp576_f1_ab_test.sh` | **Public flip** (not dogfood) |
| F1(c) | Exchange-code **short expiry** | ✅ read-only confirmed (~10 min) | — |
| 4th leg | Kratos preserves `state` inside `return_to` and appends `&code=` (echoed to the loopback intact) | Live login run | Correctness (login works) |

On a green `cyp576_f1_ab_test.sh` (F1(a)+(b)), the public-flip precondition for CYP-576 is cleared
(still subject to CYP-532/S1b for any live-Kratos change).

---

## 5. Accepted residuals (dogfood scope)

- **C2 — cross-thread `pending*` access (LOW, fail-closed only):** `onGithubReturn` runs on the
  HttpServer callback thread; `pendingState` / `pendingExchangeInitCode` / `handoffTimeoutJob` are
  written on the VM (Main) coroutine without synchronization. Worst case is a **stale read → spurious
  fail-closed rejection** (an attacker cannot forge a matching state ⇒ never a false-accept).
  Hardening rec: `@Volatile` on those fields or marshal `onGithubReturn` onto `runScope`. Non-blocking.
- **Loopback cleartext on a shared host (residual):** the `?code=` transits loopback in cleartext; a
  co-tenant with elevated privileges could observe it — but it is only the **return half**, useless
  without the app-private `init_code`. Loopback (RFC 8252) rather than a platform-authenticated deep
  link is the weaker-but-accepted native redirect for desktop.

---

## 6. Posture statement

For the **controlled dogfood** (single trusted host, small circle): **GREEN.** The auth-granting path
is fail-closed and gated behind the `state`-nonce; victim-takeover is fully covered by init-privacy;
the query-log leak is closed at the proxy; retry/cancel robustness shipped.

For **public / multi-user / untrusted-host rollout**: **BLOCKED until F1(a)/(b) are confirmed
live-green** (the login-CSRF backstop), parallel to CYP-532 / S1b. This condition is anchored here and
in the merge commit; it is the one line that must survive to the public-flip call.
