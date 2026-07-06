# CYP-234a-2a — OpenAPI/AsyncAPI Document Assembly: Durable Build Plan

> Status: **DURABLE BUILD PLAN — take FRESH off the merged tip (survives a compact, CYP-255-monolith
> precedent).** The 234a-2a **fidelity core is DONE** (`SchemaWalker` generation + 234a-1 instance conformance
> [narrowing] + the 234a-2a `SchemaTightnessTest` [widening] → "generated ⇒ correct" complete). This plan is
> the REMAINING 234a-2a: assemble the full OpenAPI 3.1 + AsyncAPI documents from `:core` and drift-test the
> routes. Then **234a-2b** = the `/api/v1` route-prefix refactor (separate gated slice, its own plan).

## 0. What exists (build ON this) + what this delivers

- **`server/.../contract/SchemaWalker.kt`** (merged) — `SerialDescriptor` → OpenAPI 3.1 / JSON-Schema. `oneOf`+
  `discriminator{type}`, nested recursion, opaque/contextual/custom-serializer → carved `{}`. Exposes
  `components` (name→schema), `registeredNames` + `nameCollisions()` (collision guard), `componentDescriptors`
  (name→descriptor, for tightness). `schemaName(desc)` = simple name (@SerialName wire name for union subtypes).
- **`SchemaConformance` + `ContractConformanceTest` + `SchemaTightnessTest`** (test) — the fidelity guarantee.
- **THIS delivers:** (1) full component generation over ALL wire-DTO roots; (2) the AsyncAPI document (WS
  channels + message unions — walker-derivable); (3) the OpenAPI document (REST paths — the hand-authored
  `RestContract` table, §2); (4) the **bidirectional drift-test**; (5) emit both docs to a resource/file.

## 1. Walker-derivable side (no hand-authoring — do FIRST)

- **Full component gen:** a `ContractRoots` list of the root serializers (below); walk each → merge all
  `components`. Then `nameCollisions()` == empty (the guard) + `SchemaTightnessTest` covers all (extend its
  `roots` to the full set).
- **AsyncAPI channels** (the FRONTEND WS surface — `/ws/hub` is EXCLUDED, it's the remote-AGENT wire → the
  connector contract, per design §2.5; `/ws/agent` is token-only, included but flagged):
  | channel | subscribe (server→client) | publish (client→server) |
  |---|---|---|
  | `/ws/comm` | `CommWsServerEvent` | `CommWsClientEvent` |
  | `/ws/events` | `EventsWsServerEvent` | `EventsWsClientEvent` |
  | `/ws/lifecycle` | `AgentRunStateEvent` | — |
  | `/ws/agent` | `StreamJsonEvent` (or `StoredAgentEvent`) | `UserTurn` (token-only; §2.4) |
  Each message `payload` = a `$ref` to the generated component. Build the AsyncAPI `channels` + `components/
  messages` from the walker.
- **Wire-DTO ROOTS** to generate + conformance/tightness-cover: `CommWsServerEvent`, `CommWsClientEvent`,
  `StreamJsonEvent`, `UserTurn`, `EventsWsServerEvent`, `EventsWsClientEvent`, `AgentRunStateEvent`,
  `StoredAgentEvent`; REST DTOs: `Agent`, `Channel`, `Message`, `AclEntry`, `NewAgentSpec`, `AgentEdit`,
  `AgentDetail`, `CreatedAgent`, `Project`, `ProjectsView`, `CreateProjectRequest`, `RenameProjectRequest`,
  `SwitchActiveRequest`, `ApiKeyView`, `ApiKeyRequest`, `RepoView`, `RepoRequest`, `SendMessageRequest`,
  `ConnectorChoice`, `ReportSnapshot`, `ReportRequest`, `ChannelShare*`, `EventPage`, `ApiErrorBody`, `Capabilities`,
  `ProviderInfo`, `AgentAvatar`. (Walker recurses into nested types — enumerate the ROOTS only.)

