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
