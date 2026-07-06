# CYP-234 — Frontend-Agnostic Client-Server: Neutral Contract + Auth Uniformity + Versioning (Design)

> Status: **DESIGN-ONLY — ratification artifact, no code, no stacking.** Off develop `a9fc22b`
> (CYP-256-note precedent). Grounds on the CYP-234 baseline audit (Jira comment 12641, file:line) — not
> re-discovered. **Flow: push → PO ratifies → PO-Assistant adversarial 2nd opinion → Auftraggeber ratifies
> the ACCESS model → THEN build.** Zero deploy/time pressure. The goal: a Go / Godot / Web / CLI frontend can
> be built against the same server, without reading Kotlin, without breaking on every server change.

## 0. Baseline (from the audit — the starting point, not re-discovered)

- **Contract = Kotlin `:core` ONLY** (`@Serializable`, sealed + `classDiscriminator="type"`, `CommJson.kt:16-21`).
  No neutral spec (no OpenAPI/AsyncAPI/Proto/JSON-Schema). Compiler-guaranteed for the Kotlin client; opaque to
  a foreign language. **= the critical blocker.**
- **REST:** ~20+ protected + 3 public endpoints under bare `/api` (no `/api/v1`), cleanly enumerable
  (`ProtectedRouteEnumerationTest.kt:83`, `PlatformWiring.kt:55-157`).
- **WS:** `/ws/comm`, `/ws/events`, `/ws/lifecycle`, `/ws/agent`, `/ws/hub`. Frames = sealed interfaces +
  discriminator → **already `{"type":…}` on the wire** (language-neutral to READ). `/ws/hub` already carries
  **envelope versioning** (`WireProtocol.kt:21,24` — `WireEnvelope(v)`, v1 only).
- **Auth:** dual-path (Bearer token AND Kratos session, bearer-first, `Principal.kt:70-88`) but **fragmented** —
  three reader resolvers: `requireParticipant` (token-ONLY, no session), `requireCommReader` (token+session,
  throwing), `wsReaderOrNull` (same tier, non-throwing + `?token=` query fallback) (`Auth.kt:86-139`).
  `/ws/agent` + `/ws/hub` are **token-only by design**. Operator-token kill-switch when a human OPERATOR exists.

**What this means:** the RUNTIME is already consumable by a foreign client TODAY — the wire is JSON with a
`type` discriminator, auth already accepts a token. What's missing is the **neutral spec** (so you don't read
Kotlin), **versioning** (so you don't break), and **auth uniformity + docs** (so you know how to authenticate).
This design changes **almost no runtime behavior** — it PROJECTS the existing contract into neutral artifacts
and UNIFIES the auth gates. See §5 for the explicit "what does NOT change".

## 1. ① Neutral contract — OpenAPI 3.1 (REST) + AsyncAPI 2.6 (WS)

The property we must not lose: **"one contract, both sides, no drift."** Today the Kotlin compiler guarantees
it (`:core` compiled into client + server). A neutral spec breaks that compiler guarantee, so the spec needs
its OWN no-drift guarantee.

**Decision: GENERATE the schemas from `:core`, DRIFT-TEST the paths. Not hand-authored.**

- **Schemas (the DTO shapes) = generated** from the `:core` `SerialDescriptor`s at build time → OpenAPI 3.1
  `components/schemas` + AsyncAPI `components/messages`. kotlinx.serialization descriptors are introspectable;
  a generator walks them and emits JSON Schema (OpenAPI 3.1 IS JSON-Schema-2020-12-aligned, so this is a clean
  projection). **The Kotlin DTO stays the single source** — the schema is a build artifact, regenerated every
  build, so a schema can NEVER drift from the type. This preserves the compiler-guarantee property, projected.
- **Sealed unions → `oneOf` + `discriminator`:** a sealed hierarchy with `classDiscriminator="type"` maps
  exactly to OpenAPI `oneOf: [<subtypes>]` + `discriminator: { propertyName: "type", mapping: { "<wire>":
  "#/…/<Subtype>" } }`. The wire ALREADY carries `{"type":…}`, so the discriminator is HONEST (not invented).
  Each `@SerialName` is the mapping key. This is the crux of the projection and it is well-defined for the
  FLAT unions (`CommWsServerEvent`, the `/ws/hub` `WireEnvelope`, polymorphic REST `AgentAvatar`). **It is NOT
  yet proven for the harder shapes — the 234a spike MUST prove these BEFORE the generator is pinned (PO-A F1):**
  - **nested sealed inside a collection** — `AssistantEvent.content: List<ContentBlock>` (`StreamJsonEvent.kt:115`)
    where `ContentBlock` is itself a sealed interface (`:122`: `TextBlock`/`ThinkingBlock`/`ToolUseBlock`/…) →
    a `oneOf` array of items each `oneOf`+`discriminator`. Generators vary on nested-in-array discriminators.
  - **opaque / contextual fields** — `ToolResultBlock.content: JsonElement?` (`StreamJsonEvent.kt:148`) and the
    custom `TolerantToolsSerializer` — these have NO closed schema; they must be **carved out explicitly** as
    `{}` (any-JSON) in the spec, NOT force-projected. The spike enumerates every such field and carves it.
  If the picked generator can't do nested-sealed faithfully, the fallback in-house descriptor walker handles it
  (it already must, for the conformance tooth below).
- **Paths/operations (REST) + channels+operations (WS) = hand-authored in the spec, DRIFT-TESTED.** Routes
  live in Ktor wiring, not `:core`, so they can't be descriptor-generated. The guarantee: extend the EXISTING
  route enumeration (`ProtectedRouteEnumerationTest.kt:83`, already the authoritative route inventory) into a
  **contract drift-test** — assert the spec's path set == the actual route set (both directions: no spec path
  without a route, no route without a spec path), and each WS channel + its frame union is present. A new route
  or a renamed frame without a spec update FAILS `check`.
