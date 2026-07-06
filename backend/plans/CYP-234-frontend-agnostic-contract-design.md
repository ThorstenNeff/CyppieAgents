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
  Each `@SerialName` is the mapping key. This is the crux of the projection and it is well-defined for every
  WS frame union (`CommWsServerEvent`, `StreamJsonEvent`, the `/ws/hub` `WireEnvelope`, …) and any polymorphic
  REST DTO (`AgentAvatar`).
- **Paths/operations (REST) + channels+operations (WS) = hand-authored in the spec, DRIFT-TESTED.** Routes
  live in Ktor wiring, not `:core`, so they can't be descriptor-generated. The guarantee: extend the EXISTING
  route enumeration (`ProtectedRouteEnumerationTest.kt:83`, already the authoritative route inventory) into a
  **contract drift-test** — assert the spec's path set == the actual route set (both directions: no spec path
  without a route, no route without a spec path), and each WS channel + its frame union is present. A new route
  or a renamed frame without a spec update FAILS `check`.
- **Auth + errors in the spec:** every operation declares its security tier (§2) + the uniform error envelope
  (`{error:{code,message}}`) as a shared response schema.

**Sync/drift guarantee (replaces the compiler guarantee):** `./gradlew check` runs (a) the schema generator
(schemas ARE the types → zero schema drift) + (b) the path/channel drift-test (paths match the route inventory
→ zero path drift). So "one contract, both sides" becomes "generated-schema + drift-tested-paths, enforced in
CI" — an equivalent no-drift property, now language-neutral. **This is the load-bearing decision of ①.**

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

- **234a — the UNBLOCKER: neutral contract + versioning.** OpenAPI 3.1 (REST) + AsyncAPI (WS) generated from
  `:core` + the path/channel drift-test + `/api/v1` mounting. After 234a, a foreign client can be built against
  a real spec. (🔴 blocker, ~2-3d + ~1d versioning.)
- **234b — auth uniformity (ACCESS-RATIFIED).** The one-resolver/three-tier refactor + whichever BYO-frontend
  credential path the Auftraggeber ratified (§2) + CORS/origin policy. Gated on his access ratification. Every
  operation's tier lands in the spec. (🟡 ~1-2d, access-critical.)
- **234c — codegen + reference client + protocol docs.** Generate a Go (and/or Godot) client from the spec; a
  minimal reference client (CLI or Go) as a LIVING contract proof + the human-readable protocol/auth/error docs.
  (🟠/🟢, proves the contract; the spec alone already unblocks.)

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
