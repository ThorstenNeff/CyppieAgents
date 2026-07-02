# CYP-179 / §B(b) — Register-Wrapper: Deploy-Verified Seam Runbook

> The platform register-wrapper (`POST /api/auth/register`) closes the RC2 **§B** registration-content
> account-enumeration leak by returning a **branch-invariant** response for a new vs an existing email. The
> **security property is proven hermetically** (`RegisterWrapperTest`, `RegisterRoutesTest` — content/timing/
> no-leak/fail-closed teeth, mutation-verified). What this runbook covers is the **real Kratos v1.3.0
> mechanics** behind the seam — version-sensitive ("may ≠ does") and therefore live-verified, exactly like the
> RC2 / OIDC-S1 / P2.5 mail probes. Nothing here gates the enumeration-closure; it verifies the mail + create
> mechanics are functionally correct on the live stack.

## Preconditions
- Kratos **v1.3.0** up with the reference config; **admin API on loopback only** (`:4434`, RC4 — never
  internet-exposed). Public `:4433` up. Mailpit (SMTP sink, P2) reachable; inbox visible.
- Platform booted with `auth.kratosAdminUrl = http://127.0.0.1:4434` (mounts the wrapper as the ONLY app
  register path) and `auth.kratosPublicUrl` set.
- **Edge assumptions the wrapper depends on (PO-coordinated with deploy — NOT app code):**
  1. raw Kratos self-service register on `:4433` is **off-box unreachable** at the edge (else it re-opens §B
     directly, bypassing the wrapper);
  2. the per-IP **edge throttle (G3)** fronts `POST /api/auth/register` (same layer as login, §A).
- Run `cleanup-junk-identities.sh` (admin `:4434`) before the run to bound account-creation side-effects.

## Checks (record raw evidence for each)

### C1 — existence check shape (`identityExists`)
`GET /admin/identities?credentials_identifier=<email>` on v1.3.0.
- [ ] For a **seeded** email → response lists ≥1 identity (wrapper reads `exists=true`).
- [ ] For a **random-nonexistent** email → empty (wrapper reads `exists=false`).
- [ ] **Pin the response envelope:** bare array `[...]` vs `{identities:[...]}` — `HttpKratosRegisterBackend`
      tolerates both; confirm which v1.3.0 returns and note it. If neither param-filters, fall back to
      `?credentials_identifier=` semantics or the documented filter and update the backend.

### C2 — new-email branch: create + verify mail  ⭐ the load-bearing mechanic
`POST /admin/identities {schema_id, state:active, traits.email, credentials.password.config.password}`.
- [ ] A **fresh** register → identity created (admin count **+1**), state `active`, password set.
- [ ] **A verification mail lands in Mailpit** for that email. **If NOT** (admin-create does not auto-send
      verification on v1.3.0): wire the follow-up trigger in `createAndVerify` (admin verification/recovery
      code path) and re-run until the mail lands. This is the pinned TODO in the backend.
- [ ] Response to the caller = generic `200 {"status":"verification_pending"}`.

### C3 — existing-email branch: no create + notice mail
- [ ] Register with an **existing** email → admin identity count **UNCHANGED** (no dup, no pollution/DoS).
- [ ] Response = the **byte-identical** generic `200 {"status":"verification_pending"}` (compare bytes to C2's).
- [ ] A **notice mail** ("you already have an account") lands in Mailpit (wire `notifyExisting` to the chosen
      path — admin recovery-code or a dedicated SMTP notice). Keeps the branches mail-symmetric.

### C4 — branch-invariance end-to-end (the §B closure, on the live stack)
- [ ] C2 and C3 responses are **byte-identical** (status + body + headers; no branch-differing Set-Cookie/flow-id).
- [ ] **Timing:** sample response latency for new vs existing over N requests; confirm no branch-separable
      distribution beyond host noise (the divergent create/mail is deferred off the response path). Document the
      residual: the admin existence-query itself (absent vs present row) may differ slightly — verify it is lost
      in end-to-end noise and further raised-cost by the G3 edge throttle (same posture as §A timing).

### C5 — fail-closed (MUST-3) on the live stack
- [ ] Stop the admin `:4434` listener; register both a new and an existing email → **uniform `503`** generic
      body for both; **no identity created**. Restart admin; confirm normal operation resumes.

## Outcome
Record C1–C5 evidence (raw responses + Mailpit screenshots + admin counts before/after) into §B of
`RC2-KNOWN-LIMITATIONS.md` and flip **§B → CLOSED (register-wrapper)** once C2/C3 mails + C4 byte-parity +
C5 fail-closed all hold. Until then §B stays the HARD localhost gate. Re-run after any Kratos version bump
(standing rule).
