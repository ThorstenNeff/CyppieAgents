# CYP-234b — Auth Uniformity: Durable Build Plan (design-first, access-critical)

> Status: **DURABLE PLAN — off the merged CYP-234a tip `fe3e035`. Access-critical (high blast-radius) → built
> fail-closed, autonomously gated, NEVER autonomously deployed.** Design-first (the team's pattern for security
> code), grounded in the current auth surface. Realizes the ratified §2.2 + §6 access contract.

## 0. Ratified model (Auftraggeber-decided, spec §2.2 + §6)

The BYO-frontend credential paths, **decided, no conditional**:
- **Human / browser frontend** → native Kratos login → session cookie (same-origin; CYP-229/230/232). *Exists.*
- **Machine / non-browser frontend** → a **NEW participant-scoped token class** (`Authorization: Bearer`; WS
  `?token=` fallback because a browser can't set a WS `Authorization` header). Read-default tier, revocable,
  optional expiry, per-token rate-limit.
- **CORS** = a controlled, deploy-managed **allow-list** (a BYO web frontend needs its origin listed; incl. the
  docs origin for Swagger "Try it"). Cross-origin web auth is by **token, not cookie** → no `allowCredentials`,
  no cross-origin CSRF.

## 1. Current surface (grounding — what exists vs what's new)

| Piece | Today | 234b |
|---|---|---|
| `TokenRegistry` (Auth.kt) | agent tokens (token→agentId) + ONE `operatorToken`; `participantFor`, `revoke(agentId)` (CYP-171), `newToken` | ADD a participant-token class (issued to EXTERNAL consumers, not per-spawned-agent) |
| tier resolvers | `requireParticipant` / `requireCommReader` / `requireCommWriter` (token axis + human session) | UNIFY: the new token resolves to a read-tier participant through the SAME chokepoints |
| `installRestrictedCors` (Cors.kt) | allow-list, fail-closed empty, no `anyHost`, `allowCredentials=false` | ADD the docs origin; formalize deploy-managed; keep token-not-cookie |
| `WireRateLimiter` (exists, CYP-161) | per-agentId token-buckets on `/ws/hub` | REUSE the pattern → per-participant-token bucket (#7) |
| revoke | `revoke(agentId)` on agent removal | mint + revoke (+ optional expiry) lifecycle for the participant token (#8) |

**Key insight:** CORS-allow-list + WireRateLimiter already EXIST — 234b extends them, it doesn't invent them.
The genuinely new surface is the **participant-token class** + its **mint/revoke/expiry lifecycle** + the
**tier-resolver-unify** so it flows through the existing ACL chokepoints unchanged.

## 2. Sub-slices (each PO-gated; access-critical → small, verifiable)

- **234b-1 — participant-token class + store.** A `ParticipantTokenStore` (or extend TokenRegistry): a token →
  {subject, tier=read-default, issuedAt, expiry?} record; `participantFor` resolves it to a read-tier
  participant id (a first-class ACL read-subject, fail-closed empty until granted, like a human MEMBER). Mint +
  revoke + expiry-check. Secret-at-rest hygiene (never logged, hashed-at-rest like CYP-96 secrets). Fail-closed:
  unknown/expired/revoked token → 401. Teeth: mint→resolve→revoke→401; expired→401; tier is read-default (never
  operator); mutation-RED.
- **234b-2 — tier-resolver-unify.** Route the new token through the SAME `requireCommReader`/`requireCommWriter`
  resolution (token axis first, then session) so the ACL (`canRead`/`canWrite`) is the ONLY authz — the token
  class only widens WHO reaches the chokepoint, never the authz. Prove: a participant token gets read where
  granted, 403 where not; NEVER reaches an OPERATOR gate (403 first). No new bypass. Teeth over the real routes.
- **234b-3 — CORS docs-origin + per-token rate-limit (#7) + revoke lifecycle (#8).** Add the docs origin to the
  allow-list (deploy-managed config); per-participant-token WireRateLimiter bucket (429 on flood, per-token not
  global); the mint/revoke admin path (operator-gated, audited, secret-free). Teeth: off-list origin blocked;
  per-token bucket independent; revoke→immediate-401.

## 3. Gate (every slice)

Access-critical → **fail-closed everywhere + mutation-RED on every guard**: unknown/expired/revoked token → 401;
participant token never satisfies an operator/member gate; CORS off-list → blocked; rate-limit per-token → 429;
revoke → immediate deny. Extend `ProtectedRouteEnumerationTest` / `MemberTier403MatrixTest` so the new token
class is in the deny-matrix (never a silent widening). `:server:check` + `:e2e:test` green. Secret hygiene: the
token value never logged / never in a read-response (aligns with CYP-285). **Deploy = human-gated** (the CORS
allow-list incl. docs-origin is deploy config; I flag it, never apply it).

## 4. Scope honesty

234b is the AUTH-uniformity engine (the credential paths + their enforcement). The **documentation** of these
paths for BYO builders is **234c** (narrative guide). The participant-token class here is the machine credential
234c will document. `.5b` (full runtime-eviction) is an independent parked fast-follow (resumable). Deploy of
the whole CYP-234 chain stays on the explicit Auftraggeber GO.