- **Auth + errors in the spec:** every operation declares its security tier (§2) + the uniform error envelope
  (`{error:{code,message}}`) as a shared response schema.

**Sync/drift guarantee (replaces the compiler guarantee) — THREE checks in `./gradlew check`:**
1. **schema generation** (schemas ARE the types → zero schema DRIFT);
2. **path/channel drift-test — BIDIRECTIONAL, for REST paths AND WS channels+frame-unions** (PO-A F1c): assert
   the spec's path/channel set == the actual route/socket inventory in BOTH directions — **no spec entry without
   a route, and no route/socket without a spec entry** (extends `ProtectedRouteEnumerationTest.kt:83`; the WS
   side pins the 5 `/ws/*` channels + each channel's frame union the same way);
3. **schema CONFORMANCE tooth (PO-A F1a) — the mis-projection net that drift alone misses:** generation +
   drift guarantee that the spec MATCHES the types and the ROUTES, but NOT that a *consistently-wrong* schema is
   correct. So: serialize a real instance of EVERY `:core` wire DTO (via `CommJson`) and **assert it VALIDATES
   against its own generated schema** (a JSON-Schema validator over the emitted OpenAPI/AsyncAPI). A schema that
   is wrong-but-self-consistent (e.g. a mis-projected discriminator, a wrong nullability, a mishandled nested
   union) fails here even though the path drift-test is green. This is the tooth that makes "generated ⇒
   correct" actually true — without it, "generate" only guarantees "in sync with a possibly-wrong projector".

So "one contract, both sides" becomes "generated-schema + bidirectional-path/channel-drift-test +
per-DTO-conformance-validation, enforced in CI" — an equivalent (arguably stronger) no-drift-AND-no-misprojection
property, now language-neutral. **This is the load-bearing decision of ①.**

> Rejected: fully hand-authored spec + a round-trip drift-test. It works but re-writes every DTO shape by hand
> (100+ types) and drifts the moment someone edits a DTO but not the spec — the drift-test would catch it, but
> generation makes drift STRUCTURALLY IMPOSSIBLE for schemas, which is strictly stronger. Reserve hand-authoring
> for the paths only (there is no descriptor for a route).

> Tooling note (for the build slice, not this ratification): kotlinx.serialization → JSON-Schema generators
> exist but vary in sealed/discriminator fidelity; the 234a spike PICKS one (or a thin in-house descriptor
> walker — ~the same code as the drift-test needs anyway) and pins it. The DECISION here is "generate schemas +
> drift-test paths"; the exact generator is a build detail.

## 2. ② Auth uniformity — the tier model (ACCESS-CRITICAL — Auftraggeber ratifies)

**Target: ONE tier model, THREE tiers, ONE resolver.** Today three resolvers fragment it:
`requireParticipant` (token-only — a session-authenticated human is WRONGLY rejected on the routes that use it),
`requireCommReader` (token+session, throwing), `wsReaderOrNull` (same tier, non-throwing + `?token=`).

- **The three tiers:**
  - **public** — no auth (health, `authMe` unauth→`{authenticated:false}`, register-wrapper).
  - **participant (read)** — a valid **token** (agent / operator) OR a **verified human session** (OPERATOR →
    `OPERATOR_ID`, MEMBER → `identityId`, a first-class ACL read-subject, fail-closed empty until granted).
  - **operator (control/write)** — the operator **token** OR a verified **OPERATOR session**. Structural gate
    (the CYP-178 `authenticatedApi` group), fail-closed before the body is parsed.
- **ONE resolver, two adapters:** extract the tier resolution (the token→session cascade, single-sourced) into
  one function; a **throwing** adapter (REST → 401) and a **nullable** adapter (WS → close 1008) wrap it. So the
  RESOLUTION logic exists ONCE; only the throw-vs-null wrapper differs (a structural WS necessity, not a policy
  fork). Replace `requireParticipant`'s token-only uses with the unified read resolver (so a human session works
  everywhere a token does — closing the fragmentation). The `?token=` query fallback stays a documented
  WS-transport detail (browsers can't set `Authorization` on a WS handshake).

### Endpoint → tier table (PO-A F2 — the one silent widening flagged)

| Tier | Surface (representative — 234b does the exhaustive mapping) |
|---|---|
| **public** | `GET /api/health` · `GET /api/auth/me` (unauth → `{authenticated:false}`) · `POST /api/auth/register` |
| **participant (read)** | `GET /api/agents` (roster) · `/api/channels` · `/api/channels/{id}/messages` · `/api/inbox` · `/api/acl` · `/api/agents/{id}/avatar` + `/avatar/preview` · `GET /api/config` (masked) · `GET /api/projects` · **⚠ `GET /api/agents/{id}` (detail) — see widening below** · WS `/ws/comm` · `/ws/events` · `/ws/lifecycle` (read-tier) |
| **participant (write)** | `POST /api/channels/{id}/messages` (resolves like read; the `canWrite` ACL check is the downstream gate) |
| **operator** | `PUT /api/acl` · `POST/PUT/DELETE /api/agents` · avatar upload/clear · `POST /api/agents/{id}/{stop,start,restart}` · `PUT /api/config/*` · `POST /api/agents/{id}/connector` · `POST/PUT/DELETE /api/projects` + `/switch` · `PUT/DELETE /api/channels/{id}/share` · `POST /api/reports` · `GET /api/events` · `GET /api/workspace/members` · settings |
| **token-only WS (honest constraint)** | `/ws/agent` (drives/watches an agent process — §2.4) · `/ws/hub` (remote-**agent** wire — OUT of the frontend contract, §2.5) |

> **⚠ The ONE silent widening under unification (must be ratified, not assumed):** `GET /api/agents/{id}`
> (agent detail) is TODAY the **only** `requireParticipant` (token-ONLY) site (`AgentMgmtRoutes.kt:56`). Moving
> it onto the unified read tier makes it **session-readable** (a verified human MEMBER/OPERATOR could read agent
> detail, not just a token holder). This is consistent with `GET /api/agents` (the roster), already
> session-readable — so the widening is small and symmetric. **RATIFY: (a) accept it (agent detail joins the
> read tier — recommended, consistent with the roster), OR (b) keep agent-detail token-only as a documented
> exception.** No other endpoint's tier changes under unification (verified: `requireParticipant` has exactly
> one call site).

