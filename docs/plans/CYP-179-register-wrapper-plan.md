# CYP-179 / §B(b) — Register-Wrapper (contract-first design record)

Auftraggeber ratified §B option **(b)**. Closes the RC2 **§B** registration-content account-enumeration leak
(Kratos v1.3.0: new email → 200+create, existing → 400/`4000007`; + account-creation side-effect) by mediating
registration in the platform layer so the response reveals nothing.

## Contract
- **`POST /api/auth/register { email, password }`** — PUBLIC (anyone may register), the ONLY app register path
  (MUST-1). On the route-enum public allowlist (audited), like `/api/auth/me`.
- **Response is branch-INVARIANT:** new and existing → identical `200 {"status":"verification_pending"}`
  (constant `REGISTER_GENERIC_BODY`). Admin outage → uniform `503` (same body). Malformed input → `400`
  (existence-independent). The mail carries the branch (verify vs "you already have an account"), never the HTTP.

## Mechanism (the 3 Reviewer MUSTs, structural)
`RegisterMediator.register(email, password)`:
1. **existence check runs for BOTH branches** (symmetric) via the `KratosRegisterBackend` seam (admin :4434).
2. the branch-DIVERGENT work (`createAndVerify` new / `notifyExisting` existing) is **`dispatch`ed OFF the
   response path** → response latency branch-invariant (**MUST-2 timing parity**).
3. existence-check outage → `UNAVAILABLE`, **no create dispatched** (**MUST-3 fail-closed**, uniform).
4. seam exposes ONLY exists + the two side-effects — **no general admin proxy** (**MUST-1 register-only scope**).

Secret hygiene: email/password never logged (only failure classes). Existing branch enqueues a notice mail so
the branches are mail-symmetric (no "silence == exists" side-channel).

## Teeth (hermetic, mutation-verified)
- `RegisterWrapperTest`: content-parity · timing-parity (side-effect DEFERRED at return, both branches) ·
  no-leak (existing creates nothing / store count-invariant; new creates one) · fail-closed (uniform UNAVAILABLE,
  no dispatch). **MUT-A** (run side-effect sync) → reds timing+no-leak; **MUT-B** (outage → fail-open) → reds
  fail-closed (mediator + HTTP). Both reverted.
- `RegisterRoutesTest`: the WIRE response is byte-identical for new vs existing (status+body+Set-Cookie); no
  email echo / no `4000007`/flow-id; uniform 503 on outage; 400 on blank.
- `ProtectedRouteEnumerationTest`: register mounted + on the audited public allowlist (intentionally open).

## Deploy-verified seam (NOT the security property — that's hermetic)
`HttpKratosRegisterBackend` (admin :4434, loopback, RC4). Admin API shapes confirmed (Context7/Ory):
create = `POST /admin/identities` w/ `credentials.password.config.password`; exists =
`GET /admin/identities?credentials_identifier=`. The **verify-mail-for-admin-created-identity** + **existing-
notice** mechanics on v1.3.0 are version-sensitive → pinned by `deploy/kratos/CYP-179-REGISTER-WRAPPER-RUNBOOK.md`
(C1–C5 + Mailpit) and `RegisterWrapperLiveProbeTest` (RUN-gated).

## Config / wiring
`AuthConfig.kratosAdminUrl: String? = null` (default null → wrapper NOT mounted = fail-closed). Set → boot
constructs `HttpKratosRegisterBackend` + `RegisterMediator` (dedicated supervised IO scope) and mounts the route.

## Deploy assumptions (PO-coordinated; wrapper does NOT depend on them at build)
1. raw Kratos `:4433` self-service register made **off-box unreachable** at the edge (else §B bypassed);
2. per-IP **edge throttle (G3)** fronts the endpoint (same layer as login §A). Kratos-version-bump re-triggers
   the RC2 live re-probe (standing rule).
