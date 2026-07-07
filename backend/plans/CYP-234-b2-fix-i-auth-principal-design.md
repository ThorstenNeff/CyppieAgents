# CYP-234b-2 ①-Fix — `resolvePrincipal` bearer validation: Design Pass (core auth seam, design-first)

> Status: **DESIGN PASS — no build until ratified (PO + PO-Assistant).** Lands **in/before 234b-2**; 234b-3
> stays paused. Access-critical core-auth-seam. Realizes the ratified 3-part fix + a second finding.

## 0. The finding (empirically proven at the 234b-2 gate)

`resolvePrincipal` (Principal.kt) maps **any non-operator bearer** → `AuthPrincipal.MachineAgent(agentFor(bearer))`
— **without validating the bearer**. `MachineAgent.role` is hardcoded `AuthRole.MEMBER` (AuthPrincipal.kt:26-27).
So a **garbage / unregistered / participant** bearer → `MachineAgent(null)` → `MEMBER` → **satisfies the
`authenticatedApi(MEMBER)` gate** → reads `/api/events` (team-wide cross-agent metadata) with no grant. Probe:
`participant → 200 · garbage → 200 · agent → 200`. Pre-existing (the MachineAgent→MEMBER mapping under CYP-186
BE2's MEMBER-tier `/api/events`); the adversarial 234b-2 probe surfaced it; 234b-3's real minted tokens make the
participant-token leak live.

**Second finding (this design pass, same bug class):** `resolveAuthState` (`GET /api/auth/me`) has its OWN
un-validated bearer branch — `if (bearer != null) { role = isOperator ? OPERATOR : MEMBER; authenticated=true }`.
So a **garbage bearer → whoami reports `authenticated=true, role=MEMBER`**. Must be fixed in the same pass.

## 1. The ratified 3-part fix (+ the 2nd path)

① **`resolvePrincipal`: unknown bearer → null (401).** Only a KNOWN credential resolves:
```
operator token                         → MachineOperator (OPERATOR)   [unchanged]
agentFor(bearer) != null (known agent) → MachineAgent(agentId)  (MEMBER) [unchanged; CYP-186 BE2 preserved]
else (unknown / garbage / participant) → null                          [was MachineAgent(MEMBER) → the leak]
```
`MachineAgent.agentId` becomes **non-null** (a known agent) — the `null` case is exactly the hole.

② **Participant token → a distinct read-tier principal that does NOT satisfy the MEMBER gate.** Simplest
faithful realization: the participant token is *unknown to `resolvePrincipal`* → returns null → 401 at every
`authenticatedApi(role)` gate; it is accepted **only** by the canRead-scoped resolvers
(`requireCommReader`/`Writer`/`requireParticipant`/`wsReaderOrNull`, via `participantSubject`, CYP-234b-2). No
new principal variant needed — the participant token is simply not a role-bearing principal.

③ **MEMBER stays for verified humans + KNOWN agent tokens** (`agentFor != null`). CYP-186 BE2 `/api/events`
semantics (known agents read team-wide event metadata) is UNCHANGED — only garbage/unknown/participant fly out.

**Second path — `resolveAuthState`:** a bearer resolves to authenticated ONLY if operator (OPERATOR) or a known
agent (MEMBER); an unknown bearer → `authenticated=false` (not MEMBER). Participant-token whoami = a small
follow decision (report authenticated read-tier vs. not-a-human) — flagged, non-blocking to the leak close.

## 2. (a) Blast-radius — nothing legitimate depends on "unknown-bearer→MEMBER"

- **Human session** resolves via the Kratos `sessionCredential()` path, NOT the bearer path → unaffected. ✓
- **Known agent tokens**: `agentFor != null` → `MachineAgent(MEMBER)` unchanged → `/api/events` still readable. ✓
- **Known operator token**: `MachineOperator` unchanged. ✓
- **`requireCommReader`/`Writer`/`wsReaderOrNull`**: a known agent/operator resolves via `participantFor` (token
  axis) BEFORE `resolvePrincipal`; these resolvers already accept only `Human` (else 401/close) from
  `resolvePrincipal`, so `MachineAgent`→null there is a no-op for them. ✓
- **The ONLY `unknown-bearer→MEMBER` consumers** are the `authenticatedApi(MEMBER)` REST gates (§3). No
  legitimate flow relies on an *unregistered* bearer being MEMBER — that path only ever served garbage +
  (post-234b) participant tokens. ✓
- **`actorOf`** already handles `agentId ?: "unknown"` (audit string) → non-null agentId is a safe tighten. ✓
- **Operator routes**: an unknown bearer shifts 403→401 (more correct: it isn't authenticated). Known agent →
  still 403 (MachineAgent fails OPERATOR). ✓

## 3. (b) Re-verification list — every tier gate with KNOWN credentials still works

| surface | known operator | known agent token | verified human MEMBER | garbage bearer | participant token |
|---|---|---|---|---|---|
| `GET /api/events` (authApi MEMBER) | 200 (unch) | 200 (unch) | 200 (unch) | **401 (was 200)** | **401 (never MEMBER)** |
| `POST /api/auth/settings/*` (MEMBER) | 200 | 200 | 200 (session) | **401** | **401** |
| `/ws/events` (wsReader MEMBER) | ok (unch) | ok (unch) | ok (unch) | close (already) | close (never MEMBER) |
| operator routes (authApi OPERATOR) | 200 | 403 (unch) | 403/200 by role | **401 (was 403)** | 401/403 |
| `requireCommReader/Writer` comm | ok | ok (participantFor) | ok | 401 (already) | 200 where canRead-granted (unch) |
| `GET /api/auth/me` | OPERATOR | MEMBER | role (session) | **authenticated=false (was MEMBER)** | follow-decision |

The two **behavior changes** are both hardening: garbage → 401 (was MEMBER-200), and participant → 401 at the
MEMBER gate (it reads comm via canRead, never the event-log). Everything with a KNOWN token is unchanged.

## 4. (c) Teeth (adversarial re-gate)

- `garbage bearer → 401` on `/api/events` (+ `resolvePrincipal(garbage) == null`) — closes the hole.
- `participant token → NEVER satisfies MEMBER` (401 on `/api/events`) — the missing `neverSatisfiesTheMemberGate`
  from 234b-2, now added.
- `known agent token → still MEMBER` (200 on `/api/events`) — CYP-186 BE2 preserved (regression guard).
- `known operator → still OPERATOR`.
- `GET /api/auth/me` with a garbage bearer → `authenticated=false` (the 2nd path).
- Mutation checks: revert `resolvePrincipal` to `MachineAgent(agentFor(bearer))` → garbage + participant teeth RED.

## 5. Live-reachability assessment (CODE-ONLY — not deployed/probed by me)

The server binds `127.0.0.1` (`bootHost`), BUT the deploy fronts it with a public reverse-proxy (the SPA + `/api`
are publicly served). So a garbage bearer from the public internet → proxy → `GET /api/events` → **200 today**.
**Urgency: HIGH — publicly reachable.** Mitigating scope: the Event-Log is **content-free metadata** (event
types, agent ids, timestamps — NEVER message bodies/secrets, per CYP-39/89), so the disclosure is *cross-agent
metadata* (CYP-18 class), not content or credentials. Recommend the ①-fix ships promptly + the human confirms
the live proxy exposure at the deploy point (I do not deploy/probe). No secret/body exposure; a metadata
disclosure to any bearer-presenting caller.

## 6. Scope + discipline

Fix lands in/before 234b-2 (its honest completion); 234b-3 stays paused, rebases on the fixed tip after.
`resolvePrincipal` + `resolveAuthState` + `MachineAgent.agentId` non-null; teeth §4; `:server:check` + `:e2e`;
adversarial re-gate. NOT a contract change. Deploy human-gated.
