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

### S1 — exact account-state setup (for the Auftraggeber)

**Platform precondition (both S1a & S1b):** create a **VICTIM** identity on dev-Kratos via the P2 **password
register** flow with a test email the Auftraggeber controls (e.g. `victim@<test-domain>`) and **verify** it —
a normal established password account (the collision target). Capture its admin-API state BEFORE the test:
`GET :4434/admin/identities/{id}` → `credentials` (should be **password only**) + `verifiable_addresses`
(victim email `verified:true`).

**S1a — unverified attacker-email == victim-email (the takeover test):**
1. On the dedicated GitHub **test account**, **add** `victim@<test-domain>` as an email but **do NOT click
   GitHub's confirmation** → it stays **unverified** (it will be a *secondary* email — GitHub forbids an
   unverified *primary*). Keep the test account's OWN real email as primary + verified.
   *(Models an attacker who does NOT own the victim's email but attaches it unverified.)*
2. **Action:** "Sign in with GitHub" with this test account.
3. **Capture AFTER:** the victim identity's admin JSON (credentials + verifiable_addresses) + the OIDC-result
   session's `GET /api/auth/me` + the raw flow outcome.

**S1b — verified attacker-email == existing (defense-in-depth):**
1. On the GitHub test account, **add `victim@<test-domain>` and VERIFY it** (click GitHub's confirmation —
   needs the real mailbox) + set it **primary**. Now the GitHub verified-primary equals the existing platform
   identity's email.
2. **Action:** "Sign in with GitHub".
3. **Capture AFTER:** same as S1a — was the GitHub credential **silently linked** to the existing victim
   identity, or was an **ownership proof** (the account's password / a linking confirmation while authed as the
   victim) required first?

**Notes:** the test email must be a real mailbox the Auftraggeber controls (GitHub's S1b confirmation + being
the targeted "victim"). S1a's unverified-secondary is the realistic setup (unverified-primary is impossible on
GitHub) — the spike observes whether Kratos surfaces/uses the unverified secondary at all.

### GO / BLOCK eval (my call — probe, not guess)

- **S1a SAFE (GO):** the victim identity is **UNCHANGED** — no `oidc` credential linked to it, the victim email
  NOT (re)marked verified via this flow; the unverified GitHub email is **not** used to link/verify (Kratos
  ignores it, keys the new identity on the attacker's OWN verified primary, or errors).
  **S1a UNSAFE (BLOCK):** an `oidc` credential is linked to the victim identity and/or the victim email is
  marked verified from the *unverified* GitHub email → **account takeover**.
- **S1b SAFE (GO):** linking to the existing identity requires an **ownership proof** — no silent auto-merge.
  **S1b UNSAFE (BLOCK):** silent auto-link with no proof.
- **HARD:** GO only if BOTH S1a and S1b are SAFE. Test #1 = **verified-source-only** (S1a) + **ownership-proof-
  before-link** (S1b). Any unsafe on v1.3.0 → **OIDC BLOCKED** (fix via config / v26 + re-spike), never documented.

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

## S1a / S2 spike RESULTS + verdict (2026-07-02, deploy :4434 authoritative readout)

**S1a — GitHub takeover: GO (empirical).** VICTIM (email E) provably UNCHANGED — `credentials=[password]`,
no oidc, `verified=true`; a SEPARATE identity was created from GitHub's **verified primary** email A (not the
collision email E), platform-`verified=false`. Root cause: **GitHub never exposes the unverified secondary
email in the OIDC claim**, so the collision email never reaches Kratos → the "unverified-secondary → auto-link
→ takeover" vector is **structurally impossible with GitHub**. Not a guess — the probe ran and the victim was
untouched.

**⚠️ Per-provider residual (documented, NOT a GitHub blocker):** because GitHub never sent the unverified
email, **Kratos's own "refuse to link an unverified email to an existing identity" defense was NOT exercised**
(upstream-moot for GitHub). Therefore **S1a is a PER-PROVIDER HARD gate**: before adding ANY future OIDC
provider, re-run S1a against that provider's unverified-email exposure AND test Kratos's link-refusal. Do NOT
assume the GitHub result generalizes to a provider that DOES surface unverified emails.

**S2 — verified-mapping: GO (safe default).** The new OIDC identity is platform-`verified=false` — Kratos does
NOT blindly trust GitHub's verified flag → `requirePrincipal` (verified-required) rejects it until an
on-platform verification → **verify-then-admit** (the P2.3 verified-flip covers it; the client shows "verify
your email", P3). Security-safe.
**UX tradeoff (Auftraggeber decision):** this makes GitHub sign-in NOT one-click (a follow-up email verify is
required). One-click would need per-provider "trust GitHub's verified flag" config — GitHub-safe (only sends
the verified primary) but a documented per-provider decision (auto-verify + a provider that surfaces unverified
emails = takeover). **DECIDED (Auftraggeber, 2026-07-02): the safe default (verified=false / verify-then-admit) is BINDING; one-click is NOT chosen** (CYP-185 builds it).

**S1b — verified-collision: PENDING a quick probe.** This run had no real collision (A ≠ E). To settle whether
Kratos silently auto-merges a GitHub login into an existing same-email PASSWORD identity (no ownership proof),
run once with a VICTIM whose email == GitHub's verified primary A. Silent auto-merge → finding (assess
severity); separate identity / proof-required → clean S1b-GO. Not P3-blocking (S1a closed + the verified guard
is a backstop), but worth knowing before the client button ships.

**Overall:** S1a-GO (GitHub) + S2-GO (safe default). **The client "Sign in with GitHub" button (dev-lane) waits
on the S1b quick probe** for the final S1 verdict.
