# CYP-234a-2b — `/api/v1` Route-Prefix Versioning: Durable Build Plan

> Status: **DURABLE BUILD PLAN — pre-scoped while the 234a-2a REST side gates. Build FRESH off the merged REST
> tip (stacks on the RestContract).** ROUTING-CRITICAL: the deployed app depends on `/api`; a break here is a
> LIVE regression. The gate must prove `/api` is preserved BYTE-IDENTICALLY and both prefixes are IDENTICAL.

## 0. Goal

Additively expose every frontend REST endpoint under **both** `/api` (unchanged, the live surface) **and**
`/api/v1` (the versioned alias), so a BYO-frontend can pin a version. WS routes (`/ws/*`) and the connector
`/mcp/hub` are **NOT** versioned (out of the frontend-REST contract). No behavior change to any handler.

## 1. Why it isn't a 1-liner

Every REST route function hard-codes its `/api…` base internally (verified):
`commRoutes → route("/api")`, `agentMgmtRoutes → route("/api/agents")`, `authMeRoutes → get("/api/auth/me")`,
`eventRoutes → route("/api/events")`, `projectRoutes → route("/api/projects")`, `workspaceRoutes →
get("/api/workspace/members")`, … (~13 functions). Nesting them under a second `route("/api/v1")` would yield
`/api/v1/api/agents`. So the prefix must be lifted OUT of the functions.

## 2. Approach — **Option B: strip the prefix, mount the whole set under a base loop** (RECOMMENDED)

Make each REST route function mount **relative** paths (strip the leading `/api`), then in `PlatformWiring`:

```kotlin
for (base in listOf("/api", "/api/v1")) {
    route(base) {
        commRoutes(...)        // was route("/api"){get("/health")…}  → get("/health"), route("/agents")…
        eventRoutes(...)       // was route("/api/events")            → route("/events")
        lifecycleRoutes(...); configRoutes(...); agentMgmtRoutes(...); connectorRoutes(...)
        reportRoutes(...); projectRoutes(...); channelShareRoutes(...)
        settingsClient?.let { settingsRoutes(...) }; registerMediator?.let { registerRoutes(...) }
        authMeRoutes(...); workspaceRoutes(...)
    }
}
// OUTSIDE the loop — NOT versioned: hubMcpRoutes (/mcp/hub) + the webSocket routes (/ws/*)
```