### The Trust/Access decisions the AUFTRAGGEBER must ratify (no boundary softening without this)

1. **What a TOKEN grants vs. a SESSION.** Token: an *agent* token → that agent's identity, scoped to its own
   `agentId`; an *operator* token → operator (workspace-wide). Session (Kratos): OPERATOR role → operator tier;
   MEMBER role → participant/read tier (ACL-subject). **Recommend: ratify as-is** (this is the current, audited
   posture — the tier model just makes it uniform, it does not widen it).
2. **How a BYO-frontend authenticates.** Two honest paths: **(a) browser frontend** (Web/Godot-web) → the
   same-origin Kratos **session cookie** (already works, CYP-229/230/232); **(b) non-browser frontend** (Go/CLI/
   Godot-native) → a **token**. Today the only non-browser tokens are the machine `HUB_TOKEN_*`/operator secrets
   (deploy-provisioned) — there is **no self-service per-frontend API-token flow**. **DECISION FOR THE
   AUFTRAGGEBER:** does a BYO non-browser frontend (i) reuse the operator token (simple, but grants operator —
   over-privileged for a read-only UI), (ii) get a **new participant-scoped API token** minted for it (a clean
   per-frontend credential — recommended, but it is NEW access surface he must authorize), or (iii) drive the
   Kratos login flow natively (`X-Session-Token`, already resolvable per `wsReaderOrNull`)? **Recommend (iii)
   for humans + (ii) as a future machine-frontend credential — but (ii) mints a new token class and MUST be his
   call.** No new token is minted in this design without ratification.
