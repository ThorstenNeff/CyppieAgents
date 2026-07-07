# CYP-234c — "Build a Frontend for CyppieAgents": Narrative Guide Outline (durable)

> Status: **DURABLE OUTLINE — write the full guide FRESH from this (survives a compact). Non-urgent (docs, no
> deploy dep).** 234c = a narrative build-a-frontend guide + per-endpoint examples. **No codegen reference
> client** (PO scope-trim). Off develop `4935a2d` (CYP-234a + b complete). Audience = a third-party dev building
> a Go/Godot/Web/CLI frontend against the platform.

## 0. The "cannot lie" principle (write this as the guide's spine)

Every contract claim in the guide must reference the **generated, drift-guarded** artifacts — the same ones the
tests pin — so the guide is accurate BY CONSTRUCTION, exactly like the hosted docs. When writing a shape/route,
pull it from the source below, never from memory. If a section can't cite a drift-guarded source, flag it.

## 1. Sections + the drift-guarded source each pulls from

1. **What this is.** A frontend-agnostic platform API: multiple agent terminals, a comm hub, event log, project
   lifecycle. Frontend-neutral contract so Go/Godot/Web/CLI all build against it. — src: `02-Technische-Spezifikation.md`, `05-MVP-Scope`.
2. **The contract, hosted + generated.** OpenAPI 3.1 (REST) + AsyncAPI 2.6 (WS), generated from `:core`
   (kotlinx.serialization) — accurate by construction, drift-tested against the live routing. Browsable at
   `/docs` (Redoc + AsyncAPI, maritime shell). — src: `ContractGenerator.openApi()/asyncApi()`, `DocsRoutes`,
   `RestContractDriftTest`, `AsyncApiContractTest`, `SchemaConformance`/`SchemaTightnessTest` (the fidelity proof).
3. **Versioning.** Every REST endpoint is served under BOTH `/api` (current) and `/api/v1` (pin this). Identical
   by construction (one dual-mount loop). — src: `PlatformWiring` for-loop, `ApiVersioningGateTest`.
4. **The two credential paths (the heart — from the ratified §2.2 model).**
   - **Machine / non-browser (Go/Godot/CLI): the participant-scoped Bearer token.** How to obtain it (an
     OPERATOR mints via `POST /api/participant-tokens {subject, ttlMs?}` → the raw token, disclosed ONCE),
     where it goes (`Authorization: Bearer <token>`; WS `?token=` query fallback — a browser WS can't set an
     Authorization header — ⚠ note CYP-292: the deploy proxy must strip `?token=` from logs). Its tier =
     participant/**read-default** (never operator/member), revocable (`DELETE /api/participant-tokens?subject=`),
     optional expiry, per-token rate-limit (429). — src: `ParticipantTokenStore`, `ParticipantTokenRoutes`,
     `Auth.kt` resolvers, `ParticipantTokenResolverTest`, `ParticipantRateLimitTest`.
   - **Human / browser: native Kratos login → same-origin session cookie.** `GET /api/auth/me` (content-free
     whoami), register, settings. — src: `AuthMeRoutes`, `RegisterRoutes`, `SettingsRoutes`, `resolveAuthState`.
   - **Which path for which frontend type** (a decision table). — src: `05-MVP`, the spec §2.2.
5. **Authorization tiers.** public / participant (a read-SUBJECT — the ACL `canRead`/`canWrite` is the ONLY
   authz, fail-closed-empty until an operator grants) / member (verified human) / operator. The participant
   token is READ-tier by construction and **never** satisfies the member/operator gate (the ①-hardening — a
   participant/garbage bearer cannot read `/api/events`). — src: `RestContract` (per-op `tier` + `x-auth-tier`),
   the tier-tooth in `ApiVersioningGateTest`, `resolvePrincipal` (①-fix), `ParticipantTokenResolverTest`.
6. **CORS (cross-origin web frontends).** A deploy-managed allow-list (your origin must be listed; incl. the
   docs origin for a Swagger "Try it"); cross-origin auth is by **token, not cookie** → no `allowCredentials`,
   no cross-origin CSRF; DELETE allowed. Non-browser clients (Go/CLI) are CORS-independent. — src: `Cors.kt`.
7. **The core flows (with examples).** Connect the WS channels (`/ws/comm` `CommWsServerEvent`/`CommWsClientEvent`,
   `/ws/events`, `/ws/lifecycle`, `/ws/agent`); list channels / read messages / inbox / ACL; send a message
   (`POST /api/channels/{id}/messages`, ACL `canWrite`-gated → 201/403); the agent stream. `/ws/hub` +
   `/mcp/hub` are the connector wire, NOT frontend transport (excluded). — src: the AsyncAPI channels
   (`ContractGenerator.WS_CHANNELS`), the REST comm ops in `RestContract`.
8. **Per-endpoint examples (the 234c deliverable).** For each key endpoint: method, path, tier, an example
   request body + response (drawn from the generated component schemas). Cover: auth (mint/me), agents (list/
   detail/avatar), channels/messages/inbox/acl, projects/switch, events, config, reports. — src: `RestContract.REST_OPS`
   + the walker components (do NOT hand-invent shapes — read the generated schema).
9. **Errors.** The uniform `{error:{code,message}}` envelope + the status map (400/401/403/404/409/413/429). — src:
   `ApiErrorBody`, `ApiException` subclasses.
10. **Deploy conditions (operator-facing appendix, NOT the frontend dev).** The one Auftraggeber deploy GO
    bundles: CORS docs-origin config + CYP-292 proxy `?token=` log strip + the ①-fix live-hole-close. Frontend
    devs don't action these; note they exist. — src: this outline, the CYP-292 pointer in `Auth.kt`.

## 2. Sub-deliverables (234c scope, PO-ratified)

- The narrative guide (sections §1). Language = EN.
- Per-endpoint examples (§8) — drawn from the generated schemas, NOT hand-authored shapes.
- NO codegen reference client (trimmed).
- Optional: a "docs-that-can't-drift" test that asserts the guide's cited routes/shapes still exist (a light
  drift check over the guide's endpoint list vs `RestContract`) — decide at write time; keeps §0 honest.

## 3. Discipline

Write off the then-current develop tip (fetch first — the guide cites live routes). Every shape/route from a
drift-guarded source (§0). Docs-only; no code behavior. `docs/` or `backend/docs/` location TBD with the
coordinator. Non-urgent — quality over speed.
