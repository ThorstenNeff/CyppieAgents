import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// CYP-669 (S-A) — the SPA ships on Staging as a SECOND surface under its OWN subpath, while the WASM SPA stays the `/`
// default (Option B, no cutover). Default base = `/webts/`, MEASUREMENT-confirmed (deploy read the live Caddy config
// 2026-07-17: `/webts/` collides with no existing prefix — /api·/ws·/.ory·/relay·/ — and is same-origin on the one
// origin api.cyppie-agents.com, so the Kratos httpOnly cookie carries to /api·/ws·/.ory; `handle @webts /webts/*` sits
// BEFORE the WASM catch-all). So a plain `vite build` emits the REAL deploy/webts-dist artifact under /webts/ — Vite
// base-prefixes every emitted asset + dynamic-import URL so they resolve there (no 404 on /webts/assets/…, incl. the
// CYP-665 lazy xterm chunk). The path stays a thin PARAM (CYPPIE_BASE_PATH, or `--base` on build/preview) for deploy
// flexibility; CYPPIE_BASE_PATH=/ escapes back to root serving.
//
// SAME-ORIGIN is untouched by base: `base` only rewrites STATIC asset URLs. All runtime API/WS/Kratos calls anchor to
// the ORIGIN ROOT — appConfig.apiBaseUrl()=location.origin (path-independent) + authConfig's absolute `/self-service/*`
// and `/.ory/…` paths — so /api/*, /ws and the httpOnly-cookie login stay first-party at the subpath (Kratos precond).
const DEFAULT_BASE = '/webts/' // deploy-measured, PO1-locked (2026-07-17)
function resolveBase(): string {
  const raw = process.env.CYPPIE_BASE_PATH?.trim()
  if (raw === undefined || raw === '') return DEFAULT_BASE // plain build → the real /webts/ artifact
  if (raw === '/') return '/' // explicit escape hatch to root serving
  return `/${raw.replace(/^\/+/, '').replace(/\/+$/, '')}/` // `webts` | `/webts` | `/webts/` → `/webts/`
}

// CYP-398 (W0). The production SPA is served at the DEPLOY/PROXY, not by Ktor (Spec 14 §6); Ktor stays
// API-only + CORS. These `server`/`preview` ports are the LOCAL dev/preview servers only. Coexistence is
// port-based (Spec 14 §6): the new TS UI takes the former primary port (8080) and the old WASM UI moves to
// :8085. The real proxy/origin wiring (`config.web.allowedOrigins`, token-global injection) is the closing
// step of W0 and lives in the deploy config, not here.
export default defineConfig({
  // CYP-669: served-subpath (deploy-measured default `/webts/`; CYPPIE_BASE_PATH / --base override). See note above.
  base: resolveBase(),
  plugins: [react()],
  server: { port: 8080, strictPort: true },
  preview: { port: 8080, strictPort: true },
  // CYP-455 (CYP-422-prep) — CSP nonce seam, kept so the cutover CSP can hold `script-src` STRICT (no
  // `unsafe-inline`) — the actual XSS defense. The only inline script at cutover is the deploy-injected operator
  // token in index.html, carrying a per-response `nonce` (seam documented there). CSP header + nonce stamp are
  // DEPLOY-owned and activated at cutover under Auftraggeber-GO — this is only the web-ts-side prep.
  //
  // MEASURED CORRECTION (2026-07-18, vite 6.4.3): the older note here claimed `polyfill=false` is what removes
  // Vite's "one prod inline `<script>`". That is NOT true on Vite 6 — the module-preload polyfill is emitted into
  // the ENTRY JS CHUNK, not as an inline `<script>` in index.html (verified: the `modulepreload` marker count in
  // dist/assets/index-*.js goes 1→2 when flipped on; index.html emits ZERO inline scripts either way). So this
  // line is kept because it drops dead polyfill code from the bundle — NOT because it is load-bearing for the CSP.
  // The CSP precondition is enforced where it actually matters, on the built artifact, by `npm run check:csp`
  // (scripts/check-csp-seam.mjs), which fails closed on ANY inline script regardless of what produced it.
  build: { modulePreload: { polyfill: false } },
})
