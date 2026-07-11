# web-ts — CyppieAgents Web frontend (TypeScript/DOM rewrite)

Epic **CYP-397** (Spec `14-Web-HTML-Rewrite.md`). This is the **React + TypeScript + Vite** rewrite of the web
target. It **coexists** with the existing Compose/WASM web app (`app/webApp`) until parity is proven and the
WASM web target is retired (slice **W10**). The old app is untouched.

> **Why a root-level `web-ts/` (not `app/webApp-ts/`)?** This is a Node/npm/Vite build — a different build
> system from Gradle. `app/*` is the `:app:*` Gradle-module namespace (see `settings.gradle.kts`); nesting a
> non-module Node project there invites Gradle/IDE confusion. It sits alongside the other non-Gradle dirs
> (`deploy/`, `site/`, `scripts/`).

## Commands

```bash
cd web-ts
npm install
npm run dev        # local dev server (Vite) on :8080
npm run build      # tsc -b (typecheck) + vite build -> dist/
npm run typecheck  # types only
npm run preview    # serve the built dist/ on :8080
```

## Serving & security (Spec 14 §6 — decided)

- **Production serving is at the DEPLOY/PROXY, not Ktor.** Ktor stays API-only (REST + WS) + CORS for the SPA
  origin. The Vite `server`/`preview` ports here are **local dev only**.
- **Coexistence is port-based:** the new TS UI takes the former primary port (**:8080**); the old WASM UI moves
  to **:8085**. Both origins go in the server's `config.web.allowedOrigins` (never a wildcard). At cutover (W10)
  the `:8085` origin is removed from the allowlist and the instance stopped.
- **Operator token** is injected by the deploy/proxy as `globalThis.CYPPIE_OPERATOR_TOKEN` **before** the
  bundle (see `index.html`), never committed and never compiled in. Absent -> fail-closed MEMBER view. Read it
  via `src/platform/operatorToken.ts`. WS auth uses `?token=` (browser WS can't set headers); the proxy masks
  the query (CYP-292).

> The concrete proxy/origin/port wiring (allowlist entries, token-global injection, moving WASM to :8085) is
> the **closing step of W0** and lives in the deploy config — coordinated with Backend2, not committed here yet.

## Contract types (W1 / CYP-399)

TS types are **generated from the `:core` contract schema, never hand-written** (Spec 14 §3):

```bash
npm run contract:gen    # schema -> src/types/generated/contract.ts (gitignored, "generate don't commit")
npm run contract:check  # regenerate + tsc typecheck (fail-closed)
```

- **Input:** the real `:core` build-export at `contract/asyncapi.json` (Backend2's Gradle task — `/docs/*` is
  auth-gated, so the schema is a deterministic build artifact, never fetched from a live server). Until that
  lands, the generator falls back to `contract/asyncapi.provisional.json` (a faithful fixture) with a loud warning.
- **Discriminated unions:** `SchemaWalker` emits sealed unions as `oneOf` + `discriminator{propertyName:"type"}`,
  but the subtype schemas carry **no** `type` literal. So `scripts/generate-contract-types.mjs` injects the
  discriminant from `discriminator.mapping` into each member → real TS discriminated unions (`type: "system"` …).
  The wire already carries `type`; `:core`/the server are unchanged. Fail-closed: an unresolvable mapping, or a
  literal that doesn't survive codegen, errors the generator.

> **Seam to reconcile with Backend2:** the export **path** (`web-ts/contract/asyncapi.json`), **format**
> (ContractGenerator AsyncAPI 2.6 + JSON-Schema components), and whether the schema-vs-`:core` **regen-diff**
> lives in a Gradle test (extending `AsyncApiContractTest`) are the export-seam details to confirm.

## Net layer (W2 / CYP-400)

`src/net/` — the WS/REST clients (Spec 14 §2.2). `npm test` runs the vitest suite.

- `reconnectingSocket.ts` — the channel-agnostic reconnecting WebSocket all 8 channels share: `?token=` auth
  (browser WS can't set headers; proxy-masks the query, CYP-292), backoff reconnect, clean teardown. Socket
  factory + scheduler are injectable so reconnect/idempotency are deterministically unit-tested.
- `agentSocket.ts` — `/ws/agent`: `StoredAgentEvent` stream, `?since=<seq>` replay on reconnect, **`seq`
  idempotency** (drops `seq <= cursor` → no reconnect duplicates, the CYP-400 AC), sends `UserTurn`.
- `oneWayFeed.ts` — generic server→client feed for the read-only channels (lifecycle/token-usage/busy-state/
  terminal-state); concrete instances are one-liners once each channel's type is generated.
- `rest.ts` — `/api/*` base: Bearer operator-token (when present) OR session cookie (`credentials:"include"`).

> **Scope note:** this increment lands the shared infra + the `/ws/agent` client + REST base, fully typed
> against W1's generated types and tested (backoff, reconnect, dedup). The remaining channels (comm/events bidi;
> the 4 one-way feeds) wire on top trivially, but their *types* need either the fixture expanded per channel
> (hand-modelling — the thing W1 avoids) or Backend2's real export — see the report/flag.

## Agent transcript (W3 / CYP-401)

`src/agentview/` — the `/ws/agent` stream as **structured DOM** (not xterm; §4). Pure logic is a faithful port of
`:app:shared` with a **behavioral golden corpus** (`streamJsonMapper.test.ts`) pinning it against the Kotlin
`StreamJsonMapper`:

- `streamJsonMapper.ts` — wire `StreamJsonEvent` → UI `AgentEvent` rows (stateful tool_use→tool_result
  resolution, fire-once ready notice, injected-message surfacing, start-time carry-forward, summary truncation).
- `transcriptFolding.ts` — folds the stream (assistant deltas concatenate; tool calls update in place; terminal
  rows de-dup by id → reconnect-replay safe).
- `transcriptTime.ts` — pure `HH:mm` formatter (DST-correct per-instant offset).
- `AgentTranscript.tsx` — the renderer, one row per event kind. `useAgentTranscript.ts` wires socket→mapper→fold.

**XSS (security AC):** all agent-supplied text renders as React text children (escaped). **No `innerHTML` /
`dangerouslySetInnerHTML`** — enforced by `noInnerHtml.test.ts` (source guard) and proven escaped by
`AgentTranscript.render.test.tsx` (a `<script>`/`<img onerror>` payload renders as literal text, no live element).

> **CSP is a proxy concern, on purpose.** The only inline script in production is the deploy's operator-token
> global (`index.html`), so a correct `script-src` needs a **nonce the proxy owns** — a meta CSP here would
> intersect with (and fight) the proxy's header. The app is kept CSP-friendly (external bundle, no inline scripts
> of ours, no `eval`); the authoritative CSP (`default-src 'self'; connect-src 'self' ws: wss:; object-src
> 'none'; base-uri 'none'; script-src 'self' 'nonce-…'`) is set at the proxy — coordinated with Backend2/deploy.

