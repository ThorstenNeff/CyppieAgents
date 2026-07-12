import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * CYP-422 same-origin parity — TEST-ONLY config owned by the QA parity rig (web-e2e/), co-located here only so
 * Vite resolves `vite`/`@vitejs/plugin-react` from web-ts/node_modules. It NEVER ships and is not referenced by
 * any product build (web-ts/vite.config.ts is the product config, unchanged); it is used solely by
 * web-e2e/playwright.parity.config.ts via `npx vite --config vite.parity.config.ts`.
 *
 * It models the CONFIRMED production topology (PO1: reverse-proxy = ONE origin; the auth stack — CYP-230
 * cookie-session, CYP-31 origin-guard, /ws/agent cookie — is same-origin by design). The browser only ever talks
 * to Vite on :8080, which proxies /api + /ws to the hermetic Ktor harness on :8791 — exactly what a reverse proxy
 * does, so there is no CORS and no allowCredentials question. With NO CYPPIE_API_BASE/WS_BASE global injected
 * (web-e2e/parity/fixtures.ts), appConfig falls back to location.origin (:8080): the SPA and its API share an origin.
 */
const KTOR = 'http://127.0.0.1:8791'
// The operator token (E2ePlatform.OPERATOR_TOKEN). In production /ws/agent authenticates via the same-origin
// Kratos session COOKIE (CYP-454) — the token-based hermetic harness has no Kratos, so the reverse proxy here
// injects the operator's upstream auth as a Bearer token (an auth mode /ws/agent also accepts: operator token via
// Authorization/?token=). This is orthogonal to what the seq-?since/reconnect tooth tests (the transcript FEED);
// it just lets the real feed authenticate against the hermetic harness — the reverse proxy's job.
const OPERATOR_TOKEN = 'e2e-operator-token'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 8080,
    strictPort: true,
    proxy: {
      // REST + WS forwarded server-side to the harness → the browser sees a single origin (no CORS).
      '/api': { target: KTOR, changeOrigin: false },
      // Inject the operator Bearer on the WS UPGRADE upstream: /ws/agent (CYP-454) sends no ?token= from the
      // client, so the hermetic harness (no Kratos cookie) would else reject the handshake. The `headers` option
      // does NOT apply to WS upgrades (http-proxy limitation), so set it on the proxyReqWs event. Harmless for
      // /ws/comm|terminal (they also carry ?token=). Models the reverse proxy forwarding the operator's auth.
      '/ws': {
        target: KTOR,
        ws: true,
        changeOrigin: false,
        configure: (proxy) => {
          proxy.on('proxyReqWs', (proxyReq) => {
            proxyReq.setHeader('Authorization', `Bearer ${OPERATOR_TOKEN}`)
          })
        },
      },
    },
  },
})
