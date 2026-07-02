# CYP-186 (S18) — Token-Disposition (C): contract-first plan (no code)

> Status: **PLAN for the bar → Reviewer(Test) security review → build**. The last Multi-User slice. The
> operator **token** and the OPERATOR **role** stay an **OR** at the `authenticatedApi(OPERATOR)` gate (no
> weakening) — C narrows the token to a machine/bootstrap/break-glass path: **attributed** and **deploy-disablable**.

## 0. Grounded seams (from merged code)

- **`AuthGuard.onCall`** (Principal.kt:134) resolves + authorizes the principal, enforces CSRF, stashes it under
  `PrincipalKey`. **Single-source hook** for the audit — every OPERATOR-gated request already passes here.
- **`resolvePrincipal`** (Principal.kt:63): the bearer axis returns `MachineOperator` when `tokens.isOperator`.
  The kill-switch gates **here** (make the token inert when disabled-effective → it falls to MEMBER).
- `AuthConfig` (boot) holds `kratosPublicUrl` etc. — the disable flag lives here (boot/env, immutable at runtime).

## 1. C.1 — audit the principal identity on every OPERATOR mutation

**Seam:** add an optional audit emitter to `AuthDeps` (default no-op), called from `AuthGuard.onCall` **after
authorization**, for **unsafe-method** (POST/PUT/DELETE/PATCH) OPERATOR-gated requests:
```
AuthDeps( …, val audit: (OperatorAudit) -> Unit = {} )
data class OperatorAudit(val actor: String, val method: String, val path: String)   // content-free
```
- **actor** = the attributed principal, NEVER generic "operator":
  - `Human(identityId)` → `"human:<identityId>"`
  - `MachineOperator` (the operator token) → `"operator-token"` — this **IS the machine/break-glass marker**
    (the token path is, by definition, the non-per-user break-glass credential).
- **⚠️ OPERATOR-only sink (Q1 resolved — load-bearing disclosure line):** the attribution `human:<identityId>
  did POST /api/acl` contains **operator identityIds + operator activity** — exactly the class kept OPERATOR-only
  at BE3a (guardrail iii + PII minimization). It must **NOT** enter the **MEMBER-readable** event-log (BE2). So
  the audit goes to a **dedicated OPERATOR-only `AuditSink`** — structural isolation: the audit never touches the
  MEMBER-readable stream, so no read-filter can ever drift into a leak (preferred over "audit-in-event-log,
  tier-filtered on read"). Read via a new **OPERATOR-only** `GET /api/audit` under `authenticatedApi(OPERATOR)`.
  Content is **metadata only** (actor + method + path; never a body/secret).
- Single-source (the guard) → no per-handler drift; every current + future OPERATOR mutation is covered.

## 2. C.2 — token-disable kill-switch (DEPLOY-controlled + lockout-guarded)

- **Flag:** `AuthConfig.operatorTokenDisabled: Boolean = false`, sourced at **boot** from env
  (`CYPPIE_OPERATOR_TOKEN_DISABLED`). **Immutable at runtime — set by NO endpoint / UI / channel / API**
  (the prompt-injection line: a channel message can never disable — or enable — the token).
- **Effect (in `resolvePrincipal`, bearer branch):** the operator token is honored as `MachineOperator` UNLESS
  **disabled-effective**; when disabled-effective it falls through to `MachineAgent` (MEMBER) → 403 on operator
  routes. So a disabled token is simply not an operator anymore.
- **⭐ Lockout-guard:** disabled-effective = `operatorTokenDisabled && roles.hasOperator()`. The disable takes
  effect **only once a role-OPERATOR (a verified human) already exists** — until then the token stays active,
  so the platform is **never left with zero operator paths** (the first human still bootstraps OPERATOR via
  `ensureAssigned`, no token needed; the token covers the window before that). New `RoleStore.hasOperator():
  Boolean` (cheap `SELECT 1 … WHERE role='OPERATOR'`).

## 3. C.3 — collapse to A (documented)

With the kill-switch set AND a role-OPERATOR present, the token is inert → only role-OPERATOR (per-user, audited
as `human:<id>`) passes the mutation gate → the deployment runs at **option A's posture** (per-user identity,
no shared-secret operator) by config. Documented as the pure-per-user deployment mode.

## 4. Test-AC (load-bearing)

- **Audit attribution, teethed:** a POST/PUT under an OPERATOR gate by the **operator token** → an audit record
  `actor="operator-token"`; by a **human OPERATOR session** → `actor="human:<identityId>"`. The two are
  DISTINGUISHABLE per mutation (teeth: collapse both to "operator" reds). A safe GET emits none (mutations only).
- **Kill-switch is deploy-only:** no endpoint sets/clears `operatorTokenDisabled` — asserted by the route-enum
  meta-test (no such route) + a config-only source. A channel/API attempt is structurally impossible.
- **Lockout-guard, teethed:** `operatorTokenDisabled=true` + **no** role-OPERATOR → the operator token STILL
  authorizes (200) — the platform is not locked out. Then once a role-OPERATOR exists → the same token is 403.
  (Teeth: removing the `hasOperator()` guard locks out the fresh platform → the "still-200-before-first-operator"
  assert reds.)

## 5. Decisions (PO-confirmed 2026-07-02) + added disclosure-line AC

- **Q1 → OPERATOR-only audit.** Dedicated `AuditSink` + OPERATOR-only `GET /api/audit`; the audit never enters
  the MEMBER-readable event-log. **New load-bearing AC:** a MEMBER must NEVER read an audit entry — via
  `/api/audit` (403) **and** via the MEMBER event-log read (audit is structurally absent from that stream).
  Teeth: a MEMBER probing `/api/audit` → 403; and the MEMBER `/api/events` read never contains an audit record.
- **Q2 → confirmed:** `MachineOperator` = the machine/bootstrap/break-glass marker; a token-authed mutation logs
  as `operator-token`. No separate break-glass MODE.
- **Q3 → never-lock-out-dynamic** (`disabled-effective = flag && roles.hasOperator()`). No boot-hard-fail.
- **Q4 → confirmed:** deploy sets `CYPPIE_OPERATOR_TOKEN_DISABLED` (box-local, never UI/channel/API); **default =
  not-disabled** (C stays Hybrid until deploy opts into the A-collapse).

**Process:** this plan → Reviewer(Test) security review → GO → build C.1+C.2+C.3 (each mutation/teeth-proven, the
§4 AC axes + the Q1 disclosure-line AC) → Bar → Test-verify → PO-merge.

**Scope:** server-only (`OperatorAudit` is server-internal; the audit-read DTO is small — server-side or a tiny
`:core` shape if the client ever reads it, but not needed now). No gate weakening (token+role stay OR).