3. **Default tier of a BYO credential.** **Recommend: participant (read) by default; operator requires an
   explicit operator credential.** A BYO frontend must not get control access implicitly. Ratify.
4. **`/ws/agent` — token-only is a REAL constraint, kept honest.** A `/ws/agent` connection *drives/watches an
   agent process*; a plain MEMBER **session cannot bind to an agent process** (only the agent's own token, or an
   operator token / verified OPERATOR session — CYP-230). **A BYO frontend that wants to drive an agent needs an
   operator credential; a read-only BYO frontend uses the transcript/comm streams instead.** This boundary is
   NOT softened here — documented as a first-class constraint.
5. **`/ws/hub` is NOT a frontend transport.** It is the **remote-AGENT** BYOA wire (a bring-your-own-*agent*
   connects here), token-only, and it is exactly the surface **CYP-264** re-scopes (active-project). **Recommend:
   declare `/ws/hub` OUT of the frontend contract** (a frontend never speaks it) — it belongs to the connector/
   agent contract (CYP-118/Doc 10), documented separately. Ratify the exclusion.
6. **CORS / Origin policy for BYO frontends.** Today CORS is restricted to the configured web-client origin
   (`installRestrictedCors`). A BYO web frontend serves from a DIFFERENT origin → it needs its origin allow-
   listed. **DECISION FOR THE AUFTRAGGEBER:** the allowed-origins policy for BYO frontends (a configured allow-
   list — recommended, deploy-controlled — vs. wildcard, which he must explicitly accept as it widens the
   browser-reachable surface). Non-browser clients (Go/CLI) are unaffected by CORS.

**Dependent decisions (PO-A F3 — only live IF the decision they hang on is ratified):**