**Why Option B over an `apiBase` param threaded through 13 functions:** the single loop body mounts the SAME
sub-tree under each base → both prefixes are **identical BY CONSTRUCTION** (same route calls, same
`authenticatedApi` groups, same handlers). "Both prefixes identically auth-gated" is then structurally
guaranteed, not something the reviewer must re-verify per function. The gate test confirms the invariant; the
construction enforces it. (Option A — `apiBase: String = "/api"` per function + double call — is more surgical
per file but risks a forgotten double-call dropping one route from one prefix; Option B can't.)

**Files touched (~13 route functions, mechanical prefix-strip):** CommRoutes, AgentMgmtRoutes, LifecycleRoutes,
ConfigRoutes, ConnectorRoutes, ReportRoutes, ProjectRoutes, ChannelShareRoutes, EventRoutes, WorkspaceRoutes,
AuthMeRoutes, RegisterRoutes, SettingsRoutes + PlatformWiring (the loop). **NOT touched:** HubMcpRoutes,
AdminMetricsRoutes (unwired), the WS routes.

## 3. THE GATE (PO-mandated, routing-critical)

Extend `ProtectedRouteEnumerationTest` (the real routing-tree walk) into a per-prefix invariant test:
1. **`/api` byte-identical:** the `/api` route set (method+path) is EXACTLY the pre-2b set — i.e. == the
   42-op `RestContract.REST_OPS` path set (+ the unversioned `/mcp/hub`, `/ws/*` unchanged). No `/api` route
   added, dropped, or renamed. This is the live-regression tripwire.
2. **Both prefixes present + identical:** strip the prefix from every enumerated `/api` and `/api/v1` route →
   the two sets are EQUAL, both directions (every `/api/x` has a `/api/v1/x` and vice-versa; no route under
   only one prefix).
3. **Both prefixes identically auth-gated:** the existing `scanForLeaks` (no-credential probe → must 401/403)
   runs over BOTH prefix sets → every route fails closed under `/api/v1` exactly as under `/api` (no gate
   dropped when a route gained a second mount). Plus: the public allowlist holds under both prefixes.
4. **Non-vacuous:** a mutation that mounts the loop over only `["/api"]` (drops the v1 mount) → the
   both-prefixes test reds; a mutation that guards `/api/v1` differently → the auth-per-prefix test reds.

## 3.5 CYP-272 — bind the `tier` column to the REAL gate (fold in here)

The Tester flagged: `RestContract`'s `tier` column feeds the OpenAPI `security` / `x-auth-tier` but is bound to
NO tooth → silent desync risk. Feasibility (investigated read-only): the gate structure is heterogeneous —
OPERATOR/MEMBER routes sit under a structurally-walkable `authenticatedApi` group (`AuthenticatedRouteSelector`
child + `AuthGuard`, `required` role in plugin config); PARTICIPANT routes gate **in-handler**
(`requireParticipant`/`requireCommReader`, invisible to the tree); PUBLIC = no gate. `AuthRole` = {OPERATOR,
MEMBER}, OPERATOR ⊇ MEMBER. **A HYBRID tooth binds tier ↔ gate:**
1. **1-line enabler:** `authenticatedApi` also does `attributes.put(RequiredRoleKey, required)` on the guarded
   route → the tree walk reads OPERATOR vs MEMBER EXACTLY (not just "grouped").
2. **No-cred probe** (existing `scanForLeaks`) → PUBLIC vs protected.
3. **Classify each `/api` route:** grouped+role → OPERATOR/MEMBER · protected+not-grouped → PARTICIPANT ·
   no-cred-reachable → PUBLIC → **assert == `RestContract.tier`** (folding PARTICIPANT_WRITE→participant, since
   the write/ACL distinction is downstream, not an auth tier `security` expresses). Non-vacuous: flip a tier → RED.

Fold into the extended `ProtectedRouteEnumerationTest` — it already walks the tree for both prefixes, so the
tier-tooth runs **per prefix** (both `/api` and `/api/v1` bind their tier identically). **CYP-272 closes here,
BEFORE the 234a-3 docs deploy**, so the served OpenAPI `security` is gate-bound, not silently drift-able. The F2
widening (`GET /api/agents/{id}` = token-only `requireParticipant`) classifies as PARTICIPANT — the documented
widening point, now tooth-pinned.

## 4. Contract/drift reconciliation

`RestContract.REST_OPS` stays keyed on `/api` (the canonical surface). The `RestContractDriftTest` continues to
assert `REST_OPS == the /api enumeration` (now filtered to the `/api` prefix, excluding `/api/v1`). The
both-prefixes equality is the 2b gate's job (§3.2), not RestContract's. `ContractGenerator.openApi()` documents
`/api` paths + notes `/api/v1` as the versioned alias in `info.description` (or a `servers` entry) — no
per-path duplication in the spec.

## 5. Risks + mitigations

- **A stripped path that collides** (two functions mounting the same relative subtree): the enumeration test's
  route-count assertion catches a lost/merged route. Verify the pre/post `/api` set is IDENTICAL (§3.1).
- **A full-`/api` literal elsewhere** (CORS, redirects, the SPA): the client is origin-relative `/api` →
  unaffected; grep for hard-coded `"/api/"` server-side before stripping. CORS config (if path-scoped) must
  allow both prefixes.
- **`/ws/*` + `/mcp/hub` must stay single-mount** — keep them OUTSIDE the base loop; the WS-channel drift-test
  (already merged) + the `/mcp/hub` exclusion guard this.

## 6. Gate summary + discipline

Branch `feature/CYP-234-2b-…` off the MERGED REST tip. **Gate = the extended `ProtectedRouteEnumerationTest`
(byte-identical `/api` + both-prefixes-equal + auth-per-prefix + non-vacuous) + `RestContractDriftTest` still
green + `:server:check` green.** `:e2e` SHOULD run here (routing change → confirm the real app surface). Commit
prefix `CYP-234:`.