## 2. REST paths → DTO + tier (the HAND-AUTHORED precision work — do CAREFULLY)

Declarative `RestContract` = `List<Op(method, path, tier, requestDto?, responseDto?)>`. Table below is the
ground truth (verified against `PlatformWiring.kt:55-157` + the route files). **Tier legend:** `pub`=public,
`part`=participant/read, `part-w`=participant write (ACL-gated downstream), `op`=operator. Paths are under bare
`/api` today (2b adds `/api/v1`).

| # | method + path | tier | request | response | route file |
|---|---|---|---|---|---|
| 1 | GET /api/health | pub | — | text `ok` | CommRoutes |
| 2 | GET /api/auth/me | pub | — | (whoami, content-free) | AuthMeRoutes |
| 3 | POST /api/auth/register | pub | (register) | (branch-invariant) | RegisterRoutes |
| 4 | GET /api/agents | part | — | `List<Agent>` | CommRoutes |
| 5 | GET /api/agents/{id} | part | — | `AgentDetail` | AgentMgmtRoutes (⚠ the §2-F2 widening: token-only→session under 234b) |
| 6 | GET /api/agents/{id}/avatar | part(read) | — | PNG bytes | AgentMgmtRoutes |
| 7 | GET /api/agents/{id}/avatar/preview | part(read) | — | PNG bytes | AgentMgmtRoutes |
| 8 | POST /api/agents | op | `NewAgentSpec` | `CreatedAgent` | AgentMgmtRoutes |
| 9 | PUT /api/agents/{id} | op | `AgentEdit` | `Agent` | AgentMgmtRoutes |
| 10 | DELETE /api/agents/{id}?worktree= | op | — | 204 | AgentMgmtRoutes |
| 11 | POST /api/agents/{id}/avatar | op | multipart | `AgentDetail` | AgentMgmtRoutes |
| 12 | DELETE /api/agents/{id}/avatar | op | — | 204 | AgentMgmtRoutes |
| 13 | POST /api/agents/{id}/{stop,start,restart} | op | — | `AgentRunStateEvent` | LifecycleRoutes |
| 14 | POST /api/agents/{id}/connector | op | `ConnectorChoice` | `Agent` | ConnectorRoutes |
| 15 | GET /api/channels | part | — | `List<Channel>` | CommRoutes |
| 16 | GET /api/channels/{id}/messages?since= | part | — | `List<Message>` | CommRoutes |
| 17 | POST /api/channels/{id}/messages | part-w | `SendMessageRequest` | `Message` (201) | CommRoutes |
| 18 | GET /api/inbox?since= | part | — | `List<Message>` | CommRoutes |
| 19 | GET /api/acl | part | — | `List<AclEntry>` | CommRoutes |
| 20 | PUT /api/acl | op | `AclEntry` | `AclEntry` | CommRoutes |
| 21 | GET /api/channels/{id}/share | part | — | (share view) | ChannelShareRoutes |
| 22 | PUT/DELETE /api/channels/{id}/share | op | (share req) | (share) | ChannelShareRoutes |
| 23 | GET /api/events?since=&until=&projectId= | op | — | `EventPage` | EventRoutes |
| 24 | GET /api/config/{repo,apikey} | part(masked) | — | `RepoView`/`ApiKeyView` | ConfigRoutes |
| 25 | PUT /api/config/{repo,apikey} | op | `RepoRequest`/`ApiKeyRequest` | (view) | ConfigRoutes |
| 26 | GET /api/projects | op | — | `ProjectsView` | ProjectRoutes |
| 27 | POST /api/projects | op | `CreateProjectRequest` | `Project` (201) | ProjectRoutes |
| 28 | POST /api/projects/switch | op | `SwitchActiveRequest` | `ProjectsView` | ProjectRoutes |
| 29 | PUT /api/projects/{id} | op | `RenameProjectRequest` | `Project` | ProjectRoutes |
| 30 | DELETE /api/projects/{id}?deleteWorktrees= | op | — | `ProjectDeleteReceipt` | ProjectRoutes |
| 31 | GET /api/reports + GET /{id} + POST /api/reports | op | (report req) | `ReportSnapshot` | ReportRoutes |
| 32 | GET /api/workspace/members + /api/audit | op | — | (roster/audit) | WorkspaceRoutes |
| 33 | POST /api/auth/settings/{password,email} | member | (settings) | — | SettingsRoutes |
| 34 | GET /api/admin/db/metrics | op | — | (metrics) | AdminMetricsRoutes |