## Design tokens (CYP-423)

The maritime M3 palette is ported **1:1** from the Compose scheme (`app/shared/.../ui/MaritimeTheme.kt`) into a
CSS `--md-sys-color-*` layer — "port tokens, not optics". Single source, generated CSS (like the contract):

```bash
npm run tokens:gen   # src/ui/maritimeTokens.data.mjs -> src/ui/tokens.generated.css (gitignored)
```

- **Source of truth:** `src/ui/maritimeTokens.data.mjs` (24 roles × light/dark). The generator emits all four
  theme blocks (light base + `prefers-color-scheme: dark` + explicit `[data-theme]` overrides both ways) from that
  one source, so light/dark can't drift. `src/ui/maritimeTokens.ts` is the typed TS view + the WCAG contrast util.
- **a11y is MEASURED, not assumed** (`maritimeTokens.test.ts`): the transcript scrollbar thumb binds to the
  `outline` token — measured **3.55:1 / 3.63:1** on the surface (WCAG 1.4.11 ≥3:1). This replaces the W6
  `rgba(128,128,128,0.7)` thumb, which *composited* to ~2.35:1 and only looked fine — the code comment measured the
  solid colour, not the alpha (assumption-as-measurement). Thumb is a **solid** colour + `min-height: 24px`
  (WCAG 2.5.8); Firefox `scrollbar-width: thin` has no min-size hook (known UA limit; webkit carries it).

## Coexistence & cutover (W10 / CYP-408)

Port-based coexistence (Spec 14 §6), no big-bang. Both UIs run as **separate origins on separate ports**, one
Ktor API server (unchanged) behind the deploy/proxy serves both.

**Client-owned (here):**
- `src/platform/appConfig.ts` — the API/WS base is read from deploy globals (`CYPPIE_API_BASE`, optional
  `CYPPIE_WS_BASE`), since the SPA origin may differ from the API origin (cross-origin → CORS). Falls back to
  same-origin if no global (so a same-origin proxy also works). Operator token stays a separate global (W0).
- The build is proxy-servable (hashed assets under `/assets`, tokenless `index.html`).

**Deploy/server-owned (coordinate with Backend2/deploy — NOT in this repo's web-ts):**
- New TS UI → the former **primary port**; old WASM UI (`app/webApp`) → **:8085**.
- **`config.web.allowedOrigins`**: exactly the **two** origins, never a wildcard (server CORS).
- Per-origin **operator-token global** injected at serve time; WS `?token=` proxy-masked (CYP-292).
- Authoritative **CSP** at the proxy (nonce for the token global); the app is kept CSP-friendly.

**Cutover checklist (gated; PO2 approves):**
1. Functional **parity** proven against `09-UI-Funktionskatalog`.
2. **XSS/CSP gate** green (no `innerHTML`; CSP live) and the **contract-drift guard** green (`CONTRACT_REQUIRE_REAL`
   in CI — the real `:core` export, not the fixture).
3. Flip the web **default to the TS UI**.
4. **Retire the WASM web** — stop the `:8085` instance AND remove its origin from `config.web.allowedOrigins`
   (a dead-but-allowed origin is a leftover attack surface). Desktop/Android Compose are untouched.

> **Open (Anhang A, backend contract):** the `ERROR`-state *reason* is a Backend2 question; until answered the UI
> says "Fehler — Grund nicht gemeldet" (fail-closed), inventing nothing. Not coupled to the CYP-396 ring fix.

## Layout (grows over the epic — Spec 14 §2.2)

```
web-ts/
  index.html            # tokenless by construction; deploy injects the operator global before the bundle
  src/
    main.tsx            # mounts <App/> into #root
    App.tsx             # W0 skeleton
    platform/           # operator-token seam (W0); more platform glue later
    net/                # WS/REST clients (W2)
    types/              # GENERATED from :core (W1) — never hand-maintained
    windowmgr/          # DOM window manager (W4)
    agentview/          # stream renderer + xterm shell toggle (W3/W7/W8)
    comm/               # channels, timeline, ACL matrix (W9)
    state/              # Zustand store
```