7. **Rate-limiting the new token class — DEPENDS ON #2.** IF the Auftraggeber ratifies a self-service
   participant token (#2.ii), a public-ish token surface needs abuse protection. **Options:** (a) a per-token
   bucket reusing the existing `WireRateLimiter` pattern (already defends `/ws/hub` per-agentId) — recommended;
   (b) deploy-level (reverse-proxy) rate-limiting only; (c) none. **Recommend (a)** — a per-token limiter, so a
   BYO frontend token can't flood the read surface. Moot if #2 stays session-only/(iii).
8. **Token lifecycle — DEPENDS ON #2.** IF a new token class is minted, it needs issuance / rotation /
   revocation / expiry. **Options:** (a) static long-lived (like the current `HUB_TOKEN_*` agent tokens) — simple
   but unrevocable; (b) **revocable + optional expiry, reusing the `RemoteTokenIssuer`/`RemoteTokenStore`
   mint+revoke pattern (CYP-171)** — recommended (a compromised or retired frontend token can be killed, same as
   a remote-agent token); (c) rotation on a schedule. **Recommend (b).** Moot if #2 stays session-only.
9. **CSRF / `allowCredentials` for a cross-origin cookie BYO web frontend — DEPENDS ON #6.** IF #6 widens CORS to
   a different origin AND that frontend authenticates by **cookie** (not token), the browser needs
   `allowCredentials=true` on CORS AND the same-origin CSRF assumption (CYP-231 double-submit) breaks for the
   cross-origin case. **Options:** (a) **cross-origin BYO web frontends authenticate by TOKEN, not cookie** — no
   `allowCredentials`, no cross-origin CSRF surface at all (recommended — the cleanest, keeps CYP-231's
   same-origin cookie model intact for the first-party SPA only); (b) `allowCredentials=true` + explicit
   cross-origin CSRF hardening (a real new attack surface he must accept). **Recommend (a)** — cookie auth stays
   same-origin (the first-party SPA); a third-party origin uses a token. Moot if #6 stays same-origin-only.

**So the Auftraggeber's ratification template is 6 primary + 3 dependent decisions.** The 3 dependents only
require a decision IF he ratifies the new-access-surface primaries (#2, #6); if he keeps the current posture
(session + machine-secret tokens, same-origin cookies), #7/#8/#9 fall away entirely. Honestly: **#1/#3/#4/#5 =
current audited posture (safe to ratify as-is); #2/#6 = genuinely new access surface; #7/#8/#9 = the
consequences of saying yes to #2/#6.**

## 3. ③ REST versioning — `/api/v1` PATH, additive migration

**Recommend: a PATH prefix `/api/v1`** (not a header). Rationale: trivial for any foreign client (no content-
negotiation subtlety), cache/proxy-friendly, greppable, and it matches the WS envelope-versioning precedent
(`/ws/hub` `WireEnvelope(v)`) — one versioning mental model. A header (`Accept-Version`) is more purist but
raises the floor for a simple Go/Godot client.

**Migration (additive, ZERO break):** mount the current routes ALSO under `/api/v1` (the same handlers, one
extra `route("/v1")` layer), so **both `/api/...` and `/api/v1/...` serve identically** during a deprecation
window. The OpenAPI spec declares **`/api/v1` canonical**; `/api` (unversioned) is documented as a
deprecated-alias that maps to v1. Existing clients (the Compose app) keep working on `/api`; new BYO clients
target `/api/v1`. A future `/api/v2` is additive next to v1; v1 stays until its deprecation window closes. The
route-enumeration drift-test (§1) pins BOTH prefixes during the window.

## 4. ④ Sizing cut (like .5a/b/c — build order after ratification)

- **234a — the UNBLOCKER: neutral contract + versioning + RENDERED HOSTED DOCS.** OpenAPI 3.1 (REST) +
  AsyncAPI (WS) generated from `:core`; the **bidirectional path/channel drift-test** + the **per-DTO
  schema-conformance tooth** (§1); the **spike proves nested-sealed + carves opaque `JsonElement`/contextual
  fields BEFORE pinning the generator** (§1); `/api/v1` mounting. **PLUS (Auftraggeber sharpening — the API
  docs are a FIRST-CLASS deliverable, "genaue Doku für zukünftige GUIs", not a by-product): a RENDERED,
  BROWSABLE API reference pulled EARLY** — Swagger-UI or Redoc over the OpenAPI + an AsyncAPI renderer over the
  WS side (clickable endpoints / params / schemas / frame-unions), **hosted on the staging box** (like the app)
  = one live reference URL. Because the spec is generated-from-`:core` + conformance-tested, the rendered docs
  are **accurate by construction** — the real value. The rendered docs stay **generated/drift-tested**, never
  hand-maintained-in-parallel (same no-drift discipline). Sub-cut: **234a-1** = the schema-fidelity core
  (walker + conformance + collision guard — DONE, proven); **234a-2** = the OpenAPI/AsyncAPI document assembly
  (paths + channels) + bidirectional path/channel drift-test + `/api/v1` dual-mount; **234a-3** = the rendered
  hosted docs. (🔴 blocker.)
- **234b — auth uniformity (ACCESS-RATIFIED).** The one-resolver/three-tier refactor + whichever BYO-frontend
  credential path the Auftraggeber ratified (§2) + CORS/origin policy. Gated on his access ratification. Every
  operation's tier lands in the spec. (🟡 ~1-2d, access-critical.)
- **234c — NARRATIVE GUIDE + per-endpoint examples (NO codegen client — Auftraggeber trim 2026-07-06).** The
  explicit "build-a-frontend-from-scratch" document (first-class deliverable, NOT "if time"): auth flows (the
  new participant token class + Kratos login), the error envelope, pagination (`afterSeq` + limit), rate-limits,
  the WS-frame protocol + reconnect/replay (CYP-198/204), **and worked examples per endpoint** — served
  alongside the rendered hosted reference (234a-3). Kept **generated/drift-tested where derivable** (examples
  validated against the generated schemas; the narrative references the generated spec, never a hand-maintained
  parallel) so the guide can't drift from the real API. **A generated reference client (Go/TS) is EXPLICITLY
  OUT of scope** (Auftraggeber decision — saves ~3-5 d/language; the hosted Swagger-UI/Redoc + AsyncAPI render +
  the narrative ARE the doc proof). (🟠/🟢.)

## 5. ⑤ Honest scope — real blocker vs. nice-to-have, and what does NOT change

- **Real blocker (must):** the **neutral spec** (234a). Without it a foreign client must read Kotlin. Everything
  else is additive hardening on an already-consumable runtime.
- **Access-critical (must, but gated on the Auftraggeber):** auth uniformity + the BYO-credential decision
  (234b). Not a blocker to READ the contract, but a blocker to a CLEAN BYO auth story.
- **Nice-to-have:** `/api/v1` (additive, protects foreign clients from breaks — do it in 234a, it's cheap),
  Go/Godot codegen + reference client (234c — proves it, but the spec unblocks without it).
- **What does NOT need to change (the runtime is already consumable):**
  - the **WS wire** — frames are already `{"type":…}` language-neutral; **no frame/wire change**;
  - the **auth MECHANISM** — token + session both already exist and carry across the surface (CYP-229/230/231/
    232); 234b UNIFIES the gates + documents, it does not add a new mechanism (unless the Auftraggeber ratifies a
    new per-frontend token class, §2.2);
  - **`/ws/hub` envelope versioning** — already present (v1);
  - the **route inventory** — already enumerable + test-pinned; the drift-test EXTENDS it, doesn't replace it;
  - the **serialization config** (`CommJson`) — `ignoreUnknownKeys=true` already makes the wire
    forward-compatible for foreign clients (unknown fields don't break them).
  - **No behavior/access change ships in this design** — it projects + unifies + versions. Any access-boundary
    change (a new token class, a wider CORS, a session path onto `/ws/agent`) is called out for ratification, not
    assumed.

## 6. Risks + open questions (beyond the §2 ratification list)

- **Generator fidelity for sealed/discriminator** (§1 tooling note): the 234a spike must prove the picked
  generator (or in-house walker) emits correct `oneOf`+`discriminator` for the real frame unions; a thin
  in-house descriptor walker is the fallback (it's ~the drift-test's own code).
- **`/ws/hub` in the spec:** excluded from the FRONTEND contract (§2.5) but it IS a documented protocol — put it
  in the CONNECTOR contract (CYP-118/Doc 10 lineage), not the frontend AsyncAPI, to keep the frontend surface
  honest. Confirm with the PO.
- **CORS widening** is the one place a BYO web frontend forces a real access decision (§2.6) — deploy-controlled
  allow-list recommended; wildcard only on explicit Auftraggeber acceptance.
- **Deprecation policy** for `/api` (unversioned): how long the alias lives — a PO/Auftraggeber call, documented,
  not urgent.