> **OUT of the frontend OpenAPI (documented in the CONNECTOR contract, not here):** `POST /mcp/hub` (HubMcpRoutes
> — the in-process Hub MCP for Connector-A agents) + `/ws/hub` (remote-agent wire). Both are agent-BYOA, not
> frontend transport (design §2.5). The drift-test's frontend-route set EXCLUDES these two (assert they are
> knowingly-excluded, not silently-missing).
>
> **Every op** also declares the uniform error envelope `ApiErrorBody` (`{error:{code,message}}`) as its 4xx/5xx
> response, and its security tier (§2 of the design → OpenAPI `securitySchemes`: bearer token + the session
> cookie; tier = which scheme(s) satisfy it). Tiers here are the CURRENT audited posture (234b unifies the gate
> impl; this table just RECORDS the tier per op).

## 3. Bidirectional drift-test (the gate)

Extend `ProtectedRouteEnumerationTest.kt:83` (the authoritative route inventory) into a contract drift-test:
- **REST, both directions:** the `RestContract` path+method set == the actual mounted-route set. **No spec op
  without a route (no phantom endpoint), no route without a spec op (no undocumented endpoint)** — except the
  two knowingly-excluded connector routes (`/mcp/hub`, and `/ws/hub` on the WS side), which the test asserts are
  in an explicit EXCLUDE list (so "excluded" can't hide a real omission).
- **WS, both directions:** the AsyncAPI channel set == the actual `webSocket("...")` inventory across the
  routing package (`/ws/comm|events|lifecycle|agent`; `/ws/hub` in the exclude list).
- Keep `nameCollisions()` == empty + `SchemaTightnessTest` (extended to the full root set) green.

## 4. Document assembly + emit

- `ContractGenerator` builds two JSON documents: **OpenAPI 3.1** (`openapi`, `info`, `paths` from `RestContract`
  with `$ref` request/response bodies + `ApiErrorBody`, `components/schemas` from the walker, `components/
  securitySchemes`) + **AsyncAPI 2.6** (`asyncapi`, `info`, `channels` §1, `components/messages` + `schemas`).
- **Emit** both to `server/src/main/resources/contract/{openapi.json, asyncapi.json}` via a test (or a Gradle
  task) so they're checked in + served later (234a-3 renders them). A "generated docs are up-to-date" test
  fails if the checked-in file != the freshly-generated one (the docs-can't-drift gate for the emitted files).

## 5. Gate + branch discipline

Branch `feature/CYP-234-2a-...` off the MERGED tightness tip. **Gate = the bidirectional drift-test (REST +
WS, both directions) + the tightness/conformance/collision teeth (full root set) + the emitted-docs-up-to-date
test + `:server:check` green.** No `:e2e` (no behavior/route change in 2a — routes are only READ for the
inventory; `/api/v1` mounting is 2b). Commit prefix `CYP-234:`. Partial pushes welcome (walker-derivable side
first, then the RestContract table). **234a-2b (the `/api/v1` route-prefix refactor) is the NEXT slice** with
its own gate: `/api` byte-identical + every endpoint reachable + identically auth-gated under BOTH prefixes
(extend the route enumeration to both prefixes + an auth-tier-per-prefix check).
