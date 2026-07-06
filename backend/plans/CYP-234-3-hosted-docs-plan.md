# CYP-234a-3 — Rendered Hosted API Docs: Durable Build Plan

> Status: **DURABLE SCOPE — pre-scoped while 234a-2a/2b gate. Build after 2b.** A Ktor route renders Redoc
> (REST) + AsyncAPI (WS) from `ContractGenerator.openApi()` / `.asyncApi()` **directly** — single source, no
> checked-in artifact, accurate-by-construction. Wrapped in UIUX's maritime shell (`feature/CYP-234-dev-api-
> docs-experience`, tokens v1.2; §6=10 / §8=8 are UIUX's acceptance).

## 0. Goal + the single-source guarantee

A browsable API-docs surface for BYO-frontend builders (Go/Godot/Web/CLI). The spec JSON is **generated on
request** from the merged 234a-2a generators — so it can never drift from the code (no build step, no committed
`openapi.json`). This is the payoff of the whole 234a fidelity chain: conformance + tightness + drift already
proved the generators are correct; 234a-3 just serves them.

## 1. Routes (backend — this slice)

| method + path | serves | tier (DECISION — §4) |
|---|---|---|
| GET `/docs/openapi.json` | `ContractGenerator.openApi()` (application/json) | (see §4) |
| GET `/docs/asyncapi.json` | `ContractGenerator.asyncApi()` (application/json) | (see §4) |
| GET `/docs` | HTML index → links REST + WS views, maritime shell | (see §4) |
| GET `/docs/rest` | Redoc page pointing at `/docs/openapi.json` | (see §4) |
| GET `/docs/ws` | AsyncAPI render pointing at `/docs/asyncapi.json` | (see §4) |

**NOTE — new capability:** the server currently serves **no static content** (API-only; the WASM app is hosted
separately). 234a-3 adds Ktor `staticResources` for the vendored renderer assets (§2). Keep `/docs` OUTSIDE the
`/api` + `/api/v1` versioning loop (docs are not a versioned API resource).

## 2. Renderer delivery — the KEY decision (CSP / CYP-226)

Redoc and the AsyncAPI renderer are large JS bundles. **CYP-226 lesson: no external CDN / no dep leaking onto a
runtime classpath.** Two options:

- **(A) VENDOR the standalone bundles as server resources** (RECOMMENDED): commit `redoc.standalone.js` (~1 MB)
  and the AsyncAPI standalone bundle under `server/src/main/resources/docs/`, served via `staticResources`. The
  `/docs/*` HTML references them same-origin. Fully self-contained, CSP-clean, offline-capable, no CDN. Cost:
  ~vendored JS in the repo + a license/attribution note (Redoc = MIT community edition; AsyncAPI = Apache-2.0).
- **(B) Pinned CDN `<script>`** — smallest diff, but violates the self-contained posture + needs a CSP
  `script-src` allowance for the CDN host. NOT recommended (same class of risk as CYP-226).

**Recommend (A).** Flag: bundle size + the vendor/license step is a human-reviewable add (like the DiceBear PNG
bundling in CYP-215) — the PO/human OKs committing the vendored JS.

## 3. The maritime shell — UIUX boundary (COORDINATION)

UIUX owns the maritime shell (tokens v1.2 on `feature/CYP-234-dev-api-docs-experience`; §6/§8 acceptance).
Redoc/AsyncAPI have their own theming — the shell is the **surrounding chrome** (header, nav between REST/WS,
maritime palette), not the spec-body rendering. **Boundary proposal (for the coordinator to confirm):**
- Backend (me): serve the JSON + minimal `/docs*` HTML host pages + vendored renderer + a Redoc `theme` /
  AsyncAPI CSS-vars hook fed from the maritime tokens.
- UIUX: the shell HTML/CSS (maritime tokens → the header/nav/palette), delivered as a static asset I embed, OR
  a template I fill. Confirm who authors the outer HTML so §6/§8 acceptance is unambiguous.

## 4. Auth tier — DECISION NEEDED (access-surface, like the §2 decisions)

Who may read `/docs`? The spec reveals the full API SURFACE (endpoint shapes, tiers) — no secrets, but it is a
disclosure. Options:
- **public** — any reacher of the (localhost-/staging-bound) box; matches "docs for BYO builders". Simplest.
- **participant/authenticated** — same gate as the app; docs only for logged-in users.
- **operator** — tightest; only operators browse.

**Recommend: same posture as the app shell** (if the SPA is reachable to a caller, the docs are too) — likely
**public on the access-controlled staging box**, since the point is external frontend builders reading it. This
is an Auftraggeber/PO access call (parallel to §2). Fail-safe default until decided = **authenticated** (not
public), tightenable later.

## 5. Tests (the gate)

- **Single-source:** `GET /docs/openapi.json` body == `ContractGenerator.openApi()` (and asyncapi likewise) —
  proves served == generated, no drift. (Booted via the same test harness; assert byte-equality.)
- **Served spec is valid:** run the existing `SchemaConformance`/structure checks over the served document
  (reuses 234a-1/2a teeth) — the served spec parses + every component resolves.
- **Smoke:** `/docs`, `/docs/rest`, `/docs/ws` → 200 + `text/html`; the vendored JS resource → 200.
- **Auth:** `/docs*` honors the §4 tier (fail-closed probe if gated).

## 6. Scope + discipline

Branch `feature/CYP-234-3-…` off the merged 2b tip. **Gate = single-source equality + served-spec-valid + smoke
+ (if gated) auth + `:server:check` green.** `:e2e` optional (new routes, no behavior change to existing ones).
The vendored-JS commit is a human-review step (§2). Coordinate the shell boundary with UIUX (§3) BEFORE building
the HTML host. Commit prefix `CYP-234:`.
