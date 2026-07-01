# CYP-183 / P4.3 — OIDC "Sign in with GitHub" spike runbook

> **Purpose:** settle four unknowns against **live Kratos v1.3.0** BEFORE any OIDC UX claim (the RC2 pattern).
> **S1 (linking-takeover) is a HARD GATE — not documentable.** If v1.3.0 is unsafe, **OIDC is BLOCKED** (fix
> via config / v26) — it does NOT ship "accept + documented". S2–S4 are graded (S3 → §D if unreachable).
> Deploy-coordinated: needs dev-Kratos with the P4.1 config, `GITHUB_CLIENT_SECRET` on the Kratos box, the
> callback URL registered in the GitHub App, and a **test GitHub account with a controllable email**.

## Prereqs

1. dev-Kratos running the P4.1 config (`oidc` provider `github`, mapper jsonnet) + `GITHUB_CLIENT_SECRET` env.
2. GitHub App *Authorization callback URL* = `<proxy-origin>/.ory/kratos/public/self-service/methods/oidc/callback/github`.
3. A **test GitHub account** whose primary email can be toggled verified/unverified (the attacker role).
4. A **victim** password identity in Kratos with email `v@x` (created via the P2 register flow).

## ⭐ S1 — linking-takeover (HARD GATE)

**Attack:** the attacker's GitHub account primary email = the victim's `v@x`, **UNVERIFIED**; attacker clicks
"Sign in with GitHub". If Kratos auto-links that GitHub credential to the victim's `v@x` password identity
(or marks `v@x` verified from the unverified GitHub email), the attacker owns the victim's account.

| Step | Expected SAFE (GO) | UNSAFE (BLOCK) |
|---|---|---|
| **S1a** attacker GitHub email `v@x` **UNVERIFIED** → Sign in with GitHub | Kratos **refuses to use the unverified email** — no identity created/linked with `v@x`, `v@x` NOT marked verified (the identity stays the victim's, untouched) | Kratos **auto-links** the GitHub cred to the `v@x` identity, and/or marks `v@x` verified → **takeover** |
| **S1b** attacker GitHub email `v@x` **VERIFIED** + `v@x` already exists → Sign in | Kratos requires an **ownership proof** (password / linking confirmation) BEFORE linking — no silent auto-link | silent auto-link with no proof |

**Evidence to capture (both steps):** the raw Kratos flow outcome (status + body), whether a new
credential was linked to the victim identity (admin API `GET /admin/identities/{id}` — credential list),
and the victim's `verifiable_addresses[].verified` before/after. **GO only if BOTH S1a and S1b are SAFE.**
Any unsafe result → report to PO → **OIDC BLOCKED** until closed (config knob or v26 re-spike).

## S2 — verified-mapping (graded; feeds the P1 guard)

**New** GitHub user (email NOT in Kratos), GitHub email **verified** → Sign in with GitHub.
- **Capture:** after the flow, call `GET /api/auth/me` for that session → `verified`?
- **auto-verified (`verified:true`)** → the P1 guard admits immediately (best UX, no code).
- **not auto-verified (`verified:false`)** → the user completes Kratos email verification → the
  verified-flip→guard linkage (P2.3) admits them; the client shows "verify your email" (P3). Acceptable — no block.

## S3 — linking-enum (graded → §D if config-unreachable, parallel §B)

Sign in with GitHub for an email that **already exists** vs a **new** email; diff the OIDC/linking responses.
- **No existence tell** → closed.
- **Reveals "email already registered"** → an enumeration vector. Assess config-reachability (like §B); if
  unreachable on v1.3.0 → **§D graded limit** (CYP-179, re-eval at v26). Weaker than §B: the attacker must
  control a GitHub account that GitHub verified for the victim's email (S1 already blocks the unverified path).

## S4 — state/nonce/PKCE/callback delegation (automated)

`OidcSpikeTest` (RUN-gated) initiates a native OIDC login flow (`{method:oidc, provider:github}`) and asserts
Kratos returns a redirect to `github.com/login/oauth/authorize` carrying **`state`** and a **`code_challenge`**
(PKCE) — proving Kratos generates + owns the OAuth security params (the platform re-implements none, §S5).
Run on the Kratos box: `KRATOS_PUBLIC_URL=… ./gradlew :server:test --tests "*OidcSpikeTest" --rerun-tasks`.

## GO / BLOCK summary

- **S1 SAFE (both) → GO** to build the client button + live flow. **S1 UNSAFE → BLOCK OIDC** (fix + re-spike).
- **S2** → auto-verified or verify-then-admit; both proceed.
- **S3** → closed, or §D graded limit (CYP-179).
- **S4** → delegation confirmed (automated).
- **Multi-user:** confirm a new GitHub identity is **MEMBER**, not OPERATOR (only the FIRST verified identity
  bootstraps OPERATOR — the existing `SqliteRoleStore` Middleway; verify via `/api/auth/me` role).

## Follow-up (post-GO) — automated S1 regression via a mock OIDC IdP

The manual S1 above is the *initial* gate. For long-term **regression protection** (catching a future config/
version change that reintroduces auto-link-on-unverified), a **mock OIDC IdP** can automate the S1a core check:
a local OIDC provider (`.well-known` + JWKS + a signed `id_token`) registered as a Kratos **`generic`** provider
that returns `email_verified:false, email=v@x` → assert Kratos does NOT auto-link/verify to the existing `v@x`.
Caveats (why it is a follow-up, not the initial gate): (1) it still needs deploy coordination (live Kratos must
reach the mock); (2) it exercises the `generic`-OIDC path, not the ship path's `github` OAuth2 provider — so it
is a fast **BLOCK-if-unsafe** tripwire on the core logic, never a **GO** confirmation (only the real GitHub flow
is faithful). Build it when OIDC ships if the HARD-gate automation is judged worth the signed-JWT-provider effort.
